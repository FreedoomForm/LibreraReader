package com.foobnix.ai;

/**
 * Pure-Java DSP for the DeepFilterNet3 voice enhancement model (official
 * 48 kHz full-band checkpoint, conv_lookahead=2).
 *
 * The neural stages run through ONNX Runtime (see {@link VoiceEnhancer});
 * this class implements everything around them and mirrors, operation by
 * operation, the validated numpy reference (SNR 71.5 dB vs the official
 * torch implementation):
 *
 *  1. STFT analysis  : vorbis window, hop 480, window 960, spectrum /960
 *  2. features       : ERB band energies (log-dB, EMA mean-norm, /40)
 *                      + complex unit-norm of the first 96 bins (EMA)
 *  3. (ONNX: enc -> erb_dec mask [T,32] + df_dec coefs [T,96,10])
 *  4. post           : bin gain = band mask; deep filter (5 taps, lags -2..+2)
 *                      on the first 96 bins; band-masked spectrum above
 *  5. iSTFT          : UNSCALED inverse FFT (realfft convention), vorbis
 *                      window, overlap-add, drop 480 samples of delay
 *
 * No Android imports: this class is unit-testable off-device.
 */
public final class Dfn3Dsp {
    public static final int SR = 48000;
    public static final int N_FFT = 960;
    public static final int HOP = 480;
    public static final int NB_ERB = 32;
    public static final int NB_DF = 96;
    public static final int FREQ = N_FFT / 2 + 1; // 481
    public static final int ORDER = 5;
    public static final int LOOKAHEAD = 2;
    /** EMA decay: exp(-hop / (sr * tau)), tau = 1 s */
    public static final float ALPHA = (float) Math.exp(-HOP / (double) SR);

    private static volatile double[] windowCache;
    private static volatile int[] erbWidthsCache;
    private static volatile int[] bandOfCache;

    private Dfn3Dsp() {
    }

    /** vorbis window: sin(0.5*pi*sin^2(0.5*pi*(i+0.5)/(N/2))) */
    public static double[] window() {
        double[] w = windowCache;
        if (w == null) {
            w = new double[N_FFT];
            for (int i = 0; i < N_FFT; i++) {
                double s = Math.sin(0.5 * Math.PI * (i + 0.5) / (N_FFT / 2));
                w[i] = Math.sin(0.5 * Math.PI * s * s);
            }
            windowCache = w;
        }
        return w;
    }

    /** libDF erb_fb(sr=48000, fft=960, nb=32, min=2): bin counts per band, sum = 481 */
    public static int[] erbWidths() {
        int[] erb = erbWidthsCache;
        if (erb != null) {
            return erb;
        }
        double freqWidth = (double) SR / N_FFT;
        double lo = freq2erb(0.0), hi = freq2erb(SR / 2.0);
        double step = (hi - lo) / NB_ERB;
        int prevFreq = 0, freqOver = 0;
        erb = new int[NB_ERB];
        for (int i = 1; i <= NB_ERB; i++) {
            double f = erb2freq(lo + i * step);
            int fb = (int) Math.round(f / freqWidth);
            int nb = fb - prevFreq - freqOver;
            if (nb < 2) {
                freqOver = 2 - nb;
                nb = 2;
            } else {
                freqOver = 0;
            }
            erb[i - 1] = nb;
            prevFreq = fb;
        }
        erb[NB_ERB - 1] += 1;
        int total = 0;
        for (int v : erb) {
            total += v;
        }
        erb[NB_ERB - 1] -= total - FREQ;
        erbWidthsCache = erb;
        return erb;
    }

    private static double freq2erb(double f) {
        return 9.265 * Math.log1p(f / (24.7 * 9.265));
    }

    private static double erb2freq(double e) {
        return 24.7 * 9.265 * (Math.exp(e / 9.265) - 1.0);
    }

