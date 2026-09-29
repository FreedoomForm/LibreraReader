"""Functional smoke test: run the SHIPPED Inflect-Nano-v2 assets end-to-end.

Mirrors the app's Java pipeline (InflectFrontend + KokoroEngine): lexicon
lookup + punctuation assembly, blank-interleaved tokens, duration.onnx ->
decode.onnx. Writes a WAV for check_wav.py.
"""
import os
import sys
import wave

import numpy as np
import onnxruntime as ort

model_dir, out_path = sys.argv[1], sys.argv[2]

TEXT = ("Hello from the offline voice engine. This is a real synthesis test. "
        "The reader must survive a long paragraph with several sentences, "
        "because decode memory grows with the length of the text. If this "
        "paragraph sounds fine, a whole page of a book will sound fine too.")

# ---- symbols (must match InflectFrontend.SYMBOLS / runtime/text/symbols.py)
import re
SYMBOLS = (
    "_;:,.!?\u00a1\u00bf\u2014\u2026\"\u00ab\u00bb\u201c\u201d ABCDEF"
    "GHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz\u0251\u0250\u0252"
    "\u00e6\u0253\u0299\u03b2\u0254\u0255\u00e7\u0257\u0256\u00f0"
    "\u02a4\u0259\u0258\u025a\u025b\u025c\u025d\u025e\u025f\u0284"
    "\u0261\u0260\u0262\u029b\u0266\u0267\u0127\u0265\u029c\u0268"
    "\u026a\u029d\u026d\u026c\u026b\u026e\u029f\u0271\u026f\u0270"
    "\u014b\u0273\u0272\u0274\u00f8\u0275\u0278\u03b8\u0153\u0276"
    "\u0298\u0279\u027a\u027e\u027b\u0280\u0281\u027d\u0282\u0283"
    "\u0288\u02a7\u0289\u028a\u028b\u2c71\u028c\u0263\u0264\u028d"
    "\u03c7\u028e\u028f\u0291\u0290\u0292\u0294\u02a1\u0295\u02a2"
    "\u01c0\u01c1\u01c2\u01c3\u02c8\u02cc\u02d0\u02d1\u02bc\u02b4"
    "\u02b0\u02b1\u02b2\u02b7\u02e0\u02e4\u02de\u2193\u2191\u2192"
    "\u2197\u2198'\u0329'\u1d7b"
)
SYM = {}
for i, ch in enumerate(SYMBOLS):
    SYM[ch] = i  # last occurrence wins, like the python dict

# ---- lexicon
lex = {}
with open(os.path.join(model_dir, 'lexicon-en.txt'), encoding='utf-8') as f:
    for line in f:
        w, _, p = line.rstrip('\n').partition('\t')
        if w:
            lex[w] = p
assert len(lex) >= 100000, 'lexicon too small: %d' % len(lex)

LETTERS = ['e\u026a', 'bi\u02d0', 'si\u02d0', 'di\u02d0', 'i\u02d0', '\u025bf',
           'd\u0292i\u02d0', 'e\u026at\u0283', 'a\u026a', 'd\u0292e\u026a', 'ke\u026a',
           '\u025bl', '\u025bm', '\u025bn', 'o\u028a', 'pi\u02d0', 'kju\u02d0',
           '\u0251\u02d0\u0279', '\u025bs', 'ti\u02d0', 'ju\u02d0', 'vi\u02d0',
           'd\u028cblj u\u02d0', '\u025bks', 'wa\u026a', 'zi\u02d0']


def lookup(word):
    p = lex.get(word)
    if p is not None:
        return p
    for suf in ('s', 'es', 'ed', 'ing', 'ly'):
        if word.endswith(suf) and len(word) > len(suf) + 2:
            base = word[:-len(suf)]
            p = lex.get(base)
            if p is not None:
                return p
            if base.endswith(base[-2]) and len(base) > 2 and base[-1] in 'bcdfgklmnprstvz':
                p = lex.get(base[:-1])
                if p is not None:
                    return p
            if suf in ('ed', 'ing'):
                p = lex.get(base + 'y')
                if p is not None:
                    return p
    return ' '.join(LETTERS[ord(c) - ord('a')] for c in word if 'a' <= c <= 'z') or None


def phonemize(text):
    out = []
    for raw in text.lower().split():
        word = []
        for ch in raw:
            if ch in '.,;:!?':
                if word:
                    out.append(('w', ''.join(word)))
                    word = []
                out.append(('p', ch))
            elif ch in '-\u2013':
                if word:
                    out.append(('w', ''.join(word)))
                    word = []
            elif ch in "'\u2019":
                continue
            else:
                word.append(ch)
        if word:
            out.append(('w', ''.join(word)))
    s = []
    for kind, val in out:
        if kind == 'w':
            p = lookup(val)
            if not p:
                continue
            if s:
                s.append(' ')
            s.append(p)
        else:
            s.append(val)
    phon = ''.join(s).strip()
    assert phon, 'empty phoneme string'
    return phon


def to_tokens(phon):
    seq = []
    for ch in phon:
        i = SYM.get(ch)
        assert i is not None, 'symbol not in table: %r' % ch
        seq.append(i)
    with_blanks = np.zeros(len(seq) * 2 + 1, dtype=np.int64)
    with_blanks[1::2] = seq
    return with_blanks[None, :]


phones = phonemize(TEXT)
tokens = to_tokens(phones)
print('phonemes:', phones[:60], '... tokens:', tokens.shape)

dur = ort.InferenceSession(os.path.join(model_dir, 'duration.onnx'), providers=['CPUExecutionProvider'])
dec = ort.InferenceSession(os.path.join(model_dir, 'decode.onnx'), providers=['CPUExecutionProvider'])

m, logs, mask = dur.run(['m_p_exp', 'logs_p_exp', 'y_mask'], {
    'tokens': tokens,
    'lengths': np.asarray([tokens.shape[1]], dtype=np.int64),
    'length_scale': np.asarray(1.0, dtype=np.float32),
})
rng = np.random.default_rng(1000)
noise = rng.standard_normal(m.shape, dtype=np.float32)
wav = dec.run(['waveform'], {
    'm_p_exp': m, 'logs_p_exp': logs, 'y_mask': mask,
    'zp_noise': noise, 'noise_scale': np.asarray(0.667, dtype=np.float32),
})[0].reshape(-1)
wav = np.clip(wav, -1, 1)
assert np.abs(wav).max() > 0.01, 'synthesis produced near-silence'

w = wave.open(out_path, 'wb')
w.setnchannels(1)
w.setsampwidth(2)
w.setframerate(24000)
w.writeframes((wav * 32767).astype(np.int16).tobytes())
w.close()
print('generated %d samples @ 24000 Hz, peak %.3f' % (len(wav), np.abs(wav).max()))
print('PYTHON TTS TEST PASSED')
