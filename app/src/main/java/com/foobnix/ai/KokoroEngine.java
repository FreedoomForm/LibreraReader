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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Offline AI TTS engine: Inflect-Nano-v2 (VITS, 24 kHz, en-us, ~16 MB fp32)
 * executed through the ONNX Runtime that is already in the APK. The split
 * export (duration.onnx + decode.onnx) comes from the official
 * owensong/Inflect-Nano-v2-ONNX repository; the text frontend
 * ({@link InflectFrontend}) mirrors the model's own python pipeline using a
 * precomputed espeak lexicon, so no espeak-ng is needed on the device.
 * <p>
 * The model ships inside the APK assets (assets/inflect) and is unpacked to
 * filesDir/inflect on first use, so the reader works fully offline.
 * RTF ~0.2 on a weak sandbox core (single thread); the class name and the
 * public API predate the model swap (this used to be Kokoro-7M-Distill via
 * sherpa-onnx) and are kept so the reader/services/UI stay untouched.
 */
public class KokoroEngine {
    public static final String MODEL_VERSION = "inflect-nano-v2-onnx-v1";
    public static final int SAMPLE_RATE = 24000;
    private static final String TAG = "KokoroEngine";
    /** always-on log tag: visible even in release builds (LOG.* is compiled out) */
    public static final String DIAG_TAG = "KokoroDiag";
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
        /** TTSEngine word-list epoch at enqueue time: highlight events from an
         *  older page are dropped instead of marking the wrong page */
        long epoch;
        /** sentence clip: the words it contains (parallel arrays, null for a
         *  plain utterance). Word highlights are estimated inside the clip and
         *  fired from the playhead - ONE synthesis call per sentence instead of
         *  one per word, which used to pay the full model overhead per word and
         *  read at a crawl on mid-range phones. */
        String[] words;
        int[] flats;
        float[] weights;
    }

    /** a fully synthesized clip: PCM plus the exact start frame of every IPA
     *  unit recovered from the duration model (null when not recoverable) */
    static class Clip {
        final float[] pcm;
        final long[] unitFrames;

        Clip(final float[] pcm, final long[] unitFrames) {
            this.pcm = pcm;
            this.unitFrames = unitFrames;
        }
    }

    /** collects the exact per-unit start frames while a clip is synthesized */
    static class UnitTrace {
        final java.util.List<Long> frames = new java.util.ArrayList<Long>();
        /** set false when any subchunk's timing could not be recovered exactly */
        volatile boolean exact = true;

        long[] toArray() {
            final long[] out = new long[frames.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = frames.get(i);
            }
            return out;
        }
    }

    /** streaming synthesis sink: return 0 to abort (sherpa callback contract) */
    public interface SynthCallback {
        Integer invoke(float[] samples);
    }

    private volatile OrtEnvironment ortEnv;
    private volatile OrtSession durSession;
    private volatile OrtSession decSession;
    private volatile InflectFrontend frontend;
    private volatile boolean ready = false;
    private volatile boolean preparing = false;
    private final java.util.concurrent.atomic.AtomicInteger workerScheduled =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean aborted = false;
    private volatile boolean generating = false;
    private volatile AudioTrack track;
    /** bumped by every stop(); items from an older generation are stale */
    private volatile long generation = 0;
    /** frames written into the current track since it was created (for pacing) */
    private final java.util.concurrent.atomic.AtomicLong writtenFrames =
            new java.util.concurrent.atomic.AtomicLong(0);
    /** upper bound for waiting until the playhead drains the buffer */
    private static final long BARRIER_TIMEOUT_MS = 10000;
    /** if the playhead does not move for this long while we are pacing, the
     *  audio sink is not consuming anything (CI emulator / head-less device):
     *  pacing would deadlock, so the buffer is flushed the old way instead */
    private static final long FROZEN_HEAD_MS = 1200;
    /** consecutive zero-sample generations - 3 in a row trip the system-TTS fallback */
    private final java.util.concurrent.atomic.AtomicInteger zeroStreak =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** refuse to prepare below this free RAM: the engine needs ~135 MB steady
     *  (~211 MB transient peak per chunk - measured) and preparing it on a
     *  device that is already tight - e.g. with a memory-heavy PDF open -
     *  got the whole process LMK-killed (the "kicked back to the main page"
     *  bug). 220 MB was measured to be too optimistic: passing the check at
     *  230 MB free left only ~20 MB of headroom after init and the system
     *  still killed us. 384 MB leaves a real margin after the load. Falling
     *  back to the system voice politely is always better than dying mid-read. */
    private static final long MIN_PREPARE_AVAIL_MEM = 384L * 1024 * 1024;
    /** paragraph barriers stop waiting at this buffer lead instead of a full
     *  drain: an AudioTrack that runs dry mid-page UNDERRUNS, and on many
     *  devices the HAL then eats the first frames of the next clip - the
     *  reported "the first word of every line is missing" (PDF pages turn
     *  every visual line into its own paragraph). 300 ms of lead keeps the
     *  sink fed across the barrier; only page-end/stop barriers still drain
     *  to zero so the page flip lands exactly at the audio end. */
    private static final long BARRIER_LEAD_FLOOR_MS = 300;
    /** silence written into a freshly built track before the first clip: a
     *  brand-new AudioTrack's first write can glitch the same way an underrun
     *  does on some HALs - 80 ms of leading silence masks it (inaudible, and
     *  word-highlight frames are captured after it, so nothing shifts) */
    private static final int TRACK_PRIME_MS = 80;

    private final ConcurrentLinkedQueue<Item> queue = new ConcurrentLinkedQueue<Item>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    /** Look-ahead synthesis: while the current clip is PLAYING, the next
     *  queued clip is synthesized into memory on this thread. Paragraph
     *  barriers (waitDrain) then never turn the next clip's synthesis latency
     *  into audible silence ("reads one line, waits 2-3 s, continues") - the
     *  prefetched PCM starts writing the instant the barrier lifts. Only ONE
     *  clip is ever in flight, so steady-state synthesis and playback never
     *  overlap ONNX work (no doubled transient memory peaks). */
    private final java.util.concurrent.ExecutorService prefetchExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                public Thread newThread(final Runnable r) {
                    final Thread t = new Thread(r, "kokoro-prefetch");
                    t.setDaemon(true);
                    return t;
                }
            });
    private final java.util.concurrent.ConcurrentHashMap<Item, java.util.concurrent.Future<Clip>> prefetchFuts =
            new java.util.concurrent.ConcurrentHashMap<Item, java.util.concurrent.Future<Clip>>();
    /** fires the per-word highlight events of sentence clips from the playhead */
    private final java.util.concurrent.ScheduledExecutorService highlightExec =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                public Thread newThread(final Runnable r) {
                    final Thread t = new Thread(r, "kokoro-highlight");
                    t.setDaemon(true);
                    return t;
                }
            });
    private final java.util.List<Runnable> pendingOnReady = new java.util.concurrent.CopyOnWriteArrayList<Runnable>();

    public boolean isReady() {
        return ready && durSession != null;
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
                    // do not fight the system for memory: preparing the engine on
                    // a device that is already tight (PDF bitmaps, big caches)
                    // used to get the whole process LMK-killed. Refuse politely
                    // instead - the reader falls back to the system voice.
                    try {
                        android.app.ActivityManager am = (android.app.ActivityManager) LibreraApp.context
                                .getSystemService(Context.ACTIVITY_SERVICE);
                        if (am != null) {
                            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                            am.getMemoryInfo(mi);
                            if (mi.lowMemory || mi.availMem < MIN_PREPARE_AVAIL_MEM) {
                                android.util.Log.i(DIAG_TAG, "kokoro init SKIPPED: low memory, avail="
                                        + (mi.availMem >> 20) + " MB");
                                ready = false;
                                toastSafe("AI voice needs more free memory - using the system voice");
                                TTSEngine.get().onKokoroFailure();
                                return;
                            }
                        }
                    } catch (Throwable memErr) {
                        LOG.d(TAG, "memory check failed:", memErr);
                    }
                    long initStart = android.os.SystemClock.elapsedRealtime();
                    prepareInternal();
                    android.util.Log.i(DIAG_TAG, "kokoro init OK in "
                            + (android.os.SystemClock.elapsedRealtime() - initStart) + " ms, sampleRate=" + SAMPLE_RATE);
                    for (Runnable r : pendingOnReady) {
                        try {
                            r.run();
                        } catch (Throwable t) {
                            LOG.e(t);
                        }
                    }
                } catch (Throwable e) {
                    LOG.e(e);
                    android.util.Log.i(DIAG_TAG, "kokoro init FAILED: " + e);
                    ready = false;
                    toastSafe("AI TTS init failed: " + e.getClass().getSimpleName());
                    // flip the session fallback NOW instead of leaving the user
                    // silent until the 120 s watchdog: the next page turn reads
                    // with the system voice, and the user gets the explanation
                    try {
                        TTSEngine.get().onKokoroFailure();
                    } catch (Throwable t) {
                        LOG.e(t);
                    }
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
        android.util.Log.i(DIAG_TAG, "model ready: duration.onnx=" + new File(dir, "duration.onnx").length()
                + " decode.onnx=" + new File(dir, "decode.onnx").length()
                + " lexicon=" + new File(dir, "lexicon-en.txt").length());
        OrtSession dur = null;
        OrtSession dec = null;
        InflectFrontend fe = null;
        try {
            ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            try {
                // MEMORY IS THE #1 KILLER HERE, not speed: measured on the real
                // graphs, one decode pass of a 280-char chunk spiked +156 MB and
                // the BFC arena kept it resident for the whole session - with a
                // PDF open that pushed the process over the LMK line and Android
                // silently restarted it ("kicked to the main page" bug). Levers:
                // (a) ONE intra-op thread - measured FASTER than 2-3 threads for
                //     this small model (thread-pool overhead dominates) with the
                //     smallest workspace, RTF ~0.13 on a weak sandbox core;
                // (b) NO arena allocator - ORT returns scratch memory to the OS
                //     after every chunk instead of hoarding it for reuse:
                //     steady footprint drops from ~268 MB to ~135 MB.
                opts.setIntraOpNumThreads(1);
                opts.setMemoryPatternOptimization(false);
                opts.setCPUArenaAllocator(false);
                LOG.d(TAG, "prepare: 1 intra-op thread, arena off");
            } catch (Throwable t) {
                LOG.e(t);
            }
            dur = ortEnv.createSession(new File(dir, "duration.onnx").getAbsolutePath(), opts);
            dec = ortEnv.createSession(new File(dir, "decode.onnx").getAbsolutePath(), opts);
            fe = new InflectFrontend(new File(dir, "lexicon-en.txt"));
            android.util.Log.i(DIAG_TAG, "inflect frontend ready: " + fe.lexiconSize() + " words");
            durSession = dur;
            decSession = dec;
            frontend = fe;
            ready = true;
            LOG.d(TAG, "prepare done, sampleRate", SAMPLE_RATE);
        } catch (Throwable e) {
            // a half-initialized engine holds tens of MB in ORT arenas - release
            // it right away so the fallback path starts from a clean heap
            close(dur);
            close(dec);
            durSession = null;
            decSession = null;
            frontend = null;
            ready = false;
            throw e;
        }
    }

    public void stopInternal() {
        generation++;
        aborted = true;
        queue.clear();
        for (final java.util.concurrent.Future<Clip> f : prefetchFuts.values()) {
            f.cancel(false);
        }
        prefetchFuts.clear();
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
        cancelIdleRelease();
        OrtSession dur = durSession;
        OrtSession dec = decSession;
        durSession = null;
        decSession = null;
        frontend = null;
        close(dur);
        close(dec);
    }

    /**
     * The engine stays warm while a reading session is active, but 134 MB
     * must not sit in the process forever after the user stopped listening -
     * that resident block is lowmemorykiller bait while browsing big PDFs
     * (the recurring "kicked to the main page" reports). 3 minutes without
     * any playback activity releases the model; the next Play re-prepares
     * lazily (a couple of seconds, once) - the same trade the Kokoro-7M
     * era made, and stability beats a 2-second warm-up.
     */
    private static final long IDLE_RELEASE_DELAY_MS = 3L * 60 * 1000;
    private final android.os.Handler idleHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleRelease = new Runnable() {
        @Override public void run() {
            try {
                if (isBusy() || preparing) {
                    // still working (or loading) - look again later
                    idleHandler.postDelayed(this, IDLE_RELEASE_DELAY_MS);
                    return;
                }
                if (isReady()) {
                    android.util.Log.i(DIAG_TAG, "kokoro idle release: no playback for "
                            + (IDLE_RELEASE_DELAY_MS / 1000) + "s, freeing the model");
                    releaseAsync();
                }
            } catch (Throwable t) {
                LOG.e(t);
            }
        }
    };

    /** (re)starts the idle-release countdown; call on every playback activity */
    private void scheduleIdleRelease() {
        idleHandler.removeCallbacks(idleRelease);
        idleHandler.postDelayed(idleRelease, IDLE_RELEASE_DELAY_MS);
    }

    private void cancelIdleRelease() {
        idleHandler.removeCallbacks(idleRelease);
    }

    /**
     * Frees the model OFF the caller thread. release() tears down ~134 MB of
     * native session state; doing that on the main thread (onTrimMemory,
     * idle timer) froze the UI for seconds on slow devices and earned the
     * process an ANR dialog on the emulator. Serialized with the synthesis
     * executor so it can never race a running drain()/prepare().
     */
    public void releaseAsync() {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    if (isReady()) {
                        release();
                    }
                } catch (Throwable t) {
                    LOG.e(t);
                }
            }
        });
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

    public void enqueueSpeak(String text, String utteranceId, int sid, float speed) {
        Item it = new Item();
        it.isSpeak = true;
        it.text = text;
        it.utteranceId = utteranceId;
        it.sid = sid;
        it.speed = speed;
        enqueue(it);
    }

    /**
     * A sentence clip that carries its own word list: after synthesis the
     * engine maps every word to a playhead frame (by character weight) and
     * fires "ttsW<flatIdx>" highlight events exactly when the word is heard.
     */
    public void enqueueSpeakWords(final String text, final String[] words, final int[] flats,
            final float[] weights, final String utteranceId, final int sid, final float speed) {
        Item it = new Item();
        it.isSpeak = true;
        it.text = text;
        it.words = words;
        it.flats = flats;
        it.weights = weights;
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
        it.epoch = TTSEngine.get().getTTSWordEpoch();
        queue.add(it);
        ensureWorker();
        // any playback activity pushes the idle-release countdown 3 min out
        scheduleIdleRelease();
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
        TtsAudio audio = generateForDiag(text, sid, speed);
        if (!audio.save(path)) {
            throw new IllegalStateException("failed to write " + path);
        }
    }

    /**
     * Schedules a drain pass on the single executor. CAS-based so an item
     * enqueued between the worker's last poll() and its exit can never be
     * left in the queue forever (lost-wakeup race = endless silence).
     */
    private synchronized void ensureWorker() {
        if (!workerScheduled.compareAndSet(0, 1)) {
            return;
        }
        exec.execute(new Runnable() {
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                    drain();
                } finally {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                    workerScheduled.set(0);
                    // re-arm if items arrived while we were exiting
                    if (!queue.isEmpty()) {
                        ensureWorker();
                    }
                }
            }
        });
    }

    /** Tunable kill-switch limits via adb shell setprop (emulators/CI only). */
    private static long propLimit(String key, long defMs) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            String v = (String) c.getMethod("get", String.class).invoke(null, key);
            if (v != null && !v.trim().isEmpty()) {
                return Long.parseLong(v.trim());
            }
        } catch (Throwable t) {
        }
        return defMs;
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

    /**
     * Look-ahead: finds the first speakable item still queued for THIS
     * generation and synthesizes it into memory on the prefetch thread. The
     * result is joined by runItem when the playhead reaches the clip, so a
     * paragraph barrier's waitDrain never exposes the synthesis latency as
     * silence. Skipped when the engine is not ready or a newer generation
     * (stop) is current.
     */
    private void schedulePrefetch(final long myGen) {
        if (!ready || aborted || myGen != generation) {
            return;
        }
        Item target = null;
        for (final Item q : queue) {
            if (q != null && q.isSpeak && q.gen == myGen) {
                target = q;
                break;
            }
        }
        if (target == null || prefetchFuts.containsKey(target)) {
            return; // nothing speakable queued or already scheduled
        }
        final Item tgt = target;
        final java.util.concurrent.FutureTask<Clip> task =
                new java.util.concurrent.FutureTask<Clip>(new java.util.concurrent.Callable<Clip>() {
                    public Clip call() {
                        try {
                            if (aborted || myGen != generation) {
                                return null;
                            }
                            android.util.Log.i(DIAG_TAG, "prefetch start: "
                                    + (tgt.text == null ? 0 : tgt.text.length()) + " chars");
                            final Clip clip = synthesizeToMemory(tgt.text, tgt.speed);
                            if (aborted || myGen != generation) {
                                return null;
                            }
                            android.util.Log.i(DIAG_TAG, "prefetch done: "
                                    + (clip == null || clip.pcm == null ? 0 : clip.pcm.length) + " samples");
                            return clip;
                        } catch (Throwable t) {
                            LOG.e(t);
                            return null; // runItem falls back to live synthesis
                        }
                    }
                });
        if (prefetchFuts.putIfAbsent(target, task) != null) {
            return;
        }
        prefetchExec.execute(task);
    }

    /**
     * Joins the look-ahead result for an item (null when none/failed). Blocks
     * while the prefetch thread is still synthesizing - the wait equals the
     * synthesis time the inline path would pay, but it STARTED earlier, so on
     * net the clip is ready sooner or instantly.
     */
    private Clip takePrefetch(final Item it) {
        final java.util.concurrent.Future<Clip> f = prefetchFuts.remove(it);
        if (f == null) {
            return null;
        }
        try {
            return f.get();
        } catch (Throwable e) {
            return null; // cancelled or failed - synthesize inline
        }
    }

    /** synthesizes the WHOLE clip into memory (look-ahead prefetch) */
    private Clip synthesizeToMemory(String text, float speed) throws Throwable {
        final java.util.List<float[]> parts = new java.util.ArrayList<float[]>();
        final int[] total = new int[1];
        final UnitTrace trace = new UnitTrace();
        generateStream(text, speed, new SynthCallback() {
            public Integer invoke(float[] samples) {
                parts.add(samples);
                total[0] += samples.length;
                return aborted ? 0 : 1;
            }
        }, trace);
        if (total[0] <= 0) {
            return null;
        }
        final float[] all = new float[total[0]];
        int off = 0;
        for (final float[] p : parts) {
            System.arraycopy(p, 0, all, off, p.length);
            off += p.length;
        }
        return new Clip(all, trace.exact ? trace.toArray() : null);
    }

    /** milliseconds of audio currently buffered ahead of the playhead */
    private long leadMs(final AudioTrack at, final int sampleRate) {
        if (at == null || sampleRate <= 0) {
            return 0;
        }
        final long head = at.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long lead = writtenFrames.get() - head;
        if (lead < 0) {
            lead = 0;
        }
        return lead * 1000L / sampleRate;
    }

    /**
     * Returns a PLAYING track (reusing the session one, rebuilding it once if
     * the hardware broke it) or null when playback is impossible.
     */
    private AudioTrack ensureTrack(final int sampleRate) {
        AudioTrack at = track;
        if (at == null) {
            at = buildTrack(sampleRate);
            writtenFrames.set(0);
            track = at;
            primeTrack(at, sampleRate);
        }
        try {
            if (at.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                at.play();
            }
            return at;
        } catch (Throwable e) {
            LOG.e(e);
            try { at.release(); } catch (Throwable e2) { }
            if (track == at) {
                track = null;
            }
            try {
                final AudioTrack fresh = buildTrack(sampleRate);
                writtenFrames.set(0);
                track = fresh;
                primeTrack(fresh, sampleRate);
                fresh.play();
                android.util.Log.i(DIAG_TAG, "audio track rebuilt after failure");
                return fresh;
            } catch (Throwable e3) {
                LOG.e(e3);
                return null;
            }
        }
    }

    /**
     * Writes TRACK_PRIME_MS of silence into a brand-new track before the
     * first clip: masks the first-write HAL glitch (same mechanism as the
     * mid-page underrun that used to eat the first word of every line).
     */
    private void primeTrack(final AudioTrack at, final int sampleRate) {
        try {
            final int frames = sampleRate * TRACK_PRIME_MS / 1000;
            final float[] zeros = new float[frames];
            int off = 0;
            while (off < frames) {
                final int w = at.write(zeros, off, frames - off, AudioTrack.WRITE_NON_BLOCKING);
                if (w <= 0) {
                    break; // buffer full - the prime has done its job anyway
                }
                off += w;
                writtenFrames.addAndGet(w);
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    /**
     * Waits until the hardware has played everything written so far (or the
     * wait times out / the session is stopped / the sink proves head-less).
     * Used by barrier items so the page flip happens exactly when the audio
     * ends, not while seconds of speech are still buffered.
     */
    private void waitDrain(final AudioTrack at, final int sampleRate, final long myGen) {
        waitDrainFloor(at, sampleRate, myGen, 0);
    }

    /**
     * Paragraph barriers stop waiting at a small buffer lead instead of a
     * full drain: a track that runs dry mid-page underruns and the next
     * clip's first frames get eaten by the HAL on many devices ("the first
     * word of every line is missing"). Keeping {@code floorMs} of audio
     * stacked across the barrier keeps the sink fed and the clip starts
     * intact; the page-end barrier still uses floor 0.
     */
    private void waitDrainFloor(final AudioTrack at, final int sampleRate, final long myGen,
            final long floorMs) {
        if (at == null) {
            return;
        }
        final long deadline = android.os.SystemClock.elapsedRealtime() + BARRIER_TIMEOUT_MS;
        long lastHead = at.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long lastMove = android.os.SystemClock.elapsedRealtime();
        while (!aborted && myGen == generation
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            if (leadMs(at, sampleRate) <= floorMs) {
                break;
            }
            final long now = android.os.SystemClock.elapsedRealtime();
            final long head = at.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            if (head != lastHead) {
                lastHead = head;
                lastMove = now;
            } else if (now - lastMove > FROZEN_HEAD_MS) {
                break; // nothing is consuming the stream - do not wait forever
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** playhead freeze detector for the head-less sink escape */
    private static final class HeadWatch {
        long lastHead;
        long lastMove;
    }

    private HeadWatch newHeadWatch(final AudioTrack at) {
        final HeadWatch hw = new HeadWatch();
        hw.lastHead = at.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        hw.lastMove = android.os.SystemClock.elapsedRealtime();
        return hw;
    }

    /**
     * Escapes the head-less audio sink trap: when nothing consumes the stream
     * (emulator started with -noaudio, CI), WRITE_BLOCKING would block forever
     * once the buffer fills. The buffer is flushed so generation continues;
     * real phones never hit this because the playhead always moves.
     */
    private void headlessEscape(final AudioTrack at, final int sampleRate, final HeadWatch hw) {
        final long now = android.os.SystemClock.elapsedRealtime();
        final long head = at.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        if (head != hw.lastHead) {
            hw.lastHead = head;
            hw.lastMove = now;
            return;
        }
        // frozen for a while AND at least a second of audio stacked up
        if (now - hw.lastMove > FROZEN_HEAD_MS && writtenFrames.get() - head > sampleRate) {
            drainSink(at);
            hw.lastMove = android.os.SystemClock.elapsedRealtime();
        }
    }

    /** drops everything buffered and restarts the track (head-less sinks only) */
    private void drainSink(final AudioTrack at) {
        try { at.pause(); } catch (Throwable e) { }
        try { at.flush(); } catch (Throwable e) { }
        writtenFrames.set(0); // the playhead position starts over after a flush
        try { at.play(); } catch (Throwable e) { }
        android.util.Log.i(DIAG_TAG, "audio sink not consuming - buffer flushed (emulator/headless path)");
    }

    /**
     * Fires the word-start (highlight) event with the delay of how long the
     * clip still sits in the playback buffer, so the mark lands on the word
     * the user actually HEARS at that moment. Skipped when the session was
     * stopped or the page changed in the meantime.
     */
    private void fireStartAt(final String utteranceId, final long delayMs, final long myGen, final long epoch) {
        final Runnable fire = new Runnable() {
            public void run() {
                TTSEngine.get().fireKokoroStart(utteranceId);
            }
        };
        if (delayMs <= 30) {
            if (!aborted && myGen == generation && TTSEngine.get().getTTSWordEpoch() == epoch) {
                fire.run();
            }
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                if (!aborted && myGen == generation && TTSEngine.get().getTTSWordEpoch() == epoch) {
                    fire.run();
                }
            }
        }, delayMs);
    }

    /**
     * Maps IPA-unit start frames to the item's words. The frontend phonemizes
     * word-by-word, so every word contributes a known number of units; when
     * the counts sum up exactly, word k starts where its first unit starts.
     * Returns null when the alignment is unreliable - the caller falls back to
     * the character-weight estimator.
     */
    private long[] wordFramesFromUnits(final Item it, final long[] unitFrames) {
        final InflectFrontend fe = frontend;
        if (fe == null || unitFrames == null || unitFrames.length == 0
                || it.words == null || it.words.length == 0) {
            return null;
        }
        final long[] out = new long[it.words.length];
        int u = 0;
        for (int k = 0; k < it.words.length; k++) {
            final String ph;
            try {
                ph = fe.phonemize(it.words[k] == null ? "" : it.words[k]);
            } catch (Throwable t) {
                return null;
            }
            final int cnt = ph == null ? 0 : countUnits(ph);
            if (u + cnt > unitFrames.length) {
                return null;
            }
            out[k] = cnt > 0 ? unitFrames[u] : -1; // a dropped word never highlights
            u += cnt;
        }
        if (u != unitFrames.length) {
            return null; // leftover units - alignment unreliable
        }
        return out;
    }

    /** number of space-separated IPA units in a phoneme string */
    private static int countUnits(final String phonemes) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < phonemes.length(); i++) {
            if (phonemes.charAt(i) != ' ') {
                if (!in) {
                    n++;
                    in = true;
                }
            } else {
                in = false;
            }
        }
        return n;
    }

    /**
     * Fires the per-word highlight events ("ttsW<flatIdx>") for one sentence
     * clip. When {@code exact} word frames are available (recovered from the
     * duration model) they are used directly; otherwise word offsets are
     * estimated from character weights inside the clip. A 50 ms ticker
     * compares the hardware playhead with each word's frame and fires exactly
     * when the user HEARS the word. On head-less sinks (emulator without
     * audio) the playhead never moves - the remaining words are fired
     * sequentially so the highlight chain stays observable and CI can assert it.
     */
    private void scheduleWordHighlights(final AudioTrack at, final int sampleRate, final long myGen,
            final Item it, final long startFrame, final long totalSamples, final long[] exact) {
        final int n = it.words.length;
        if (n == 0 || totalSamples <= 0) {
            return;
        }
        final long[] frames = new long[n];
        if (exact != null) {
            for (int k = 0; k < n; k++) {
                frames[k] = startFrame + Math.max(0, exact[k]);
            }
            android.util.Log.i(DIAG_TAG, "highlight exact: " + n + " words");
        } else {
            android.util.Log.i(DIAG_TAG, "highlight estimated: " + n + " words");
            float totalW = 0;
            for (final float w : it.weights) {
                totalW += w;
            }
            if (totalW <= 0) {
                totalW = n;
                for (int k = 0; k < n; k++) {
                    it.weights[k] = 1;
                }
            }
            float cum = 0;
            for (int k = 0; k < n; k++) {
                cum += it.weights[k];
                frames[k] = startFrame + (long) ((cum / totalW) * totalSamples);
            }
        }
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(0);
        final long[] headState = {at.getPlaybackHeadPosition() & 0xFFFFFFFFL,
                android.os.SystemClock.elapsedRealtime()};
        final java.util.concurrent.ScheduledFuture<?>[] self = new java.util.concurrent.ScheduledFuture[1];
        android.util.Log.i(DIAG_TAG, "highlight schedule: " + n + " words, clip " + totalSamples
                + " frames from " + startFrame);
        self[0] = highlightExec.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try {
                    if (aborted || myGen != generation) {
                        self[0].cancel(false);
                        return;
                    }
                    long head;
                    try {
                        head = at.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    } catch (Throwable e) {
                        // the track was released while words were pending
                        // (head-less sink drains early) - play the rest out
                        fireRemaining(it, frames, next, n);
                        self[0].cancel(false);
                        return;
                    }
                    int i = next.get();
                    while (i < n && frames[i] <= head) {
                        fireWord(it, i);
                        i++;
                    }
                    next.set(i);
                    if (i >= n) {
                        self[0].cancel(false);
                        return;
                    }
                    final long now = android.os.SystemClock.elapsedRealtime();
                    if (head != headState[0]) {
                        headState[0] = head;
                        headState[1] = now;
                    } else if (now - headState[1] > FROZEN_HEAD_MS) {
                        // nothing consumes the stream (emulator/CI audio sink):
                        // fire the remaining words in order, do not stall
                        fireRemaining(it, frames, next, n);
                        self[0].cancel(false);
                    }
                } catch (Throwable t) {
                    LOG.e(t);
                    self[0].cancel(false);
                }
            }
        }, 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void fireRemaining(final Item it, final long[] frames,
            final java.util.concurrent.atomic.AtomicInteger next, final int n) {
        final int from = next.get();
        for (int k = from; k < n; k++) {
            fireWord(it, k);
        }
        next.set(n);
        android.util.Log.i(DIAG_TAG, "highlight: fired remaining " + (n - from) + "/" + n
                + " words without a moving playhead (head-less sink)");
    }

    private void fireWord(final Item it, final int k) {
        if (it.flats[k] < 0) {
            return; // carried over from the previous page / unmatched - nothing to mark
        }
        if (TTSEngine.get().getTTSWordEpoch() != it.epoch) {
            return; // the page changed while the clip was in flight
        }
        TTSEngine.get().fireKokoroStart(TTSEngine.WORD_SIGNAL + it.flats[k]);
    }

    /** a chunk of true silence written into the audio stream */
    private void writeSilence(final AudioTrack at, final int sampleRate, final long ms, final long myGen,
            final HeadWatch hw) {
        if (at == null || ms <= 0) {
            return;
        }
        long left = (long) (sampleRate * (Math.min(ms, 4000) / 1000f));
        final float[] zeros = new float[Math.min((int) left, Math.max(sampleRate / 10, 1))];
        while (left > 0 && !aborted && myGen == generation) {
            final int n = (int) Math.min(left, zeros.length);
            try {
                headlessEscape(at, sampleRate, hw);
                int off = 0;
                while (off < n) {
                    final int written = at.write(zeros, off, n - off, AudioTrack.WRITE_NON_BLOCKING);
                    if (written > 0) {
                        off += written;
                        writtenFrames.addAndGet(written);
                    } else {
                        Thread.sleep(20);
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Throwable e) {
                break;
            }
            left -= n;
        }
    }

    private void runItem(final Item it) {
        try {
            if (it.gen != generation) {
                return; // stale item dropped after stop()
            }
            if (it.isSpeak) {
                generating = true;
                aborted = false;
                android.util.Log.i(DIAG_TAG, "gen start: chars=" + (it.text == null ? -1 : it.text.length()) + ", sid=" + it.sid);
                final long myGen = it.gen;
                final int sampleRate = SAMPLE_RATE;
                // ONE track per session, kept PLAYING across items: stop()/play()
                // between words raced the drain and clipped word tails on real
                // phones (random garbled sounds). The track is only released when
                // the queue empties (after a drain) or on stop().
                final AudioTrack fAt = ensureTrack(sampleRate);
                if (fAt == null) {
                    // without a playing track every write() below would fail -
                    // report an error instead of a silent "done" that flips
                    // pages with no sound at all
                    LOG.e(new IllegalStateException("kokoro: AudioTrack is not playable"));
                    TTSEngine.get().fireKokoroError(it.utteranceId);
                    return;
                }
                // No explicit pacing gate: the WRITE_BLOCKING callback write
                // already self-paces generation to the audio buffer (6 s), which
                // keeps the speech gap-free instead of throttling it to 600 ms
                // ahead of the playhead. The old explicit gate was one of the
                // reasons word-level sessions crawled.
                final long[] pcm = new long[1];
                final java.util.concurrent.atomic.AtomicBoolean gotAudio =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                final java.util.concurrent.atomic.AtomicBoolean genDone =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                /** frame index of the clip's first sample, captured at first write */
                final long[] itemStartFrame = new long[1];
                /** exact per-IPA-unit start frames of this clip (null = estimator) */
                final long[][] clipUnits = new long[1][];
                final java.util.concurrent.atomic.AtomicBoolean startFired =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                final HeadWatch watch = newHeadWatch(fAt);
                final long genStart = android.os.SystemClock.elapsedRealtime();
                final SynthCallback sink = new SynthCallback() {
                    public Integer invoke(float[] samples) {
                        if (aborted || myGen != generation) {
                            return 0;
                        }
                        try {
                            // frame index where this clip's audio starts in
                            // the stream (word highlights are mapped from here)
                            if (startFired.compareAndSet(false, true)) {
                                itemStartFrame[0] = writtenFrames.get();
                                if (it.words == null) {
                                    // plain utterance: fire its start event
                                    // delayed by whatever is still buffered
                                    fireStartAt(it.utteranceId,
                                            Math.min(leadMs(fAt, sampleRate), 1000), myGen, it.epoch);
                                }
                            }
                            headlessEscape(fAt, sampleRate, watch);
                            // NON_BLOCKING + retry loop: generation can
                            // never wedge inside a blocking write on a
                            // head-less sink (CI emulator), and the
                            // escape/watchdog keep making progress
                            int off = 0;
                            while (off < samples.length) {
                                if (aborted || myGen != generation) {
                                    return 0;
                                }
                                final int w = fAt.write(samples, off, samples.length - off,
                                        AudioTrack.WRITE_NON_BLOCKING);
                                if (w > 0) {
                                    off += w;
                                    pcm[0] += w;
                                    writtenFrames.addAndGet(w);
                                    gotAudio.set(true);
                                } else {
                                    try {
                                        Thread.sleep(20);
                                    } catch (InterruptedException e) {
                                        return 0;
                                    }
                                }
                            }
                        } catch (Throwable e) {
                            return 0;
                        }
                        return 1;
                    }
                };
                // Run the native synthesis on a DEDICATED thread: a stuck generate
                // (espeak infinite loop on exotic text) must never block the shared
                // executor - otherwise every later Play/preview stays silent forever.
                final Thread genThread = new Thread(new Runnable() {
                    public void run() {
                        try {
                            // a look-ahead job may have synthesized this clip while
                            // the PREVIOUS one was playing - write the prefetched PCM
                            // through the same sink (start frame, highlights, pacing
                            // all identical); on a miss or failure synthesize live
                            final Clip pre = takePrefetch(it);
                            if (pre != null && pre.pcm != null && pre.pcm.length > 0) {
                                clipUnits[0] = pre.unitFrames;
                                int off = 0;
                                while (off < pre.pcm.length && !aborted && myGen == generation) {
                                    headlessEscape(fAt, sampleRate, watch);
                                    final int len = Math.min(4800, pre.pcm.length - off);
                                    final Integer r = sink.invoke(
                                            java.util.Arrays.copyOfRange(pre.pcm, off, off + len));
                                    if (r == null || r == 0) {
                                        break;
                                    }
                                    off += len;
                                }
                            } else {
                                final UnitTrace liveTrace = it.words == null ? null : new UnitTrace();
                                generateStream(it.text, it.speed, sink, liveTrace);
                                if (liveTrace != null && liveTrace.exact) {
                                    clipUnits[0] = liveTrace.toArray();
                                }
                            }
                        } catch (Throwable e) {
                            LOG.e(e);
                        } finally {
                            genDone.set(true);
                        }
                    }
                });
                genThread.setName("kokoro-gen");
                try {
                    genThread.setDaemon(true);
                } catch (Throwable e) {
                }
                genThread.start();
                // Kill-switches: no FIRST audio (default 60s) or a stalled stream
                // (default 30s). A healthy stream is never abandoned - only a hung
                // one. Slow emulators (TCG) can raise both via adb shell setprop.
                long firstMs = propLimit("kokoro.first_audio_ms", 60000);
                long stallMs = propLimit("kokoro.stall_ms", 30000);
                long lastCount = -1L;
                long lastProgress = genStart;
                boolean firstLogged = false;
                while (!genDone.get() && !aborted && myGen == generation) {
                    if (pcm[0] != lastCount) {
                        lastCount = pcm[0];
                        lastProgress = android.os.SystemClock.elapsedRealtime();
                    }
                    long stalledMs = android.os.SystemClock.elapsedRealtime() - lastProgress;
                    if (gotAudio.get() && !firstLogged) {
                        firstLogged = true;
                        android.util.Log.i(DIAG_TAG, "first audio after "
                                + (android.os.SystemClock.elapsedRealtime() - genStart) + " ms");
                    }
                    if (!gotAudio.get() && stalledMs > firstMs) {
                        android.util.Log.i(DIAG_TAG, "WATCHDOG: no first audio in " + firstMs + " ms - system TTS fallback");
                        LOG.e(new IllegalStateException("kokoro timeout: no audio within 60s"));
                        TTSEngine.get().onKokoroFailure();
                        break;
                    }
                    if (gotAudio.get() && stalledMs > stallMs) {
                        android.util.Log.i(DIAG_TAG, "WATCHDOG: stream stalled " + stalledMs + " ms - system TTS fallback");
                        LOG.e(new IllegalStateException("kokoro timeout: audio stream stalled"));
                        TTSEngine.get().onKokoroFailure();
                        break;
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (!genDone.get()) {
                    // timed out (or stopped): release the track so a thread stuck
                    // in WRITE_BLOCKING gets an exception and exits; a leaked
                    // native thread (espeak hang) is a daemon and dies with us
                    aborted = true;
                    stopInternal();
                    generating = false;
                    TTSEngine.get().fireKokoroError(it.utteranceId);
                    return;
                }
                if (pcm[0] > 0) {
                    float audioSec = pcm[0] / (float) SAMPLE_RATE;
                    float genSec = (android.os.SystemClock.elapsedRealtime() - genStart) / 1000f;
                    // RTF < 1 means synthesis outpaces playback (no stutter)
                    LOG.d(TAG, "kokoro gen", String.format("%.1f", genSec), "s for",
                            String.format("%.1f", audioSec), "s audio, RTF",
                            String.format("%.2f", genSec / Math.max(audioSec, 0.01f)));
                    android.util.Log.i(DIAG_TAG, "gen done: " + pcm[0] + " samples, RTF "
                            + String.format("%.2f", genSec / Math.max(audioSec, 0.01f)));
                }
                // schedule this sentence clip's word highlights: the ticker maps
                // every word to a playhead frame and fires it when it is heard.
                // Exact timing comes from the duration model whenever the IPA
                // units align with the item's words - no more highlight drift
                if (it.words != null && pcm[0] > 0) {
                    final long[] exact = wordFramesFromUnits(it, clipUnits[0]);
                    scheduleWordHighlights(fAt, sampleRate, myGen, it, itemStartFrame[0], pcm[0], exact);
                }
                // this clip's PCM is now fully written while the track keeps
                // playing it for seconds - use the playback time to synthesize
                // the NEXT queued clip into memory (gap-free reading)
                schedulePrefetch(myGen);
                // keep the track playing for the next item; release it only when
                // nothing else is queued and the tail has actually played out
                if (queue.isEmpty()) {
                    waitDrain(fAt, sampleRate, myGen);
                    if (queue.isEmpty() && track == fAt) {
                        track = null;
                        try { fAt.release(); } catch (Throwable e) { }
                    }
                }
                generating = false;
                if (!aborted && myGen == generation) {
                    if (pcm[0] > 0) {
                        zeroStreak.set(0);
                        TTSEngine.get().fireKokoroDone(it.utteranceId);
                    } else {
                        // zero samples = something failed silently - report an
                        // error so the session stops instead of paging silently
                        LOG.e(new IllegalStateException("kokoro item produced 0 samples"));
                        android.util.Log.i(DIAG_TAG, "ZERO SAMPLES for sid=" + it.sid);
                        toastSafe("AI voice error: no audio for this text");
                        TTSEngine.get().fireKokoroError(it.utteranceId);
                        // one empty clip is an edge case, several in a row mean
                        // the engine is broken on this device - stop paging
                        // silently through the rest of the book, use system voice
                        if (zeroStreak.incrementAndGet() >= 3) {
                            android.util.Log.i(DIAG_TAG, "WATCHDOG: 3 empty generations in a row - system TTS fallback");
                            LOG.e(new IllegalStateException("kokoro: 3 consecutive zero-sample generations"));
                            zeroStreak.set(0);
                            TTSEngine.get().onKokoroFailure();
                        }
                    }
                }
            } else {
                aborted = false;
                final long myGen = it.gen;
                final int sampleRate = SAMPLE_RATE;
                // the playhead may spend seconds here draining the buffered
                // tail - use the time to synthesize the next clip into memory
                schedulePrefetch(myGen);
                AudioTrack at = track;
                if (it.silenceMs > 0) {
                    // the pause must live in the AUDIO, not in a generation-thread
                    // sleep: a sleep vanishes whenever generation runs ahead of
                    // playback, which glued whole phrases together ("speaks 5
                    // phrases at once"). Skipped when we are behind anyway (RTF>1
                    // device - no extra gap on top of the synthesis lag).
                    if (at != null) {
                        if (leadMs(at, sampleRate) > 40) {
                            try {
                                if (at.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                                    at.play();
                                }
                            } catch (Throwable e) {
                                LOG.e(e);
                            }
                            writeSilence(at, sampleRate, it.silenceMs, myGen, newHeadWatch(at));
                        }
                    }
                } else if (at != null) {
                    // barrier (0 ms): paragraph counter and the page flip must
                    // land when the audio actually ends, not while seconds of
                    // speech are still buffered - otherwise every page tail is
                    // cut off by the next stopInternal(). Page-end/stop barriers
                    // drain to zero; PARAGRAPH barriers keep a 300 ms lead - a
                    // track that runs dry mid-page underruns and the next
                    // clip's first word gets eaten by the HAL (the reported
                    // "the beginning of every line is not spoken" bug).
                    final String uid = it.utteranceId;
                    final boolean pageEndBarrier = uid == null || uid.equals(TTSEngine.UTTERANCE_ID_DONE)
                            || uid.startsWith(TTSEngine.STOP_SIGNAL) || uid.equals("Temp");
                    if (pageEndBarrier) {
                        waitDrainFloor(at, sampleRate, myGen, 0);
                    } else {
                        waitDrainFloor(at, sampleRate, myGen, BARRIER_LEAD_FLOOR_MS);
                    }
                }
                if (queue.isEmpty() && track != null && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    final AudioTrack dead = track;
                    track = null;
                    try { dead.release(); } catch (Throwable e) { }
                }
                if (!aborted && myGen == generation) {
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

    public int getSampleRate() {
        return SAMPLE_RATE;
    }

    /**
     * Inflect-Nano-v2 synthesis: normalize -> lexicon IPA -> tokens ->
     * duration.onnx -> decode.onnx, streamed to the callback per sentence
     * chunk with the reference runner's boundary pauses and edge fades.
     * A callback return value of 0 aborts the rest of the text.
     */
    private void generateStream(String text, float speed, SynthCallback cb) throws Throwable {
        generateStream(text, speed, cb, null);
    }

    /**
     * @param trace when non-null, collects the EXACT start frame of every IPA
     *              unit (space-separated phoneme group) within the clip - the
     *              word-highlight playhead uses it instead of the character
     *              weight estimate (which drifted over sentence pauses and
     *              number expansions and made the highlight jump around)
     */
    private void generateStream(String text, float speed, SynthCallback cb, final UnitTrace trace) throws Throwable {
        InflectFrontend fe = frontend;
        OrtSession dur = durSession;
        OrtSession dec = decSession;
        if (fe == null || dur == null || dec == null) {
            throw new IllegalStateException("Inflect engine is not ready");
        }
        final float sp = Math.max(0.5f, Math.min(2.0f, speed <= 0 ? 1f : speed));
        final float lengthScale = 1f / sp;
        List<String> chunks = InflectFrontend.splitSentences(text);
        if (chunks.isEmpty()) {
            return;
        }
        long clipBase = 0; // absolute frame offset of the current subchunk inside the clip
        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (aborted) {
                    return;
                }
                String chunk = chunks.get(i);
                if (i > 0) {
                    // boundary pause lives in the audio stream (plays as silence)
                    int pauseLen = Math.round(SAMPLE_RATE * InflectFrontend.boundaryPauseSec(chunks.get(i - 1)));
                    int written = writeZeros(cb, pauseLen);
                    clipBase += Math.max(0, written);
                    if (written == 0 && aborted) {
                        return;
                    }
                }
                String phones = fe.phonemize(chunk);
                if (phones == null) {
                    continue;
                }
                // A 140-char TEXT chunk can phonemize into 300+ IPA characters
                // (lexicon strings run longer than the source words), which
                // with blank interleaving means T≈1300 decode frames and a
                // ~200 MB transient allocation - exactly what crashed the
                // process natively on the emulator with a PDF open. Cap the
                // DECODE size by splitting the phoneme string on word
                // boundaries; the audio plays back seamlessly (edge-faded).
                java.util.List<String> subPhones =
                        InflectFrontend.splitPhonemes(phones, InflectFrontend.PHONEME_LIMIT);
                for (int s = 0; s < subPhones.size(); s++) {
                    if (aborted) {
                        return;
                    }
                    String part = subPhones.get(s);
                    long[] sids = InflectFrontend.toTokenIds(part);
                    if (sids == null || sids.length < 4) {
                        continue;
                    }
                    if (subPhones.size() > 1) {
                        android.util.Log.i(DIAG_TAG, "gen subchunk " + (s + 1) + "/" + subPhones.size()
                                + ": phones=" + part.length() + " tokens=" + sids.length);
                    } else {
                        android.util.Log.i(DIAG_TAG, "gen chunk: phones=" + part.length()
                                + " tokens=" + sids.length
                                + " nativeHeap=" + (android.os.Debug.getNativeHeapAllocatedSize() >> 20) + "MB");
                    }
                    final long wrote = synthesizeTokens(sids, i * 7 + s, lengthScale, dur, dec, cb,
                            trace, part, clipBase);
                    if (wrote < 0) {
                        return; // aborted or sink stopped
                    }
                    clipBase += wrote;
                }
            }
        } finally {
            // (tensor maps are closed inside synthesizeTokens)
        }
    }

    /**
     * Runs duration + decode for ONE token sequence and streams the resulting
     * waveform into the callback. Split out of generateStream so long phoneme
     * strings can be synthesized in decode-sized pieces without nesting tensor
     * lifecycle management inside two loops.
     *
     * @return the number of samples streamed, or -1 when the caller must stop
     *         (aborted or the sink closed)
     */
    private long synthesizeTokens(long[] ids, int seed, float lengthScale, OrtSession dur, OrtSession dec,
            SynthCallback cb, final UnitTrace trace, final String phonemePart, final long clipBase) throws Throwable {
        Map<String, OnnxTensor> durInputs = new LinkedHashMap<String, OnnxTensor>();
        Map<String, OnnxTensor> decInputs = new LinkedHashMap<String, OnnxTensor>();
        OrtSession.Result durRes = null;
        OrtSession.Result decRes = null;
        try {
            durInputs.put("tokens", OnnxTensor.createTensor(ortEnv,
                    LongBuffer.wrap(ids), new long[]{1, ids.length}));
            durInputs.put("lengths", OnnxTensor.createTensor(ortEnv,
                    LongBuffer.wrap(new long[]{ids.length}), new long[]{1}));
            durInputs.put("length_scale", OnnxTensor.createTensor(ortEnv,
                    FloatBuffer.wrap(new float[]{lengthScale}), new long[]{}));
            durRes = dur.run(durInputs);
            OnnxTensor mT = (OnnxTensor) durRes.get("m_p_exp").orElse(null);
            OnnxTensor logsT = (OnnxTensor) durRes.get("logs_p_exp").orElse(null);
            OnnxTensor maskT = (OnnxTensor) durRes.get("y_mask").orElse(null);
            if (mT == null || logsT == null || maskT == null) {
                return 0; // nothing speakable in this piece - keep going
            }
            long[] mShape = mT.getInfo().getShape();
            float[] m = flat(mT);
            float[] logs = flat(logsT);
            float[] mask = flat(maskT);
            if (m == null) {
                return 0;
            }
            android.util.Log.i(DIAG_TAG, "gen duration OK: T=" + (mShape.length > 2 ? mShape[2] : m.length)
                    + " nativeHeap=" + (android.os.Debug.getNativeHeapAllocatedSize() >> 20) + "MB");
            if (trace != null) {
                // EXACT per-unit start frames from the expanded duration
                // features - the highlight playhead consumes them; a subchunk
                // that cannot be recovered poisons the whole clip (estimator
                // fallback), which is still better than a drifting highlight
                final long[] tokStarts = tokenFrameStarts(m, mShape, ids.length);
                if (tokStarts == null) {
                    trace.exact = false;
                } else {
                    appendUnitFrames(trace, phonemePart, tokStarts, clipBase);
                }
            }
            // gaussian latent noise, seeded per piece (reference: seed + index)
            Random rng = new Random(1000L + seed);
            float[] noise = new float[m.length];
            for (int k = 0; k < noise.length; k++) {
                noise[k] = (float) rng.nextGaussian();
            }
            decInputs.put("m_p_exp", OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(m), mShape));
            decInputs.put("logs_p_exp", OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(logs), mShape));
            decInputs.put("y_mask", OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(mask),
                    new long[]{1, 1, mShape.length > 2 ? mShape[2] : 0}));
            decInputs.put("zp_noise", OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(noise), mShape));
            decInputs.put("noise_scale", OnnxTensor.createTensor(ortEnv,
                    FloatBuffer.wrap(new float[]{0.667f}), new long[]{}));
            try {
                decRes = dec.run(decInputs);
                OnnxTensor wavT = (OnnxTensor) decRes.get("waveform").orElse(null);
                if (wavT == null) {
                    return 0;
                }
                float[] wav = flat(wavT);
                if (wav == null || wav.length == 0) {
                    return 0;
                }
                edgeFade(wav, SAMPLE_RATE, 5);
                final int SLICE = 4800;
                int off = 0;
                while (off < wav.length) {
                    if (aborted) {
                        return -1;
                    }
                    int len = Math.min(SLICE, wav.length - off);
                    float[] slice = new float[len];
                    System.arraycopy(wav, off, slice, 0, len);
                    Integer r = cb.invoke(slice);
                    if (r == null || r == 0) {
                        return -1;
                    }
                    off += len;
                }
                return wav.length;
            } finally {
                closeQuietly(decRes);
                closeTensors(decInputs);
            }
        } finally {
            closeQuietly(durRes);
            closeTensors(durInputs);
        }
    }

    /**
     * Exact per-token frame starts recovered from the duration model's
     * expanded features: m_p_exp repeats each input token's feature column
     * for the token's predicted duration, and the VITS blank interleaving
     * (x _ x _ ...) guarantees adjacent tokens differ, so every token
     * boundary is visible as a column change. Returns null when the recovery
     * is not exact (merged/extra segments) - the caller falls back to the
     * character-weight estimator.
     */
    private static long[] tokenFrameStarts(final float[] m, final long[] mShape, final int tokenCount) {
        if (mShape == null || mShape.length < 3 || tokenCount <= 0) {
            return null;
        }
        final int D = (int) mShape[1];
        final int T = (int) mShape[2];
        if (D <= 0 || T <= 0 || m.length != D * (long) T) {
            return null;
        }
        final long[] starts = new long[tokenCount];
        int seg = 0;
        for (int t = 1; t < T; t++) {
            final int off = t * D;
            boolean same = true;
            for (int d = 0; d < D; d++) {
                if (m[off + d] != m[off - D + d]) {
                    same = false;
                    break;
                }
            }
            if (!same) {
                seg++;
                if (seg >= tokenCount) {
                    return null; // more segments than tokens - not a clean repeat
                }
                starts[seg] = t;
            }
        }
        if (seg != tokenCount - 1) {
            return null; // some token's segment vanished (zero duration) - inexact
        }
        return starts;
    }

    /**
     * Appends the start frame of every IPA unit (space-separated phoneme
     * group of one subchunk) to the trace. Phoneme-string char {@code c} maps
     * to token {@code 2c+1} (toTokenIds interleaves a blank after every
     * symbol and leaves a leading blank at token 0).
     */
    private static void appendUnitFrames(final UnitTrace trace, final String part,
            final long[] tokStarts, final long clipBase) {
        if (part == null || tokStarts == null) {
            trace.exact = false;
            return;
        }
        final int n = part.length();
        int c = 0;
        while (c < n) {
            while (c < n && part.charAt(c) == ' ') {
                c++;
            }
            if (c >= n) {
                break;
            }
            final int tok = 2 * c + 1;
            if (tok >= tokStarts.length) {
                trace.exact = false;
                return;
            }
            trace.frames.add(clipBase + tokStarts[tok]);
            while (c < n && part.charAt(c) != ' ') {
                c++;
            }
        }
    }

    private static void closeTensors(Map<String, OnnxTensor> inputs) {
        for (OnnxTensor t : inputs.values()) {
            closeQuietly(t);
        }
        inputs.clear();
    }

    private static int writeZeros(SynthCallback cb, int len) {
        if (len <= 0) {
            return len;
        }
        float[] zeros = new float[Math.min(len, SAMPLE_RATE / 5)];
        int left = len;
        while (left > 0) {
            int n = Math.min(left, zeros.length);
            float[] slice = n == zeros.length ? zeros : java.util.Arrays.copyOf(zeros, n);
            Integer r = cb.invoke(slice);
            if (r == null || r == 0) {
                return 0;
            }
            left -= n;
        }
        return len;
    }

    private static float[] flat(OnnxTensor t) {
        try {
            FloatBuffer fb = t.getFloatBuffer();
            float[] out = new float[fb.remaining()];
            fb.get(out);
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 5 ms linear fade at both ends (the reference runner's edge_fade) */
    private static void edgeFade(float[] wav, int sampleRate, int ms) {
        int frames = Math.min(Math.round(sampleRate * ms / 1000f), wav.length / 2);
        if (frames <= 0) {
            return;
        }
        for (int i = 0; i < frames; i++) {
            float g = (float) i / frames;
            wav[i] *= g;
            wav[wav.length - 1 - i] *= g;
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable e) {
            }
        }
    }

    /**
     * Diagnostics: synchronous generation outside the reading queue.
     */
    public TtsAudio generateForDiag(String text, int sid, float speed) throws Throwable {
        OrtSession dur = durSession;
        if (dur == null) {
            throw new IllegalStateException("Inflect engine is not ready");
        }
        final java.util.List<float[]> parts = new java.util.ArrayList<float[]>();
        final int[] total = new int[1];
        generateStream(text, speed, new SynthCallback() {
            public Integer invoke(float[] samples) {
                parts.add(samples);
                total[0] += samples.length;
                return 1;
            }
        });
        float[] all = new float[total[0]];
        int off = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, all, off, p.length);
            off += p.length;
        }
        return new TtsAudio(all, SAMPLE_RATE);
    }

    /**
     * Diagnostics: plays the given PCM through the SAME AudioTrack path the
     * reader uses and reports how far the hardware consumed it.
     */
    public String playForDiag(final float[] samples) {
        OrtSession dur = durSession;
        if (dur == null || samples == null || samples.length == 0) {
            return "playback: no data to play";
        }
        AudioTrack at = null;
        try {
            at = buildTrack(SAMPLE_RATE);
            at.play();
            final int written = at.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
            long start = android.os.SystemClock.elapsedRealtime();
            int head = 0;
            int lastHead = -1;
            long lastMove = start;
            while (android.os.SystemClock.elapsedRealtime() - lastMove < 800
                    && android.os.SystemClock.elapsedRealtime() - start < 20000) {
                Thread.sleep(100);
                head = at.getPlaybackHeadPosition();
                if (head != lastHead) {
                    lastHead = head;
                    lastMove = android.os.SystemClock.elapsedRealtime();
                }
                if (head >= written) {
                    break;
                }
            }
            long ms = android.os.SystemClock.elapsedRealtime() - start;
            return "playback: written=" + written + " frames, hardware played " + head
                    + " frames (" + (written > 0 ? 100 * head / written : 0) + "%) in " + ms + " ms";
        } catch (Throwable e) {
            return "playback: PLAYBACK ERROR: " + e;
        } finally {
            if (at != null) {
                try { at.stop(); } catch (Throwable e) { }
                try { at.release(); } catch (Throwable e) { }
            }
        }
    }

    private AudioTrack buildTrack(int sampleRate) {
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        if (minBuf <= 0) {
            minBuf = 16384;
        }
        // float PCM is 4 bytes per frame: a 6 s buffer absorbs generation jitter
        // on slow devices (WRITE_BLOCKING self-paces generation into it), which
        // is what keeps the speech gap-free instead of "speaks, then pauses"
        int buf = Math.max(minBuf, (int) (sampleRate * 6f * 4));
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
        File outDir = new File(c.getFilesDir(), "inflect");
        File marker = new File(outDir, ".version");
        if (marker.exists()) {
            String v = readText(marker).trim();
            if (MODEL_VERSION.equals(v)) {
                return outDir;
            }
        }
        deleteRecursive(outDir);
        outDir.mkdirs();
        copyAssets(c, "inflect", outDir);
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