    /** bin -> erb band index */
    public static int[] bandOf() {
        int[] bandOf = bandOfCache;
        if (bandOf != null) {
            return bandOf;
        }
        int[] w = erbWidths();
        bandOf = new int[FREQ];
        int b = 0, acc = 0;
        for (int bin = 0; bin < FREQ; bin++) {
            while (b < NB_ERB - 1 && bin >= acc + w[b]) {
                acc += w[b];
                b++;
            }
            bandOf[bin] = b;
        }
        bandOfCache = bandOf;
        return bandOf;
    }

    /** number of STFT frames for n input samples (mirrors libDF streaming) */
    public static int frameCount(int n) {
        return (int) Math.ceil((n + N_FFT) / (double) HOP);
    }

    // ------------------------------------------------------------------ STFT

    /** complex spectrum of one utterance: re[t] / im[t] are double[FREQ] */
    public static final class Spec {
        public final double[][] re;
        public final double[][] im;
        public final int frames;

        Spec(int frames) {
            this.frames = frames;
            this.re = new double[frames][FREQ];
            this.im = new double[frames][FREQ];
        }
    }

    /** features fed to the encoder */
    public static final class Feat {
        public final float[][] erb;    // [T][NB_ERB]
        public final float[][] specRe; // [T][NB_DF]
        public final float[][] specIm; // [T][NB_DF]

        Feat(int frames) {
            erb = new float[frames][NB_ERB];
            specRe = new float[frames][NB_DF];
            specIm = new float[frames][NB_DF];
        }
    }

    /** step 1: STFT analysis (x padded internally, libDF semantics) */
    public static Spec analyze(float[] x, int n) {
        int T = frameCount(n);
        double[] w = window();
        Spec spec = new Spec(T);
        double[] buf = new double[N_FFT];
        for (int t = 0; t < T; t++) {
            int off = t * HOP;
            for (int i = 0; i < N_FFT; i++) {
                int j = off + i - HOP; // 480 leading zeros (empty analysis memory)
                double v = (j >= 0 && j < n) ? x[j] : 0.0;
                buf[i] = v * w[i];
            }
            rfft960(buf, spec.re[t], spec.im[t]);
            for (int f = 0; f < FREQ; f++) {
                spec.re[t][f] /= N_FFT;
                spec.im[t][f] /= N_FFT;
            }
        }
        return spec;
    }

    /** step 2: ERB + complex unit-norm features with EMA states */
    public static Feat features(Spec spec) {
        int T = spec.frames;
        int[] widths = erbWidths();
        Feat feat = new Feat(T);
        double[] meanState = new double[NB_ERB];
        for (int b = 0; b < NB_ERB; b++) {
            meanState[b] = -60.0 + b * (-30.0 / (NB_ERB - 1));
        }
        double[] unitState = new double[NB_DF];
        for (int f = 0; f < NB_DF; f++) {
            unitState[f] = 0.001 + f * (-0.0009 / (NB_DF - 1));
        }
        for (int t = 0; t < T; t++) {
            // band energies: mean of |X|^2 per band, log10(+1e-10)*10
            int bin = 0;
            for (int b = 0; b < NB_ERB; b++) {
                double acc = 0;
                for (int k = 0; k < widths[b]; k++, bin++) {
                    double re = spec.re[t][bin], im = spec.im[t][bin];
                    acc += re * re + im * im;
                }
                acc /= widths[b];
                double v = 10.0 * Math.log10(acc + 1e-10);
                double s = v * (1.0 - ALPHA) + meanState[b] * ALPHA;
                meanState[b] = s;
                feat.erb[t][b] = (float) ((v - s) / 40.0);
            }
            // complex unit norm on the first NB_DF bins
            for (int f = 0; f < NB_DF; f++) {
                double re = spec.re[t][f], im = spec.im[t][f];
                double u = Math.sqrt(re * re + im * im) * (1.0 - ALPHA) + unitState[f] * ALPHA;
                unitState[f] = u;
                double scale = 1.0 / Math.sqrt(u);
                feat.specRe[t][f] = (float) (re * scale);
                feat.specIm[t][f] = (float) (im * scale);
            }
        }
        return feat;
    }

