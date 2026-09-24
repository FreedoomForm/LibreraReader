package com.foobnix.ai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.foobnix.LibreraApp;
import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.info.R;
import com.foobnix.tts.TTSEngine;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsCallback;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline AI TTS engine: Kokoro-82M (int8) through sherpa-onnx.
 * The model ships inside the APK assets (assets/kokoro) and is unpacked to
 * filesDir/kokoro on first use, so the reader works fully offline.
 */
public class KokoroEngine {
    public static final String MODEL_VERSION = "kokoro-int8-multi-lang-v1.0-1";
    private static final String TAG = "KokoroEngine";
    private static KokoroEngine INSTANCE = new KokoroEngine();

    public static KokoroEngine get() {
        return INSTANCE;
    }

    static class Item {
        boolean isSpeak;
        String text;
        String utteranceId;
        long silenceMs;
        int sid;
        float speed;
        long gen;
    }

    private volatile OfflineTts tts;
    private volatile boolean ready = false;
    private volatile boolean preparing = false;
    private volatile boolean workerRunning = false;
    private volatile boolean aborted = false;
    private volatile boolean generating = false;
    private volatile AudioTrack track;
    /** bumped by every stop(); items from an older generation are stale */
    private volatile long generation = 0;
    private final ConcurrentLinkedQueue<Item> queue = new ConcurrentLinkedQueue<Item>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final java.util.List<Runnable> pendingOnReady = new java.util.concurrent.CopyOnWriteArrayList<Runnable>();

    public boolean isReady() {
        return ready && tts != null;
    }

    public boolean isBusy() {
        return !queue.isEmpty() || generating
                || (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING);
    }

    public void prepareAsync(final Runnable onReady) {
        prepareAsync(onReady, false);
    }

