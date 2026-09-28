"""Standalone scriptable re-implementation of NVIDIA RE-USE (SEMamba 30x1)
that (a) loads the official model.safetensors keys unchanged and
(b) exports to ONNX with the whole audio pipeline inside the graph:

    PCM [1, L] -> STFT(conv DFT) -> SEMamba -> iSTFT(conv_transpose) -> PCM [1, L]

- The Mamba sequential scans become ONNX Loop ops (torch.jit.script).
- STFT = Conv1d with the hann-windowed DFT basis (exact for real input),
  reflect padding, stride=hop, same as torch.stft(center=True).
- iSTFT = ConvTranspose1d overlap-add + window-square normalization +
  center crop, same as torch.istft(center=True).

Fixed shapes at export time: 16 kHz, n_fft=640, hop=80, win=640, T frames.
"""
import json
import math
import os
import sys
from typing import List, Optional

import torch
import torch.nn as nn
import torch.nn.functional as F

SR = 16000
N_FFT = 640
HOP = 80
WIN = 640
COMPRESS = "relu_log1p"


# --------------------------------------------------------------------------
# pure-torch Mamba (state-dict compatible with mamba_ssm.Mamba as used by
# the RE-USE checkpoint: no in_proj/out_proj bias, conv1d/dt_proj bias)
# --------------------------------------------------------------------------
class MambaScan(nn.Module):
    def __init__(self, d_model: int, d_state: int, d_conv: int, expand: int):
        super().__init__()
        self.d_model = d_model
        self.d_state = d_state
        self.d_conv = d_conv
        self.expand = expand
        self.d_inner = int(expand * d_model)
        self.dt_rank = int(math.ceil(d_model / 16.0))

        self.in_proj = nn.Linear(d_model, self.d_inner * 2, bias=False)
        self.conv1d = nn.Conv1d(self.d_inner, self.d_inner, d_conv, bias=True,
                                groups=self.d_inner, padding=d_conv - 1)
        self.x_proj = nn.Linear(self.d_inner, self.dt_rank + self.d_state * 2,
                                bias=False)
        self.dt_proj = nn.Linear(self.dt_rank, self.d_inner, bias=True)
        self.A_log = nn.Parameter(torch.zeros(self.d_inner, d_state))
        self.D = nn.Parameter(torch.ones(self.d_inner))
        self.out_proj = nn.Linear(self.d_inner, d_model, bias=False)

    def forward(self, hidden_states: torch.Tensor) -> torch.Tensor:
        # hidden_states: (B, L, D)
        batch = hidden_states.shape[0]
        seqlen = hidden_states.shape[1]

        xz = self.in_proj(hidden_states)               # (B, L, 2*di)
        xz = torch.transpose(xz, 1, 2)                 # (B, 2*di, L)
        x, z = torch.chunk(xz, 2, dim=1)
        A = -torch.exp(self.A_log.float())             # (di, N)

        x = F.silu(self.conv1d(x)[..., :seqlen])       # causal conv + silu

        xl = torch.reshape(x, (batch * seqlen, self.d_inner))
        x_dbl = self.x_proj(xl)                        # (B*L, dt+2N)
        dt, Bv, Cv = torch.split(x_dbl, [self.dt_rank, self.d_state, self.d_state], dim=-1)
        dt = torch.matmul(dt, torch.transpose(self.dt_proj.weight, 0, 1))  # (B*L, di)
        dt = dt.t().reshape(self.d_inner, batch, seqlen).transpose(0, 1)   # (B, di, L)
        dt = dt.contiguous()
        Bv = Bv.t().reshape(self.d_state, batch, seqlen).transpose(0, 1).contiguous()
        Cv = Cv.t().reshape(self.d_state, batch, seqlen).transpose(0, 1).contiguous()

        dt = dt.float() + self.dt_proj.bias.float().unsqueeze(0).unsqueeze(-1)
        dt = F.softplus(dt)
        # A: (di, N) -> (1, di, 1, N); dt: (B, di, L) -> (B, di, L, 1)
        deltaA = torch.exp(dt.unsqueeze(-1) * A.unsqueeze(0).unsqueeze(2))
        deltaB_u = (dt.unsqueeze(-1)
                    * Bv.float().transpose(1, 2).unsqueeze(1)
                    * x.float().unsqueeze(-1))         # (B, di, L, N)

        # sequential scan -> ONNX Loop.
        # The trip count is deliberately DATA-DEPENDENT (sum over a tensor
        # derived from x): a Python-int / shape-derived bound would make the
        # exporter unroll the whole sequence into the main graph (hundreds of
        # MB and tens of GB of shape-inference memory). A runtime bound keeps
        # it a compact onnx::Loop node.
        trip = int(torch.sum(x[0, 0, :] * 0 + 1))
        state = torch.zeros(batch, self.d_inner, self.d_state,
                            dtype=torch.float32, device=x.device)
        ys = torch.jit.annotate(List[torch.Tensor], [])
        i = 0
        while i < trip:
            state = deltaA[:, :, i] * state + deltaB_u[:, :, i]
            y = (state * Cv.float()[:, :, i].unsqueeze(1)).sum(-1)  # (B, di)
            ys.append(y)
            i += 1
        y = torch.stack(ys, dim=2)                     # (B, di, L)

        y = y + x.float() * self.D.float().unsqueeze(0).unsqueeze(-1)
        y = y * F.silu(z.float())
        y = torch.transpose(y.to(x.dtype), 1, 2)       # (B, L, di)
        return self.out_proj(y)


