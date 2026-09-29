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
    private final ConcurrentLinkedQueue<Item> queue = new ConcurrentLinkedQueue<Item>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
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
        ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        try {
            // synthesis is the heavy part: give ONNX as many cores as we can spare
            // (all but one on big phones; 2 on a weak 4-core device)
            int cores = Runtime.getRuntime().availableProcessors();
            int onnxThreads = Math.max(2, Math.min(6, cores - 1));
            opts.setIntraOpNumThreads(onnxThreads);
            LOG.d(TAG, "prepare: cores", cores, "onnxThreads", onnxThreads);
        } catch (Throwable t) {
            LOG.e(t);
        }
        OrtSession dur = ortEnv.createSession(new File(dir, "duration.onnx").getAbsolutePath(), opts);
        OrtSession dec = ortEnv.createSession(new File(dir, "decode.onnx").getAbsolutePath(), opts);
        InflectFrontend fe = new InflectFrontend(new File(dir, "lexicon-en.txt"));
        android.util.Log.i(DIAG_TAG, "inflect frontend ready: " + fe.lexiconSize() + " words");
        durSession = dur;
        decSession = dec;
        frontend = fe;
        ready = true;
        LOG.d(TAG, "prepare done, sampleRate", SAMPLE_RATE);
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
        OrtSession dur = durSession;
        OrtSession dec = decSession;
        durSession = null;
        decSession = null;
        frontend = null;
        close(dur);
        close(dec);
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
     * Waits until the hardware has played everything written so far (or the
     * wait times out / the session is stopped / the sink proves head-less).
     * Used by barrier items so the page flip happens exactly when the audio
     * ends, not while seconds of speech are still buffered.
     */
    private void waitDrain(final AudioTrack at, final int sampleRate, final long myGen) {
        if (at == null) {
            return;
        }
        final long deadline = android.os.SystemClock.elapsedRealtime() + BARRIER_TIMEOUT_MS;
        long lastHead = at.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long lastMove = android.os.SystemClock.elapsedRealtime();
        while (!aborted && myGen == generation
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            if (leadMs(at, sampleRate) <= 0) {
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
     * Fires the per-word highlight events ("ttsW<flatIdx>") for one sentence
     * clip. Word offsets are estimated from character weights inside the clip
     * and mapped to playhead frames; a 50 ms ticker compares the hardware
     * playhead with each word's frame and fires exactly when the user HEARS
     * the word. On head-less sinks (emulator without audio) the playhead never
     * moves - the remaining words are fired sequentially so the highlight
     * chain stays observable and CI can assert it.
     */
    private void scheduleWordHighlights(final AudioTrack at, final int sampleRate, final long myGen,
            final Item it, final long startFrame, final long totalSamples) {
        final int n = it.words.length;
        if (n == 0 || totalSamples <= 0) {
            return;
        }
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
        final long[] frames = new long[n];
        float cum = 0;
        for (int k = 0; k < n; k++) {
            cum += it.weights[k];
            frames[k] = startFrame + (long) ((cum / totalW) * totalSamples);
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
                final java.util.concurrent.atomic.AtomicBoolean startFired =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                final HeadWatch watch = newHeadWatch(fAt);
                final long genStart = android.os.SystemClock.elapsedRealtime();
                // Run the native synthesis on a DEDICATED thread: a stuck generate
                // (espeak infinite loop on exotic text) must never block the shared
                // executor - otherwise every later Play/preview stays silent forever.
                final Thread genThread = new Thread(new Runnable() {
                    public void run() {
                        try {
                            generateStream(it.text, it.speed, new SynthCallback() {
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
                            });
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
                // every word to a playhead frame and fires it when it is heard
                if (it.words != null && pcm[0] > 0) {
                    scheduleWordHighlights(fAt, sampleRate, myGen, it, itemStartFrame[0], pcm[0]);
                }
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
                    // cut off by the next stopInternal()
                    waitDrain(at, sampleRate, myGen);
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
        OrtSession.Result durRes = null;
        OrtSession.Result decRes = null;
        Map<String, OnnxTensor> durInputs = new LinkedHashMap<String, OnnxTensor>();
        Map<String, OnnxTensor> decInputs = new LinkedHashMap<String, OnnxTensor>();
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
                    if (written == 0 && aborted) {
                        return;
                    }
                }
                String phones = fe.phonemize(chunk);
                if (phones == null) {
                    continue;
                }
                long[] ids = InflectFrontend.toTokenIds(phones);
                if (ids == null || ids.length < 4) {
                    continue;
                }
                durInputs.put("tokens", OnnxTensor.createTensor(ortEnv,
                        LongBuffer.wrap(ids), new long[]{1, ids.length}));
                durInputs.put("lengths", OnnxTensor.createTensor(ortEnv,
                        LongBuffer.wrap(new long[]{ids.length}), new long[]{1}));
                durInputs.put("length_scale", OnnxTensor.createTensor(ortEnv,
                        FloatBuffer.wrap(new float[]{lengthScale}), new long[]{}));
                try {
                    durRes = dur.run(durInputs);
                    OnnxTensor mT = (OnnxTensor) durRes.get("m_p_exp").orElse(null);
                    OnnxTensor logsT = (OnnxTensor) durRes.get("logs_p_exp").orElse(null);
                    OnnxTensor maskT = (OnnxTensor) durRes.get("y_mask").orElse(null);
                    if (mT == null || logsT == null || maskT == null) {
                        continue;
                    }
                    long[] mShape = mT.getInfo().getShape();
                    float[] m = flat(mT);
                    float[] logs = flat(logsT);
                    float[] mask = flat(maskT);
                    if (m == null) {
                        continue;
                    }
                    // gaussian latent noise, seeded per chunk (reference: seed + index)
                    Random rng = new Random(1000L + i);
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
                            continue;
                        }
                        float[] wav = flat(wavT);
                        if (wav == null || wav.length == 0) {
                            continue;
                        }
                        edgeFade(wav, SAMPLE_RATE, 5);
                        final int SLICE = 4800;
                        int off = 0;
                        while (off < wav.length) {
                            if (aborted) {
                                return;
                            }
                            int len = Math.min(SLICE, wav.length - off);
                            float[] slice = new float[len];
                            System.arraycopy(wav, off, slice, 0, len);
                            Integer r = cb.invoke(slice);
                            if (r == null || r == 0) {
                                return;
                            }
                            off += len;
                        }
                    } finally {
                        closeQuietly(decRes);
                        decRes = null;
                        closeTensors(decInputs);
                    }
                } finally {
                    closeQuietly(durRes);
                    durRes = null;
                    closeTensors(durInputs);
                }
            }
        } finally {
            closeTensors(durInputs);
            closeTensors(decInputs);
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
