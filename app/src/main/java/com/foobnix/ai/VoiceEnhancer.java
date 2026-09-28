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
 * NVIDIA RE-USE (SEMamba) voice enhancement of the SYSTEM TTS output.
 * The model runs fully offline through ONNX Runtime: PCM in (16 kHz),
 * enhanced PCM out. Processing is chunked (2 s windows) so an utterance of
 * any length can be filtered; chunk joins are cross-faded.
 * <p>
 * The model was trained for universal speech enhancement (noise, reverb,
 * bandwidth, codec artifacts). On already-clean TTS audio it behaves close to
 * identity with a mild polish - that is the "voice filter" the user asked
 * for, and on slow devices the caller auto-disables it (see
 * {@link #getMeasuredSpeedFactor()}).
 */
public class VoiceEnhancer {
    public static final String MODEL_VERSION = "reuse-int8-v1";
    private static final String TAG = "VoiceEnhancer";
    /** sample rate the ONNX graph was exported for */
    public static final int MODEL_RATE = 16000;
    /** 2 s chunk = 32000 samples (matches the exported fixed input length) */
    private static final int CHUNK = 32000;
    /** crossfade length at chunk joins, samples */
    private static final int FADE = 640;

    private static VoiceEnhancer INSTANCE = new VoiceEnhancer();

    public static VoiceEnhancer get() {
        return INSTANCE;
    }

    private volatile OrtSession session;
    private volatile OrtEnvironment env;
    private volatile boolean preparing = false;
    private volatile boolean available = false;
    /** measured: seconds of audio produced per second of compute (>=1 is realtime) */
    private volatile double measuredSpeedFactor = 0;

    public boolean isAvailable() {
        return available && session != null;
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
                    android.util.Log.i(KokoroEngine.DIAG_TAG, "RE-USE init FAILED: " + e);
                    available = false;
                } finally {
                    preparing = false;
                }
            }
        }, "reuse-prepare");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void prepareSync() throws Throwable {
        if (available && session != null) {
            return;
        }
        File model = extractModel();
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        try {
            opts.setIntraOpNumThreads(Math.max(2, Math.min(4,
                    Runtime.getRuntime().availableProcessors() - 1)));
        } catch (Throwable t) {
            LOG.e(t);
        }
        session = env.createSession(model.getAbsolutePath(), opts);
        available = true;
        android.util.Log.i(KokoroEngine.DIAG_TAG, "RE-USE ready: "
                + model.length() + " bytes, inputs=" + session.getInputNames());
    }

    private File extractModel() throws Exception {
        Context c = LibreraApp.context;
        File outDir = new File(c.getFilesDir(), "reuse");
        File marker = new File(outDir, ".version");
        File model = new File(outDir, "model.int8.onnx");
        if (marker.exists() && model.exists()
                && MODEL_VERSION.equals(new String(java.nio.file.Files.readAllBytes(marker.toPath())).trim())) {
            return model;
        }
        outDir.mkdirs();
        copyAssetFile(c, "reuse/model.int8.onnx", model);
        FileOutputStream fos = new FileOutputStream(marker);
        fos.write(MODEL_VERSION.getBytes());
        fos.close();
        return model;
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
        OrtSession s = session;
        if (s == null || !available || pcm == null || pcm.length < CHUNK / 8) {
            return pcm;
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        // to the model rate
        float[] x = resample(pcm, sampleRate, MODEL_RATE);
        int n = x.length;
        int padded = ((n + CHUNK - 1) / CHUNK) * CHUNK;
        float[] out = new float[n];
        float[] prevTail = null;
        // session inputs are fixed-size [1, CHUNK]
        Map<String, OnnxTensor> inputs = new LinkedHashMap<String, OnnxTensor>();
        int produced = 0;
        for (int start = 0; start < n; start += CHUNK) {
            if (session != s) {
                return pcm; // re-initialized meanwhile
            }
            float[] chunk = new float[CHUNK];
            int len = Math.min(CHUNK, n - start);
            System.arraycopy(x, start, chunk, 0, len);
            try {
                inputs.clear();
                inputs.put("pcm_in", OnnxTensor.createTensor(env,
                        FloatBuffer.wrap(chunk), new long[]{1, CHUNK}));
                float[][] result = null;
                try (OrtSession.Result r = s.run(inputs)) {
                    Object o = r.get(0).getValue();
                    if (o instanceof float[][]) {
                        result = (float[][]) o;
                    } else if (o instanceof float[]) {
                        result = new float[][]{(float[]) o};
                    }
                }
                if (result == null) {
                    return pcm;
                }
                float[] y = result[0];
                // crossfade into the previous chunk tail
                if (prevTail != null) {
                    for (int i = 0; i < FADE && i < len && i < produced; i++) {
                        float a = (float) i / FADE;
                        int global = start + i;
                        if (global < out.length) {
                            y[i] = prevTail[i] * (1 - a) + y[i] * a;
                        }
                    }
                }
                int copy = Math.min(len, Math.max(0, out.length - start));
                if (copy > 0) {
                    System.arraycopy(y, 0, out, start, copy);
                    produced = copy;
                }
                if (len == CHUNK) {
                    prevTail = new float[FADE];
                    System.arraycopy(y, CHUNK - FADE, prevTail, 0, FADE);
                } else {
                    prevTail = null;
                }
            } catch (Throwable e) {
                LOG.e(e);
                return pcm; // fail open: unenhanced audio beats silence
            }
        }
        long dt = android.os.SystemClock.elapsedRealtime() - t0;
        double seconds = (double) n / MODEL_RATE;
        measuredSpeedFactor = seconds / Math.max(1, dt) * 1000.0;
        return floatToShort(out, MODEL_RATE, sampleRate, pcm.length);
    }

    private static short[] floatToShort(float[] x, int fromRate, int toRate, int targetLen) {
        float[] r = resample(x, fromRate, toRate);
        short[] out = new short[targetLen];
        for (int i = 0; i < out.length; i++) {
            float v = i < r.length ? r[i] : 0f;
            v = Math.max(-1f, Math.min(1f, v));
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (v * 32767f)));
        }
        return out;
    }

    private static float[] resample(short[] in, int fromRate, int toRate) {
        float[] f = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            f[i] = in[i] / 32768f;
        }
        return resample(f, fromRate, toRate);
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
        OrtSession s = session;
        session = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable e) {
                LOG.e(e);
            }
        }
    }
}