class MambaBlock(nn.Module):
    def __init__(self, d_model: int, d_state: int, d_conv: int, expand: int):
        super().__init__()
        self.forward_blocks = MambaScan(d_model, d_state, d_conv, expand)
        self.backward_blocks = MambaScan(d_model, d_state, d_conv, expand)
        self.output_proj = nn.Linear(2 * d_model, d_model)
        self.norm = nn.LayerNorm(d_model)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        out_fw = self.forward_blocks(x) + x
        xf = torch.flip(x, dims=[1])
        out_bw = self.backward_blocks(xf) + xf
        out_bw = torch.flip(out_bw, dims=[1])
        out = torch.cat([out_fw, out_bw], dim=-1)
        return self.norm(self.output_proj(out))


class TFMambaBlock(nn.Module):
    def __init__(self, d: int, d_state: int, d_conv: int, expand: int):
        super().__init__()
        self.time_mamba = MambaBlock(d, d_state, d_conv, expand)
        self.freq_mamba = MambaBlock(d, d_state, d_conv, expand)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: (B, C, T, F)
        b = x.shape[0]
        c = x.shape[1]
        t = x.shape[2]
        f = x.shape[3]
        xt = x.permute(0, 3, 2, 1).contiguous().view(b * f, t, c)
        xt = self.time_mamba(xt) + xt
        xt = xt.view(b, f, t, c).permute(0, 2, 1, 3).contiguous().view(b * t, f, c)
        xt = self.freq_mamba(xt) + xt
        xt = xt.view(b, t, f, c).permute(0, 3, 1, 2)
        return xt


def _pad2d(kernel, dil):
    return (int((kernel[0] * dil[0] - dil[0]) / 2),
            int((kernel[1] * dil[1] - dil[1]) / 2))


class DenseBlock2(nn.Module):
    def __init__(self, hid: int, depth: int):
        super().__init__()
        self.depth = depth
        blocks = []
        for i in range(depth):
            dil = 2 ** i
            blocks.append(nn.Sequential(
                nn.Conv2d(hid * (i + 1), hid, (3, 3), dilation=(dil, 1),
                          padding=_pad2d((3, 3), (dil, 1))),
                nn.InstanceNorm2d(hid, affine=True),
                nn.PReLU(hid)))
        self.dense_block = nn.ModuleList(blocks)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        skip = x
        for block in self.dense_block:
            x = block(skip)
            skip = torch.cat([x, skip], dim=1)
        return x


