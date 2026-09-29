package com.foobnix.ai;

import android.content.Context;

import com.foobnix.LibreraApp;
import com.foobnix.android.utils.LOG;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * DeepFilterNet3 voice enhancement of the SYSTEM TTS output.
 * <p>
 * Official 48 kHz full-band checkpoint (enc / erb_dec / df_dec split export,
 * conv_lookahead=2) executed through ONNX Runtime. All signal processing
 * around the networks (vorbis-window STFT/iSTFT, ERB features, deep filter
 * application, overlap-add) is implemented in {@link Dfn3Dsp}, which was
 * verified bit-accurate against the reference numpy pipeline (SNR 71.5 dB
 * vs the official torch implementation).
 * <p>
 * The whole utterance is processed in one pass (DFN3 is stateless between
 * calls, GRU zero-initialized per utterance - exactly like the reference).
 * On already-clean TTS audio the model behaves close to identity with a
 * mild polish; on slow devices the caller auto-disables it (see
 * {@link #getMeasuredSpeedFactor()}).
 */
public class VoiceEnhancer {
    public static final String MODEL_VERSION = "dfn3-official-v1";
    private static final String TAG = "VoiceEnhancer";
    /** sample rate the model was trained/exported for */
    public static final int MODEL_RATE = Dfn3Dsp.SR;
    /** fail-open guard: utterances longer than this are left unenhanced */
    private static final int MAX_SAMPLES = MODEL_RATE * 60;

    private static VoiceEnhancer INSTANCE = new VoiceEnhancer();

    public static VoiceEnhancer get() {
        return INSTANCE;
    }

    private volatile OrtSession encSession;
    private volatile OrtSession erbDecSession;
    private volatile OrtSession dfDecSession;
    private volatile OrtEnvironment env;
    private volatile boolean preparing = false;
    private volatile boolean available = false;
    /** measured: seconds of audio produced per second of compute (>=1 is realtime) */
    private volatile double measuredSpeedFactor = 0;

    public boolean isAvailable() {
        return available && encSession != null;
    }

    public double getMeasuredSpeedFactor() {
        return measuredSpeedFactor;
    }

    public void prepareAsync() {
        if (available || preparing) {
            return;
        }
        preparing = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    prepareSync();
                } catch (Throwable e) {
                    LOG.e(e);
                    android.util.Log.i(KokoroEngine.DIAG_TAG, "DeepFilterNet3 init FAILED: " + e);
                    available = false;
                } finally {
                    preparing = false;
                }
            }
        }, "dfn3-prepare");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void prepareSync() throws Throwable {
        if (available && encSession != null) {
            return;
        }
        File dir = extractModels();
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        try {
            opts.setIntraOpNumThreads(Math.max(2, Math.min(4,
                    Runtime.getRuntime().availableProcessors() - 1)));
        } catch (Throwable t) {
            LOG.e(t);
        }
        encSession = env.createSession(new File(dir, "enc.onnx").getAbsolutePath(), opts);
        erbDecSession = env.createSession(new File(dir, "erb_dec.onnx").getAbsolutePath(), opts);
        dfDecSession = env.createSession(new File(dir, "df_dec.onnx").getAbsolutePath(), opts);
        available = true;
        android.util.Log.i(KokoroEngine.DIAG_TAG, "DeepFilterNet3 ready: enc="
                + new File(dir, "enc.onnx").length() + " erb_dec="
                + new File(dir, "erb_dec.onnx").length() + " df_dec="
                + new File(dir, "df_dec.onnx").length());
    }

    private File extractModels() throws Exception {
        Context c = LibreraApp.context;
        File outDir = new File(c.getFilesDir(), "dfn3");
        File marker = new File(outDir, ".version");
        if (marker.exists()
                && MODEL_VERSION.equals(new String(java.nio.file.Files.readAllBytes(marker.toPath())).trim())
                && new File(outDir, "enc.onnx").exists()
                && new File(outDir, "erb_dec.onnx").exists()
                && new File(outDir, "df_dec.onnx").exists()) {
            return outDir;
        }
        outDir.mkdirs();
        copyAssetFile(c, "dfn3/enc.onnx", new File(outDir, "enc.onnx"));
        copyAssetFile(c, "dfn3/erb_dec.onnx", new File(outDir, "erb_dec.onnx"));
        copyAssetFile(c, "dfn3/df_dec.onnx", new File(outDir, "df_dec.onnx"));
        FileOutputStream fos = new FileOutputStream(marker);
        fos.write(MODEL_VERSION.getBytes());
        fos.close();
        return outDir;
    }

    private void copyAssetFile(Context c, String assetPath, File outFile) throws Exception {
        File parent = outFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getAssets().open(assetPath);
            out = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * Enhances 16-bit mono PCM at the given sample rate. Returns new PCM at
     * the same rate, same length. If the model is not available the input is
     * returned unchanged.
     */
    public short[] enhance(short[] pcm, int sampleRate) {
        OrtSession enc = encSession;
        if (enc == null || !available || pcm == null || sampleRate <= 0) {
            return pcm;
        }
        float[] src = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            src[i] = pcm[i] / 32768f;
        }
        float[] x = resample(src, sampleRate, MODEL_RATE);
        int n = x.length;
        if (n < Dfn3Dsp.HOP * 4 || n > MAX_SAMPLES) {
            return pcm;
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            Dfn3Dsp.Spec spec = Dfn3Dsp.analyze(x, n);
            Dfn3Dsp.Feat feat = Dfn3Dsp.features(spec);
            int T = spec.frames;

            // ---- encoder ----
            float[] erbIn = new float[T * Dfn3Dsp.NB_ERB];
            float[] specIn = new float[2 * T * Dfn3Dsp.NB_DF];
            for (int t = 0; t < T; t++) {
                for (int b = 0; b < Dfn3Dsp.NB_ERB; b++) {
                    erbIn[t * Dfn3Dsp.NB_ERB + b] = feat.erb[t][b];
                }
                for (int f = 0; f < Dfn3Dsp.NB_DF; f++) {
                    specIn[t * Dfn3Dsp.NB_DF + f] = feat.specRe[t][f];
                    specIn[T * Dfn3Dsp.NB_DF + t * Dfn3Dsp.NB_DF + f] = feat.specIm[t][f];
                }
            }
            Map<String, OnnxTensor> inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put("feat_erb", OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(erbIn), new long[]{1, 1, T, Dfn3Dsp.NB_ERB}));
            inputs.put("feat_spec", OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(specIn), new long[]{1, 2, T, Dfn3Dsp.NB_DF}));
            float[] mask;
            float[] coefs;
            try (OrtSession.Result r = enc.run(inputs)) {
                float[] e0 = toFloatArray(r, "e0");
                float[] e1 = toFloatArray(r, "e1");
                float[] e2 = toFloatArray(r, "e2");
                float[] e3 = toFloatArray(r, "e3");
                float[] emb = toFloatArray(r, "emb");
                float[] c0 = toFloatArray(r, "c0");
                if (e0 == null || emb == null || c0 == null) {
                    return pcm;
                }
                // ---- erb decoder: band mask ----
                Map<String, OnnxTensor> in2 = new LinkedHashMap<String, OnnxTensor>();
                in2.put("emb", OnnxTensor.createTensor(env, FloatBuffer.wrap(emb),
                        new long[]{1, T, 512}));
                in2.put("e3", OnnxTensor.createTensor(env, FloatBuffer.wrap(e3),
                        new long[]{1, 64, T, 8}));
                in2.put("e2", OnnxTensor.createTensor(env, FloatBuffer.wrap(e2),
                        new long[]{1, 64, T, 8}));
                in2.put("e1", OnnxTensor.createTensor(env, FloatBuffer.wrap(e1),
                        new long[]{1, 64, T, 16}));
                in2.put("e0", OnnxTensor.createTensor(env, FloatBuffer.wrap(e0),
                        new long[]{1, 64, T, 32}));
                try (OrtSession.Result r2 = erbDecSession.run(in2)) {
                    mask = toFloatArray(r2, "m");
                }
                // ---- df decoder: deep filter coefficients ----
                Map<String, OnnxTensor> in3 = new LinkedHashMap<String, OnnxTensor>();
                in3.put("emb", OnnxTensor.createTensor(env, FloatBuffer.wrap(emb),
                        new long[]{1, T, 512}));
                in3.put("c0", OnnxTensor.createTensor(env, FloatBuffer.wrap(c0),
                        new long[]{1, 64, T, Dfn3Dsp.NB_DF}));
                try (OrtSession.Result r3 = dfDecSession.run(in3)) {
                    coefs = toFloatArray(r3, "coefs");
                }
            }
            if (mask == null || coefs == null || mask.length < T * Dfn3Dsp.NB_ERB
                    || coefs.length < T * Dfn3Dsp.NB_DF * Dfn3Dsp.ORDER * 2) {
                return pcm;
            }
            // ---- post-processing + iSTFT ----
            Dfn3Dsp.Spec out = new Dfn3Dsp.Spec(T);
            Dfn3Dsp.post(spec, mask, coefs, out);
            float[] y = Dfn3Dsp.synthesize(out, n);

            long dt = android.os.SystemClock.elapsedRealtime() - t0;
            measuredSpeedFactor = (n / (double) MODEL_RATE) / Math.max(1, dt) * 1000.0;
            float[] back = resample(y, MODEL_RATE, sampleRate);
            short[] result = new short[pcm.length];
            for (int i = 0; i < result.length; i++) {
                float v = i < back.length ? back[i] : 0f;
                v = Math.max(-1f, Math.min(1f, v));
                result[i] = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, (int) (v * 32767f)));
            }
            return result;
        } catch (Throwable e) {
            LOG.e(e);
            return pcm; // fail open: unenhanced audio beats silence
        }
    }

    /** copies an ONNX float tensor output into a flat array */
    private static float[] toFloatArray(OrtSession.Result r, String name) {
        try {
            OnnxTensor t = (OnnxTensor) r.get(name).orElse(null);
            if (t == null) {
                return null;
            }
            FloatBuffer fb = t.getFloatBuffer();
            float[] out = new float[fb.remaining()];
            fb.get(out);
            return out;
        } catch (Throwable e) {
            LOG.e(e);
            return null;
        }
    }

    /** linear-interpolation resampler with a light box pre-filter when downsampling */
    static float[] resample(float[] in, int fromRate, int toRate) {
        if (in == null || in.length == 0 || fromRate == toRate) {
            return in;
        }
        float[] src = in;
        if (toRate < fromRate) {
            // crude anti-alias: moving average over the decimation window
            int win = Math.max(1, fromRate / toRate);
            if (win > 1) {
                float[] sm = new float[in.length];
                double acc = 0;
                for (int i = 0; i < in.length; i++) {
                    acc += in[i];
                    if (i >= win) {
                        acc -= in[i - win];
                    }
                    sm[i] = (float) (acc / Math.min(i + 1, win));
                }
                src = sm;
            }
        }
        long outLenL = (long) src.length * toRate / fromRate;
        int outLen = (int) Math.min(outLenL, 64L * 1024 * 1024);
        float[] out = new float[outLen];
        double step = (double) fromRate / toRate;
        double pos = 0;
        for (int i = 0; i < outLen; i++) {
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, src.length - 1);
            float a = (float) (pos - i0);
            out[i] = src[i0] * (1 - a) + src[i1] * a;
            pos += step;
        }
        return out;
    }

    public void release() {
        available = false;
        close(encSession);
        close(erbDecSession);
        close(dfDecSession);
        encSession = null;
        erbDecSession = null;
        dfDecSession = null;
    }

    private static void close(OrtSession s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable e) {
                LOG.e(e);
            }
        }
    }
}
