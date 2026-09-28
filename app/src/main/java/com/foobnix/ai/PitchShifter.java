package com.foobnix.ai;

/**
 * Real-time pitch shifter for the custom voice filter: a streaming linear
 * resampler that reads through the incoming PCM faster (higher pitch) or
 * slower (lower pitch). The engine compensates the tempo with the Kokoro
 * "speed" parameter, so the reading pace stays natural while the voice
 * pitch moves towards the recorded voice sample.
 *
 * The resampler is stateful and chunk-agnostic: sherpa-onnx delivers
 * generated audio in arbitrary blocks, and the fractional position carries
 * over between blocks, so no boundary artifacts appear between them.
 */
public final class PitchShifter {
    private final double factor;
    /** fractional position of the next output sample between last and cur */
    private double pos = 0.0;
    private float last = 0f;
    private boolean hasLast = false;

    public PitchShifter(final float factor) {
        this.factor = VoiceProfile.clampFactor(factor > 0 ? factor : 1f);
    }

    /**
     * Feeds the next input block, returns the resampled audio (may be empty).
     */
    public float[] process(final float[] in, final int len) {
        if (len <= 0) {
            return new float[0];
        }
        if (factor == 1.0) {
            final float[] out = new float[len];
            System.arraycopy(in, 0, out, 0, len);
            return out;
        }
        final float[] out = new float[(int) (len / factor) + 4];
        int n = 0;
        for (int i = 0; i < len; i++) {
            final float cur = in[i];
            if (!hasLast) {
                last = cur;
                hasLast = true;
                continue;
            }
            while (pos < 1.0) {
                out[n++] = last + (cur - last) * (float) pos;
                pos += factor;
            }
            pos -= 1.0;
            last = cur;
        }
        if (n == 0) {
            return new float[0];
        }
        final float[] res = new float[n];
        System.arraycopy(out, 0, res, 0, n);
        return res;
    }

    /** one-shot helper for offline exports */
    public static float[] shift(final float[] in, final float factor) {
        if (in == null || in.length == 0 || factor == 1f) {
            return in;
        }
        return new PitchShifter(factor).process(in, in.length);
    }
}