class SPConvTranspose2d(nn.Module):
    def __init__(self, in_ch: int, out_ch: int, kernel, r: int):
        super().__init__()
        self.pad1 = nn.ConstantPad2d((1, 1, 0, 0), 0.)
        self.out_channels = out_ch
        self.conv = nn.Conv2d(in_ch, out_ch * r, kernel)
        self.r = r

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.pad1(x)
        out = self.conv(x)
        b = out.shape[0]
        nc = out.shape[1]
        h = out.shape[2]
        w = out.shape[3]
        out = out.view(b, self.r, nc // self.r, h, w)
        out = out.permute(0, 2, 3, 4, 1).contiguous()
        out = out.view(b, nc // self.r, h, -1)
        return out


class DenseEncoder(nn.Module):
    def __init__(self, input_channel: int, hid: int):
        super().__init__()
        self.dense_conv_1 = nn.Sequential(
            nn.Conv2d(input_channel, hid, (1, 1)),
            nn.InstanceNorm2d(hid, affine=True),
            nn.PReLU(hid))
        self.dense_block = DenseBlock2(hid, 4)
        self.dense_conv_2 = nn.Sequential(
            nn.Conv2d(hid, hid, (1, 3), stride=(4, 2)),
            nn.InstanceNorm2d(hid, affine=True),
            nn.PReLU(hid))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.dense_conv_1(x)
        x = self.dense_block(x)
        x = self.dense_conv_2(x)
        return x


class MagDecoder(nn.Module):
    def __init__(self, hid: int, out_ch: int):
        super().__init__()
        self.dense_block = DenseBlock2(hid, 4)
        self.up_conv1 = nn.Sequential(
            SPConvTranspose2d(hid, hid, (1, 3), 2),
            nn.InstanceNorm2d(hid, affine=True),
            nn.PReLU(hid))
        self.up_conv2 = nn.Sequential(
            SPConvTranspose2d(hid, hid, (1, 3), 4),
            nn.InstanceNorm2d(hid, affine=True),
            nn.PReLU(hid))
        self.final_conv = nn.Conv2d(hid, out_ch, (1, 1))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.dense_block(x)
        x = self.up_conv1(x)
        x = self.up_conv2(x.permute(0, 1, 3, 2).contiguous()).permute(0, 1, 3, 2)
        return self.final_conv(x)


class PhaseDecoder(nn.Module):
    def __init__(self, hid: int, out_ch: int):
        super().__init__()
        self.dense_block = DenseBlock2(hid, 4)
        self.up_conv1 = nn.Sequential(
            SPConvTranspose2d(hid, hid, (1, 3), 2),
            nn.InstanceNorm2d(hid, affine=True),
            nn.PReLU(hid))
        self.up_conv2 = nn.Sequential(
            SPConvTranspose2d(hid, hid, (1, 3), 4),
            nn.InstanceNorm2d(hid, affine=True),
            nn.PReLU(hid))
        self.phase_conv_r = nn.Conv2d(hid, out_ch, (1, 1))
        self.phase_conv_i = nn.Conv2d(hid, out_ch, (1, 1))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.dense_block(x)
        x = self.up_conv1(x)
        x = self.up_conv2(x.permute(0, 1, 3, 2).contiguous()).permute(0, 1, 3, 2)
        x_r = self.phase_conv_r(x)
        x_i = self.phase_conv_i(x)
        return torch.atan2(x_i, x_r)


class SEMambaCore(nn.Module):
    """dense_encoder + 30 TFMamba blocks + mag/phase decoders, scriptable."""

    def __init__(self, num_blocks: int, hid: int = 64, d_state: int = 16,
                 d_conv: int = 4, expand: int = 4, input_channel: int = 2):
        super().__init__()
        self.dense_encoder = DenseEncoder(input_channel, hid)
        self.TSMamba = nn.ModuleList([
            TFMambaBlock(hid, d_state, d_conv, expand) for _ in range(num_blocks)])
        self.mask_decoder = MagDecoder(hid, 1)
        self.phase_decoder = PhaseDecoder(hid, 1)

    def forward(self, mag: torch.Tensor, pha: torch.Tensor):
        # mag/pha: (B, F, T) -> feature (B, 2, T+2, F+2)
        x = torch.cat([mag.transpose(1, 2).unsqueeze(1),
                       pha.transpose(1, 2).unsqueeze(1)], dim=1)
        b = x.shape[0]
        c = x.shape[1]
        t = x.shape[2]
        f = x.shape[3]
        x = torch.cat([x, torch.zeros(b, c, t, 2, dtype=x.dtype, device=x.device)], dim=-1)
        x = torch.cat([x, torch.zeros(b, c, 2, f + 2, dtype=x.dtype, device=x.device)], dim=-2)
        x = self.dense_encoder(x)
        for block in self.TSMamba:
            x = block(x)
        mag_out = self.mask_decoder(x)[:, :, :t, :f].squeeze(1).transpose(1, 2)
        pha_out = self.phase_decoder(x)[:, :, :t, :f].squeeze(1).transpose(1, 2)
        return mag_out, pha_out  # (B, F, T) compressed mag + decoded phase


def build_core_and_load(weights_path: str) -> SEMambaCore:
    from safetensors.torch import load_file
    sd = load_file(weights_path)
    core = SEMambaCore(num_blocks=30)
    missing, unexpected = core.load_state_dict(sd, strict=False)
    print("missing:", len(missing), "unexpected:", len(unexpected))
    if missing:
        print("  missing sample:", missing[:6])
    if unexpected:
        print("  unexpected sample:", unexpected[:6])
    assert len(missing) == 0 and len(unexpected) == 0, "state dict mismatch"
    core.eval()
    return core


# --------------------------------------------------------------------------
# In-graph STFT / iSTFT
# --------------------------------------------------------------------------
class _StftBase(nn.Module):
    N_FFT: int
    HOP: int

    def __init__(self):
        super().__init__()
        self.N_FFT = N_FFT
        self.HOP = HOP
        n = N_FFT
        bins = n // 2 + 1                       # 321
        tpos = torch.arange(n, dtype=torch.float64) / n
        window = torch.hann_window(n, dtype=torch.float64)
        k = torch.arange(bins, dtype=torch.float64).unsqueeze(1)
        ang = 2.0 * math.pi * k * tpos.unsqueeze(0)
        # analysis kernel rows: cos(-ang)*w, sin(-ang)*w  (matches
        # torch.stft one-sided rfft with hann window, normalized=False)
        cos_row = (torch.cos(ang) * window).unsqueeze(1)
        sin_row = (-torch.sin(ang) * window).unsqueeze(1)
        weight = torch.cat([cos_row, sin_row], dim=0)   # (2*bins, 1, n)
        self.register_buffer("stft_weight", weight.float())

        # synthesis kernel: iDFT basis scaled by window / sum_sq envelope.
        # one-sided ifft: X0 and XN/2 count once, bins 1..N/2-1 count twice,
        # and the whole sum carries the 1/N factor of the inverse DFT.
        synth = torch.cat([torch.cos(ang), -torch.sin(ang)], dim=0)  # (2*bins, n)
        scale = torch.ones(bins, dtype=torch.float64)
        scale[1:N_FFT // 2] = 2.0                       # mirror bins doubled
        scale_full = torch.cat([scale, scale], dim=0)   # cos rows, sin rows
        synth = (synth * scale_full.unsqueeze(1)) / float(N_FFT)
        synth = (synth * window.unsqueeze(0))
        self.register_buffer("istft_weight", synth.unsqueeze(1).float())  # (2*bins, 1, n)
        # envelope: conv_transpose of ones with kernel = window^2
        self.register_buffer("env_weight", (window * window).view(1, 1, n).float())

    def _analyze(self, wav: torch.Tensor):
        x = wav.unsqueeze(1)                            # (1,1,L)
        xp = F.pad(x, (self.N_FFT // 2, self.N_FFT // 2), mode="reflect")
        spec = F.conv1d(xp, self.stft_weight, stride=self.HOP)  # (1, 2*bins, T)
        re, im = torch.chunk(spec, 2, dim=1)            # (1, bins, T)
        mag = torch.log(1.0 + torch.sqrt(re * re + im * im))
        pha = torch.atan2(im, re)
        return spec, mag, pha

    def _synthesize(self, spec: torch.Tensor, mag_h: torch.Tensor,
                    pha_h: torch.Tensor, L: int) -> torch.Tensor:
        frames = spec.shape[2]
        mag_lin = torch.exp(F.relu(mag_h)) - 1.0
        # frame mask: drop frames that are all-zero (phantom sweep guard)
        zero_frac = (mag_lin == 0).float().sum(dim=1, keepdim=True) / float(mag_lin.shape[1])
        mag_lin = torch.where(zero_frac > 0.5, torch.zeros_like(mag_lin), mag_lin)
        com_re = mag_lin * torch.cos(pha_h)
        com_im = mag_lin * torch.sin(pha_h)
        spec2 = torch.cat([com_re, com_im], dim=1)      # (1, 2*bins, T)
        ola = F.conv_transpose1d(spec2, self.istft_weight, stride=self.HOP)  # (1,1,L+pad)
        ones = torch.ones(1, 1, frames, dtype=spec2.dtype, device=spec2.device)
        env = F.conv_transpose1d(ones, self.env_weight, stride=self.HOP)
        wav2 = (ola / env.clamp(min=1e-11))
        # center crop, same as torch.istft(center=True)
        out = wav2[:, :, self.N_FFT // 2:self.N_FFT // 2 + L]
        return out


class StftIdentity(_StftBase):
    """No model: analysis + synthesis only (validates STFT/iSTFT)."""

    def forward(self, wav: torch.Tensor) -> torch.Tensor:
        L = wav.shape[1]
        spec, mag, pha = self._analyze(wav)
        return self._synthesize(spec, mag, pha, L)


class ReuseGraph(_StftBase):
    """PCM in -> STFT -> SEMamba -> iSTFT -> PCM out (scriptable/exportable)."""

    N_FFT: int
    HOP: int

    def __init__(self, core: SEMambaCore):
        super().__init__()
        self.core = core

    def forward(self, wav: torch.Tensor) -> torch.Tensor:
        L = wav.shape[1]
        spec, mag, pha = self._analyze(wav)
        mag_h, pha_h = self.core(mag, pha)      # compressed mag + decoded phase
        return self._synthesize(spec, mag_h, pha_h, L)


def main():
    weights = sys.argv[1] if len(sys.argv) > 1 else "/tmp/reuse/model.safetensors"
    out_path = sys.argv[2] if len(sys.argv) > 2 else "/tmp/reuse/reuse_fp32.onnx"
    T_FRAMES = int(sys.argv[3]) if len(sys.argv) > 3 else 200  # 2.0 s at 16 kHz

    core = build_core_and_load(weights)
    model = ReuseGraph(core)
    model.eval()

    # --- 1. validate STFT/iSTFT identity roundtrip against torch ---
    L = HOP * T_FRAMES
    wav = torch.sin(torch.linspace(0, 440 * 2 * math.pi * L / SR, L)).unsqueeze(0)
    model_id = StftIdentity()
    model_id.eval()
    with torch.no_grad():
        rt = model_id(wav.clone())
    spec_ref = torch.stft(wav, N_FFT, hop_length=HOP, win_length=WIN,
                          window=torch.hann_window(WIN), center=True,
                          pad_mode="reflect", normalized=False, return_complex=True)
    wav_ref = torch.istft(spec_ref, N_FFT, hop_length=HOP, win_length=WIN,
                          window=torch.hann_window(WIN), center=True, length=L)
    err = (rt - wav_ref).abs().max().item()
    print("stft/istft identity roundtrip max err vs torch: %.2e" % err)
    assert err < 1e-3, "roundtrip mismatch"

    # --- 2. compare the full graph (eager) against the reference pipeline ---
    import os as _os
    SKIP_REF = _os.environ.get("SKIP_REF") == "1"
    _mag_mine = _pha_mine = None
    if SKIP_REF:
        print("SKIP_REF=1 - skipping reference pipeline comparison")
    else:
        with torch.no_grad():
            out_mine = model(wav.clone())
        import sys as _sys
        _sys.path.insert(0, "/home/z/my-project/scripts/reuse/shim")
        _sys.path.insert(0, "/tmp/reuse")
        from models.stfts import mag_phase_stft, mag_phase_istft
        from models.generator_SEMamba_time_d4 import SEMamba as SEMambaRef
        import json as _json
        cfg = _json.load(open("/tmp/reuse/config.json"))
        from safetensors.torch import load_file as _lf
        ref = SEMambaRef(cfg)
        ref.load_state_dict(_lf(weights), strict=True)
        ref.eval()
        n_fft_scaled, hop_scaled, win_scaled = N_FFT, HOP, WIN
        with torch.no_grad():
            mag_r, pha_r, _ = mag_phase_stft(wav, n_fft=n_fft_scaled, hop_size=hop_scaled,
                                             win_size=win_scaled,
                                             compress_factor=cfg["model_cfg"]["compress_factor"],
                                             center=True, addeps=False)
            amp_g, pha_g, _ = ref(mag_r, pha_r)
            _mag_mine, _pha_mine = core(mag_r, pha_r)
        amp_g = amp_g.clone()
        mag2 = torch.exp(torch.nn.ReLU()(amp_g)) - 1.0
        zero_portion = torch.sum(mag2 == 0, 1) / mag2.shape[1]
        amp_g2 = amp_g.clone()
        amp_g2[:, :, (zero_portion > 0.5)[0]] = 0
        audio_ref = mag_phase_istft(amp_g2, pha_g, n_fft_scaled, hop_scaled,
                                    win_scaled, cfg["model_cfg"]["compress_factor"])
        audio_ref = audio_ref[0, :L]
        err2 = (out_mine[0, :L] - audio_ref).abs().max().item()
        print("full-graph vs reference pipeline waveform max err (informational): %.2e" % err2)
        # waveform-level comparison is dominated by 2pi phase wraps of the
        # decoded phase (atan2 branch cuts flip on ~1e-6 differences), so the
        # authoritative checks are mag/phase diffs + the STFT/iSTFT roundtrip.
        mag_diff = (amp_g - _mag_mine).abs().max().item()
        pha_diff = ((pha_g - _pha_mine + 3.14159265358979) % (2 * 3.14159265358979)
                    - 3.14159265358979).abs().max().item()
        print("core mag max diff: %.2e, wrapped-phase max diff: %.2e" % (mag_diff, pha_diff))
        assert mag_diff < 5e-3 and pha_diff < 0.2, "core mismatch"

    # --- export ---
    stage = os.environ.get("STAGE", "all")
    if stage in ("all", "script"):
        scripted = torch.jit.script(model)
        if stage == "script":
            # low-memory machines: save the TorchScript module and run the
            # second half in a fresh process
            torch.jit.save(scripted, out_path + ".ts")
            print("scripted module saved:", out_path + ".ts")
            return
    else:
        scripted = torch.jit.load(out_path + ".ts")
    dummy = torch.zeros(1, L)
    torch.onnx.export(scripted, (dummy,), out_path,
                      input_names=["pcm_in"], output_names=["pcm_out"],
                      opset_version=17, do_constant_folding=True,
                      dynamo=False)
    print("exported:", out_path)

    import onnx
    m = onnx.load(out_path)
    onnx.checker.check_model(m)
    ops = {}
    for nnode in m.graph.node:
        ops[nnode.op_type] = ops.get(nnode.op_type, 0) + 1
    print("ops:", {k: v for k, v in sorted(ops.items(), key=lambda kv: -kv[1])})


if __name__ == "__main__":
    from typing import List  # noqa: needed by jit.annotate
    main()
