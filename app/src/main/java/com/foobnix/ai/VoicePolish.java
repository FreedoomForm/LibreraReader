package com.foobnix.ai;

/**
 * Audible "voice polish" post-chain for TTS playback, applied whenever the
 * user enables voice enhancement (AppState.ttsVoiceEnhance).
 * <p>
 * A denoiser such as DeepFilterNet3 is nearly transparent on already-clean
 * TTS audio - there is no noise to remove, so the toggle produced no audible
 * difference (user feedback on build 7352). What listeners actually perceive
 * as "better voice quality" is loudness and clarity, so this chain adds them:
 *
 *  1. high-pass 70 Hz      - removes rumble / DC offset of cheap engines
 *  2. presence +4 dB @3kHz - clarity on small phone speakers
 *  3. gentle compressor    - denser, more even voice (2.5:1 above -19 dBFS)
 *  4. loudness normalize   - RMS to -17 dBFS (gain capped at +12 dB)
 *  5. soft limiter         - peak ceiling -1 dBFS, no clipping
 *
 * Pure Java, no Android imports (unit-testable off-device). The chain is
 * length-preserving and deterministic per utterance, so word-highlight
 * timing is unaffected. It always runs when enhancement is on, so the
 * toggle always has an audible effect.
 */
public final class VoicePolish {
    /** loudness target: -17 dBFS RMS */
    public static final double TARGET_RMS = 0.1413;
    /** static gain cap: +12 dB */
    public static final double MAX_GAIN = 3.981;
    /** compressor threshold: -19 dBFS */
    public static final double THRESHOLD = 0.1122;
    /** compressor ratio above threshold */
    public static final double RATIO = 2.5;
    /** limiter knee start */
    public static final double KNEE = 0.7;
    /** limiter ceiling: -1 dBFS */
    public static final double CEILING = 0.891;
    /** presence EQ center and gain */
    public static final double PRESENCE_HZ = 3000.0;
    public static final double PRESENCE_GAIN_DB = 4.0;
    public static final double PRESENCE_Q = 0.8;
    /** high-pass corner */
    public static final double HIGHPASS_HZ = 70.0;

    private VoicePolish() {
    }

    /**
     * Polishes 16-bit mono PCM. Returns a new array of the same length; the
     * input is returned unchanged when it is null/empty or the rate is bad.
     */
    public static short[] apply(short[] pcm, int sampleRate) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0) {
            return pcm;
        }
        int n = pcm.length;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = pcm[i] / 32768.0;
        }
        highPass(x, sampleRate);
        presence(x, sampleRate);
        compress(x, sampleRate);
        normalize(x);
        limit(x);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double v = Math.max(-1.0, Math.min(1.0, x[i]));
            out[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, (int) Math.round(v * 32767.0)));
        }
        return out;
    }

    /** RBJ high-pass biquad at {@link #HIGHPASS_HZ}, Q 0.7071 */
    static void highPass(double[] x, int sr) {
        double w0 = 2 * Math.PI * HIGHPASS_HZ / sr;
        double c = Math.cos(w0), s = Math.sin(w0);
        double a = s / (2 * 0.7071);
        double a0 = 1 + a;
        double b0 = (1 + c) / 2 / a0, b1 = -(1 + c) / a0, b2 = (1 + c) / 2 / a0;
        double a1 = -2 * c / a0, a2 = (1 - a) / a0;
        biquad(x, b0, b1, b2, a1, a2);
    }

    /** RBJ peaking biquad at {@link #PRESENCE_HZ} */
    static void presence(double[] x, int sr) {
        double w0 = 2 * Math.PI * PRESENCE_HZ / sr;
        double c = Math.cos(w0), s = Math.sin(w0);
        double A = Math.pow(10.0, PRESENCE_GAIN_DB / 40.0);
        double a = s / (2 * PRESENCE_Q);
        double a0 = 1 + a / A;
        double b0 = (1 + a * A) / a0, b1 = -2 * c / a0, b2 = (1 - a * A) / a0;
        double a1 = -2 * c / a0, a2 = (1 - a / A) / a0;
        biquad(x, b0, b1, b2, a1, a2);
    }

    private static void biquad(double[] x, double b0, double b1, double b2,
                               double a1, double a2) {
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = x[i];
            double y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x[i] = y;
            x2 = x1;
            x1 = v;
            y2 = y1;
            y1 = y;
        }
    }

    /**
     * One-pole envelope compressor: attack 10 ms, release 150 ms, hard knee,
     * 2.5:1 above {@link #THRESHOLD}. Adds density without audible pumping at
     * speech rates.
     */
    static void compress(double[] x, int sr) {
        double atk = Math.exp(-1.0 / (0.010 * sr));
        double rel = Math.exp(-1.0 / (0.150 * sr));
        double env = 0;
        for (int i = 0; i < x.length; i++) {
            double a = Math.abs(x[i]);
            if (a > env) {
                env = atk * env + (1 - atk) * a;
            } else {
                env = rel * env + (1 - rel) * a;
            }
            double g = 1.0;
            if (env > THRESHOLD) {
                g = THRESHOLD * Math.pow(env / THRESHOLD, 1.0 / RATIO) / env;
            }
            x[i] *= g;
        }
    }

    /** static gain to the loudness target, gain capped at {@link #MAX_GAIN} */
    static void normalize(double[] x) {
        double acc = 0;
        for (int i = 0; i < x.length; i++) {
            acc += x[i] * x[i];
        }
        double rms = Math.sqrt(acc / Math.max(1, x.length));
        if (rms < 1e-4) {
            return; // silence stays silent
        }
        double g = TARGET_RMS / rms;
        if (g > MAX_GAIN) {
            g = MAX_GAIN;
        }
        if (g > 1.0) {
            for (int i = 0; i < x.length; i++) {
                x[i] *= g;
            }
        }
    }

    /** soft limiter: transparent below {@link #KNEE}, ceiling {@link #CEILING} */
    static void limit(double[] x) {
        double k = CEILING - KNEE;
        for (int i = 0; i < x.length; i++) {
            double v = x[i];
            double a = Math.abs(v);
            if (a > KNEE) {
                double y = KNEE + k * Math.tanh((a - KNEE) / k);
                x[i] = Math.signum(v) * y;
            }
        }
    }
}