    /**
     * step 4: combine model outputs into the enhanced spectrum.
     *
     * @param mask  flat [T*32] (ONNX erb_dec output [1,1,T,32])
     * @param coefs flat [T*96*10] (ONNX df_dec output [1,T,96,10]),
     *              index ((t*96+f)*ORDER+n)*2 + (0=re, 1=im)
     */
    public static void post(Spec spec, float[] mask, float[] coefs, Spec out) {
        int T = spec.frames;
        int[] bandOf = bandOf();
        for (int t = 0; t < T; t++) {
            // bins >= NB_DF: spectrum * band gain
            for (int f = NB_DF; f < FREQ; f++) {
                double g = mask[t * NB_ERB + bandOf[f]];
                out.re[t][f] = spec.re[t][f] * g;
                out.im[t][f] = spec.im[t][f] * g;
            }
            // bins < NB_DF: deep filter, y[t] = sum_{lag=-2..+2} c[lag+2,t] * x[t+lag]
            for (int f = 0; f < NB_DF; f++) {
                double accRe = 0, accIm = 0;
                for (int nn = 0; nn < ORDER; nn++) {
                    int tt = t + nn - LOOKAHEAD;
                    if (tt < 0 || tt >= T) {
                        continue;
                    }
                    double cr = coefs[(t * NB_DF + f) * ORDER * 2 + nn * 2];
                    double ci = coefs[(t * NB_DF + f) * ORDER * 2 + nn * 2 + 1];
                    double xr = spec.re[tt][f], xi = spec.im[tt][f];
                    accRe += cr * xr - ci * xi;
                    accIm += cr * xi + ci * xr;
                }
                out.re[t][f] = accRe;
                out.im[t][f] = accIm;
            }
        }
    }

    /** step 5: iSTFT synthesis (unscaled inverse FFT, WOLA, delay slice) */
    public static float[] synthesize(Spec spec, int n) {
        int T = spec.frames;
        double[] w = window();
        double[] out = new double[(T - 1) * HOP + N_FFT];
        double[] re = new double[FREQ], im = new double[FREQ], frame = new double[N_FFT];
        for (int t = 0; t < T; t++) {
            System.arraycopy(spec.re[t], 0, re, 0, FREQ);
            System.arraycopy(spec.im[t], 0, im, 0, FREQ);
            irfft960Unscaled(re, im, frame);
            int off = t * HOP;
            for (int i = 0; i < N_FFT; i++) {
                out[off + i] += frame[i] * w[i];
            }
        }
        float[] res = new float[n];
        for (int i = 0; i < n; i++) {
            res[i] = (float) out[HOP + i];
        }
        return res;
    }

    // -------------------------------------------------------- FFT internals

    /**
     * Real FFT of 960 points (unscaled, = numpy.fft.rfft convention).
     * Bluestein chirp-z via a radix-2 FFT of length 2048.
     */
    static void rfft960(double[] frame, double[] outRe, double[] outIm) {
        int N = N_FFT, L = 2048;
        double[] bRe = new double[L], bIm = new double[L];
        double[] cRe = new double[L], cIm = new double[L];
        for (int n = 0; n < N; n++) {
            double ang = -Math.PI * (double) n * n / N;
            double c = Math.cos(ang), s = Math.sin(ang);
            bRe[n] = frame[n] * c;
            bIm[n] = frame[n] * s;
            // c[d] = exp(+i*pi*d^2/N) for d = 0..N-1, folded to L-d for negative d
            cRe[n] = c;
            cIm[n] = -s;
            if (n > 0) {
                cRe[L - n] = c;
                cIm[L - n] = -s;
            }
        }
        fftRadix2(bRe, bIm, false);
        fftRadix2(cRe, cIm, false);
        for (int i = 0; i < L; i++) {
            double tr = bRe[i] * cRe[i] - bIm[i] * cIm[i];
            double ti = bRe[i] * cIm[i] + bIm[i] * cRe[i];
            bRe[i] = tr;
            bIm[i] = ti;
        }
        fftRadix2(bRe, bIm, true); // includes 1/L
        for (int k = 0; k < FREQ; k++) {
            double ang = -Math.PI * (double) k * k / N;
            double c = Math.cos(ang), s = Math.sin(ang);
            outRe[k] = bRe[k] * c - bIm[k] * s;
            outIm[k] = bRe[k] * s + bIm[k] * c;
        }
    }