    /**
     * @param silent true to suppress the "initializing" toast (background warm-up)
     */
    public void prepareAsync(final Runnable onReady, boolean silent) {
        if (isReady()) {
            if (onReady != null) {
                onReady.run();
            }
            return;
        }
        if (onReady != null) {
            pendingOnReady.add(onReady);
        }
        if (preparing) {
            return;
        }
        preparing = true;
        if (!silent) {
            toast(R.string.tts_kokoro_init);
        }
        exec.execute(new Runnable() {
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                    prepareInternal();
                    for (Runnable r : pendingOnReady) {
                        try {
                            r.run();
                        } catch (Throwable t) {
                            LOG.e(t);
                        }
                    }
                } catch (Throwable e) {
                    LOG.e(e);
                    ready = false;
                    toastSafe("AI TTS init failed: " + e.getClass().getSimpleName());
                } finally {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                    pendingOnReady.clear();
                    preparing = false;
                }
            }
        });
    }

    public synchronized void prepareSync() {
        if (isReady()) {
            return;
        }
        try {
            prepareInternal();
        } catch (Throwable e) {
            LOG.e(e);
            throw new RuntimeException(e);
        }
    }

    private synchronized void prepareInternal() throws Throwable {
        if (isReady()) {
            return;
        }
        LOG.d(TAG, "prepare start");
        File dir = extractModel();
        OfflineTtsKokoroModelConfig k = OfflineTtsKokoroModelConfig.builder()
                .setModel(new File(dir, "model.int8.onnx").getAbsolutePath())
                .setVoices(new File(dir, "voices.bin").getAbsolutePath())
                .setTokens(new File(dir, "tokens.txt").getAbsolutePath())
                .setDataDir(new File(dir, "espeak-ng-data").getAbsolutePath())
                .setDictDir(new File(dir, "dict").getAbsolutePath())
                .setLexicon(new File(dir, "lexicon-us-en.txt").getAbsolutePath() + ","
                        + new File(dir, "lexicon-gb-en.txt").getAbsolutePath() + ","
                        + new File(dir, "lexicon-zh.txt").getAbsolutePath())
                .build();
        OfflineTtsModelConfig m = OfflineTtsModelConfig.builder()
                .setKokoro(k)
                .setNumThreads(2)
                .setDebug(false)
                .build();
        OfflineTtsConfig c = OfflineTtsConfig.builder()
                .setModel(m)
                .setRuleFsts(new File(dir, "date-zh.fst").getAbsolutePath() + ","
                        + new File(dir, "number-zh.fst").getAbsolutePath() + ","
                        + new File(dir, "phone-zh.fst").getAbsolutePath())
                .build();
        OfflineTts t = new OfflineTts(c);
        OfflineTts old = tts;
        tts = t;
        ready = true;
        if (old != null) {
            try {
                old.release();
            } catch (Throwable e) {
                LOG.e(e);
            }
        }
        LOG.d(TAG, "prepare done, sampleRate", t.getSampleRate());
    }

    public void stopInternal() {
        generation++;
        aborted = true;
        queue.clear();
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.pause(); } catch (Throwable e) { }
            try { t.flush(); } catch (Throwable e) { }
            try { t.release(); } catch (Throwable e) { }
        }
    }

    public void release() {
        stopInternal();
        ready = false;
        OfflineTts t = tts;
        tts = null;
        if (t != null) {
            try { t.release(); } catch (Throwable e) { }
        }
    }

    public void enqueueSpeak(String text, String utteranceId, int sid, float speed) {
        Item it = new Item();
        it.isSpeak = true;
        it.text = text;
        it.utteranceId = utteranceId;
        it.sid = sid;
        it.speed = speed;
        enqueue(it);
    }

    public void enqueueSilence(long ms, String utteranceId, int sid, float speed) {
        Item it = new Item();
        it.isSpeak = false;
        it.silenceMs = ms;
        it.utteranceId = utteranceId;
        enqueue(it);
    }

    private void enqueue(Item it) {
        it.gen = generation;
        queue.add(it);
        ensureWorker();
    }

    public void preview(final int sid) {
        prepareAsync(new Runnable() {
            public void run() {
                stopInternal();
                enqueueSpeak(KokoroVoices.previewText(sid), "Temp", sid, 1.0f);
            }
        });
    }

    public void generateToWav(String text, int sid, float speed, String path) throws Throwable {
        OfflineTts t = tts;
        if (t == null) {
            throw new IllegalStateException("Kokoro engine is not ready");
        }
        GeneratedAudio audio = t.generate(text, sid, speed);
        audio.save(path);
    }

    private synchronized void ensureWorker() {
        if (workerRunning) {
            return;
        }
        workerRunning = true;
        exec.execute(new Runnable() {
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                    drain();
                } finally {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                    workerRunning = false;
                }
            }
        });
    }

    private void drain() {
        while (true) {
            Item it = queue.poll();
            if (it == null) {
                break;
            }
            try {
                runItem(it);
            } catch (Throwable e) {
                LOG.e(e);
            }
        }
    }

    private void runItem(final Item it) {
        try {
            OfflineTts t = tts;
            if (t == null || it.gen != generation) {
                return; // stale item dropped after stop()
            }
            if (it.isSpeak) {
                generating = true;
                aborted = false;
                final long myGen = it.gen;
                AudioTrack at = buildTrack(t.getSampleRate());
                track = at;
                try {
                    at.play();
                } catch (Throwable e) {
                    LOG.e(e);
                }
                try {
                    final AudioTrack fAt = at;
                    t.generateWithCallback(it.text, it.sid, it.speed, new OfflineTtsCallback() {
                        public Integer invoke(float[] samples) {
                            if (aborted || myGen != generation || fAt == null) {
                                return 0;
                            }
                            try {
                                fAt.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
                            } catch (Throwable e) {
                                return 0;
                            }
                            return 1;
                        }
                    });
                } catch (Throwable e) {
                    LOG.e(e);
                }
                try { at.stop(); } catch (Throwable e) { }
                try { at.release(); } catch (Throwable e) { }
                track = null;
                generating = false;
                if (!aborted && myGen == generation) {
                    TTSEngine.get().fireKokoroDone(it.utteranceId);
                }
            } else {
                aborted = false;
                long left = it.silenceMs;
                while (left > 0 && !aborted && it.gen == generation) {
                    long step = Math.min(50, left);
                    Thread.sleep(step);
                    left -= step;
                }
                if (!aborted && it.gen == generation) {
                    TTSEngine.get().fireKokoroDone(it.utteranceId);
                }
            }
        } catch (Throwable e) {
            LOG.e(e);
            if (!aborted && it.gen == generation) {
                TTSEngine.get().fireKokoroError(it.utteranceId);
            }
        } finally {
            generating = false;
        }
    }

    private AudioTrack buildTrack(int sampleRate) {
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        if (minBuf <= 0) {
            minBuf = 16384;
        }
        int buf = Math.max(minBuf, sampleRate * 4);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(buf)
                .build();
    }

    private File extractModel() throws Exception {
        Context c = LibreraApp.context;
        File outDir = new File(c.getFilesDir(), "kokoro");
        File marker = new File(outDir, ".version");
        if (marker.exists()) {
            String v = readText(marker).trim();
            if (MODEL_VERSION.equals(v)) {
                return outDir;
            }
        }
        deleteRecursive(outDir);
        outDir.mkdirs();
        copyAssets(c, "kokoro", outDir);
        writeText(marker, MODEL_VERSION);
        LOG.d(TAG, "model extracted to", outDir.getAbsolutePath());
        return outDir;
    }

    private void copyAssets(Context c, String assetsPath, File outDir) throws Exception {
        String[] list = c.getAssets().list(assetsPath);
        if (list == null) {
            return;
        }
        outDir.mkdirs();
        for (String name : list) {
            String child = assetsPath + "/" + name;
            File outFile = new File(outDir, name);
            String[] sub = c.getAssets().list(child);
            if (sub != null && sub.length > 0) {
                copyAssets(c, child, outFile);
            } else {
                copyAssetFile(c, child, outFile);
            }
        }
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
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception e) { }
            if (out != null) try { out.close(); } catch (Exception e) { }
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursive(k);
            }
        }
        f.delete();
    }

    private String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            in.read(b);
            return new String(b, "UTF-8");
        } finally {
            in.close();
        }
    }

    private void writeText(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private void toast(final int resId) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    Toast.makeText(LibreraApp.context, resId, Toast.LENGTH_LONG).show();
                } catch (Throwable e) { }
            }
        });
    }

    private void toastSafe(final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    Toast.makeText(LibreraApp.context, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable e) { }
            }
        });
    }
}