    /**
     * Complex 960-point DFT (unscaled, numpy convention) via Bluestein
     * chirp-z with a radix-2 FFT of length 2048 (L >= 2N-1).
     */
    static void cfft960Bluestein(double[] inRe, double[] inIm, double[] outRe, double[] outIm) {
        int N = N_FFT, L = 2048;
        double[] bRe = new double[L], bIm = new double[L];
        double[] cRe = new double[L], cIm = new double[L];
        for (int n = 0; n < N; n++) {
            double ang = -Math.PI * (double) n * n / N;
            double c = Math.cos(ang), s = Math.sin(ang);
            bRe[n] = inRe[n] * c - inIm[n] * s;
            bIm[n] = inRe[n] * s + inIm[n] * c;
            // c[d] = exp(+i*pi*d^2/N) for d = 0..N-1, folded to L-d for negative d
            cRe[n] = c;
            cIm[n] = -s;
            if (n > 0) {
                cRe[L - n] = c;
                cIm[L - n] = -s;
            }
        }
        fftRadix2(bRe, bIm, false);
        fftRadix2(cRe, cIm, false);
        for (int i = 0; i < L; i++) {
            double tr = bRe[i] * cRe[i] - bIm[i] * cIm[i];
            double ti = bRe[i] * cIm[i] + bIm[i] * cRe[i];
            bRe[i] = tr;
            bIm[i] = ti;
        }
        fftRadix2(bRe, bIm, true); // includes 1/L
        for (int k = 0; k < N; k++) {
            double ang = -Math.PI * (double) k * k / N;
            double c = Math.cos(ang), s = Math.sin(ang);
            outRe[k] = bRe[k] * c - bIm[k] * s;
            outIm[k] = bRe[k] * s + bIm[k] * c;
        }
    }

    /**
     * Inverse real FFT of 960 points, UNSCALED (matches the realfft crate
     * used by libDF: no 1/N factor). Input: FREQ hermitian-symmetric bins.
     */
    static void irfft960Unscaled(double[] inRe, double[] inIm, double[] outReal) {
        double[] zRe = new double[N_FFT], zIm = new double[N_FFT];
        double[] yRe = new double[N_FFT], yIm = new double[N_FFT];
        // full hermitian spectrum, conj() for the IDFT-via-forward-FFT trick:
        // IDFT_unscaled(X) = conj(FFT(conj(X))); hermitian X => real result
        zRe[0] = inRe[0];
        zIm[0] = -inIm[0];
        for (int k = 1; k < FREQ - 1; k++) {
            zRe[k] = inRe[k];
            zIm[k] = -inIm[k];
            zRe[N_FFT - k] = inRe[k];
            zIm[N_FFT - k] = inIm[k];
        }
        zRe[FREQ - 1] = inRe[FREQ - 1];
        zIm[FREQ - 1] = -inIm[FREQ - 1];
        cfft960Bluestein(zRe, zIm, yRe, yIm);
        for (int n = 0; n < N_FFT; n++) {
            outReal[n] = yRe[n];
        }
    }

    /** iterative radix-2 FFT; forward unscaled, inverse scaled by 1/n */
    static void fftRadix2(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                double ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }
}
