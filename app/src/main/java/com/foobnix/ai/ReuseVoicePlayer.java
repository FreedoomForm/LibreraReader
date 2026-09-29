package com.foobnix.ai;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.foobnix.LibreraApp;
import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.tts.TTSEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-time DeepFilterNet3 voice enhancement pipeline for the SYSTEM TTS engine.
 * <p>
 * Instead of letting the engine play the synthesized audio directly, each
 * page paragraph is synthesized to a WAV file (synthesizeToFile), filtered
 * through {@link VoiceEnhancer} (DeepFilterNet3, ONNX) and played back
 * through an AudioTrack. Word-highlight ranges reported during synthesis
 * (onRangeStart) are re-fired at the exact playback position, so the
 * highlight stays glued to the enhanced audio.
 * <p>
 * The service protocol is preserved: playback completion fires the same
 * utterance callbacks (fireKokoroDone / downstream listener) that the plain
 * system-TTS path fires at synthesis completion, so paragraph bookmarks,
 * page turns, stop signals and the sleep timer all behave identically.
 * If the device is too slow for the model, the DFN3 stage turns itself off
 * and reading continues with the loudness-polished voice - playback never
 * stalls.
 */
public class ReuseVoicePlayer {
    private static final String TAG = "ReuseVoicePlayer";
    /** if the enhancement runs slower than this factor of realtime, give it up */
    private static final double MIN_SPEED_FACTOR = 1.15;

    private static ReuseVoicePlayer INSTANCE = new ReuseVoicePlayer();

    public static ReuseVoicePlayer get() {
        return INSTANCE;
    }

    static class Item {
        String text;
        String utteranceId;
        boolean fireDoneAtEnd = true;
        // synthesis results
        CountDownLatch synthLatch;
        volatile boolean synthOk;
        volatile boolean aborted;
        // highlight ranges recorded during synthesis: [start, frame]
        final List<int[]> ranges = new ArrayList<int[]>();
        int sampleRate;
        short[] pcm;
    }

    private final ConcurrentLinkedQueue<Item> queue = new ConcurrentLinkedQueue<Item>();
    private final java.util.concurrent.atomic.AtomicInteger workerScheduled =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger generation =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile AudioTrack track;
    private volatile UtteranceProgressListener ownListener;
    private UtteranceProgressListener savedListener;
    private volatile boolean slowDeviceDisabled = false;
    private int slowStreak = 0;

    public boolean isActive() {
        return ownListener != null;
    }

    public boolean isSlowDeviceDisabled() {
        return slowDeviceDisabled;
    }

    public void prepareAsync() {
        VoiceEnhancer.get().prepareAsync();
    }

    /**
     * Replaces the engine's progress listener with a wrapper that records
     * highlight ranges and suppresses synthesis-time completion events (the
     * service must hear them at PLAYBACK time instead). Callers of
     * {@link TTSEngine#speek} go through {@link #enqueue} afterwards.
     */
    public boolean activate() {
        if (isActive()) {
            return true;
        }
        TextToSpeech tts = TTSEngine.get().getTTS(null);
        if (tts == null) {
            return false;
        }
        savedListener = TTSEngine.get().getKokoroProgressListener();
        ownListener = new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
            }

            @Override public void onDone(String utteranceId) {
                // synthesis finished - the worker latch drives the pipeline
                Item it = findByUtterance(utteranceId);
                if (it != null) {
                    it.synthOk = true;
                    it.synthLatch.countDown();
                }
            }

            @Override public void onError(String utteranceId) {
                Item it = findByUtterance(utteranceId);
                if (it != null) {
                    it.synthOk = false;
                    it.synthLatch.countDown();
                }
            }

            @Override public void onStop(String utteranceId, boolean interrupted) {
                Item it = findByUtterance(utteranceId);
                if (it != null) {
                    it.aborted = true;
                    it.synthLatch.countDown();
                }
            }

            @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                Item it = findByUtterance(utteranceId);
                if (it != null) {
                    synchronized (it.ranges) {
                        it.ranges.add(new int[]{start, frame, end});
                    }
                }
            }
        };
        try {
            tts.setOnUtteranceProgressListener(ownListener);
        } catch (Throwable t) {
            LOG.e(t);
        }
        return true;
    }

    public void deactivate() {
        UtteranceProgressListener own = ownListener;
        ownListener = null;
        if (own != null && savedListener != null) {
            TextToSpeech tts = TTSEngine.get().getTTS(null);
            if (tts != null) {
                try {
                    tts.setOnUtteranceProgressListener(savedListener);
                } catch (Throwable t) {
                    LOG.e(t);
                }
            }
        }
        savedListener = null;
    }

    private Item findByUtterance(String utteranceId) {
        for (Item it : queue) {
            if (it.utteranceId != null && it.utteranceId.equals(utteranceId)) {
                return it;
            }
        }
        return current.get();
    }

    private final ThreadLocal<Item> current = new ThreadLocal<Item>();

    public void enqueue(String text, String utteranceId) {
        Item it = new Item();
        it.text = text;
        it.utteranceId = utteranceId;
        queue.add(it);
        ensureWorker();
    }

    /** end-of-page marker: fires the page-turn utterance at playback position */
    public void enqueuePageEnd() {
        Item it = new Item();
        it.utteranceId = TTSEngine.UTTERANCE_ID_DONE;
        it.text = "";
        queue.add(it);
        ensureWorker();
    }

    public void stop() {
        generation.incrementAndGet();
        queue.clear();
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.pause();
            } catch (Throwable e) {
            }
            try {
                t.flush();
            } catch (Throwable e) {
            }
            try {
                t.release();
            } catch (Throwable e) {
            }
        }
        deactivate();
    }

    private void ensureWorker() {
        if (!workerScheduled.compareAndSet(0, 1)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                    drain();
                } finally {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                    workerScheduled.set(0);
                    if (!queue.isEmpty()) {
                        ensureWorker();
                    }
                }
            }
        }, "reuse-player");
        t.setDaemon(false);
        t.start();
    }

    private void drain() {
        int myGen = generation.get();
        Item it;
        while (myGen == generation.get() && (it = queue.poll()) != null) {
            try {
                process(it, myGen);
            } catch (Throwable e) {
                LOG.e(e);
            }
            if (myGen != generation.get()) {
                return;
            }
        }
    }

    private void process(Item it, int myGen) {
        TextToSpeech tts = TTSEngine.get().getTTS(null);
        if (tts == null) {
            return;
        }
        // marker items (page end, stop signal) carry no text: their completion
        // simply fires at this point in the pipeline, i.e. in playback order
        if (it.text == null || it.text.length() == 0) {
            fireDone(it.utteranceId);
            return;
        }

        // ---- 1. synthesize to file ----
        File wav = new File(LibreraApp.context.getCacheDir(),
                "tts_enh_" + System.nanoTime() + ".wav");
        it.synthLatch = new CountDownLatch(1);
        current.set(it);
        Bundle params = new Bundle();
        try {
            tts.synthesizeToFile(it.text, params, wav, it.utteranceId);
        } catch (Throwable e) {
            LOG.e(e);
            current.set(null);
            fireDone(it.utteranceId);
            return;
        }
        try {
            it.synthLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        current.set(null);
        if (myGen != generation.get() || it.aborted) {
            wav.delete();
            return;
        }
        if (!it.synthOk || !wav.exists() || wav.length() < 44) {
            wav.delete();
            // synthesis failed: keep the protocol alive
            fireDone(it.utteranceId);
            return;
        }

        // ---- 2. decode + enhance ----
        short[] pcm = decodeWav(wav);
        wav.delete();
        if (pcm == null || pcm.length == 0) {
            fireDone(it.utteranceId);
            return;
        }
        it.sampleRate = lastRate;
        boolean enhanced = false;
        if (AppState.get().ttsVoiceEnhance) {
            // VoiceEnhancer always applies the audible polish chain; the DFN3
            // model stage runs on top when its models are loaded and it was
            // not auto-disabled on a slow device.
            short[] before = pcm;
            long t0 = android.os.SystemClock.elapsedRealtime();
            short[] after = VoiceEnhancer.get().enhance(pcm, it.sampleRate);
            long dt = android.os.SystemClock.elapsedRealtime() - t0;
            if (after != null && after != before && after.length > 0) {
                pcm = after;
                enhanced = true;
                if (!VoiceEnhancer.get().isDfn3Disabled()) {
                    double audioSec = (double) after.length / it.sampleRate;
                    double factor = audioSec / Math.max(0.001, dt / 1000.0);
                    if (factor < MIN_SPEED_FACTOR) {
                        if (++slowStreak >= 2) {
                            VoiceEnhancer.get().setDfn3Disabled(true);
                            slowDeviceDisabled = true;
                            android.util.Log.i(KokoroEngine.DIAG_TAG,
                                    "DFN3 disabled: device too slow (factor " + factor
                                    + "); polish continues");
                            toastSlow();
                        }
                    } else {
                        slowStreak = 0;
                    }
                }
            }
        }
        android.util.Log.i(KokoroEngine.DIAG_TAG, "dfn3 utterance: samples=" + pcm.length
                + " rate=" + it.sampleRate + " enhanced=" + enhanced
                + " dfn3=" + !VoiceEnhancer.get().isDfn3Disabled());

        // ---- 3. play through AudioTrack ----
        fireStart(it.utteranceId);
        playBlocking(pcm, it, myGen);
        if (myGen != generation.get()) {
            return;
        }
        // the TTS pause between paragraphs is part of the playback flow here
        if (AppState.get().ttsPauseDuration > 0) {
            try {
                Thread.sleep(Math.min(5000, AppState.get().ttsPauseDuration));
            } catch (InterruptedException e) {
            }
        }
        // ---- 4. the service hears completion at the playback position ----
        fireDone(it.utteranceId);
    }

    private void playBlocking(short[] pcm, Item it, int myGen) {
        int rate = it.sampleRate > 0 ? it.sampleRate : 22050;
        int bufSize = Math.max(8192, AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT));
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufSize)
                .build();
        track = t;
        try {
            t.play();
        } catch (Throwable e) {
            LOG.e(e);
            t.release();
            if (track == t) {
                track = null;
            }
            return;
        }
        final int CHUNK = 4096;
        int off = 0;
        while (off < pcm.length && myGen == generation.get()) {
            int len = Math.min(CHUNK, pcm.length - off);
            int w = t.write(pcm, off, len, AudioTrack.WRITE_BLOCKING);
            if (w < 0) {
                break;
            }
            off += w;
            scheduleHighlights(it, t);
        }
        try {
            // wait for the last samples to actually play out
            while (myGen == generation.get() && t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                    && t.getPlaybackHeadPosition() < off) {
                scheduleHighlights(it, t);
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
        }
        try {
            t.stop();
        } catch (Throwable e) {
        }
        t.release();
        if (track == t) {
            track = null;
        }
    }

    private final java.util.List<String> firedRanges = new java.util.ArrayList<String>();

    /** fires the highlight ranges whose audio frame the playhead has reached */
    private void scheduleHighlights(Item it, AudioTrack t) {
        if (!AppState.get().ttsWordHighlight || it.ranges.isEmpty()) {
            return;
        }
        int head = (int) Math.min(t.getPlaybackHeadPosition(), Integer.MAX_VALUE);
        synchronized (it.ranges) {
            for (int i = 0; i < it.ranges.size(); i++) {
                int[] r = it.ranges.get(i);
                if (r[1] > head || r[1] < 0) {
                    continue;
                }
                String key = it.utteranceId + "@" + r[0];
                if (firedRanges.contains(key)) {
                    continue;
                }
                firedRanges.add(key);
                if (firedRanges.size() > 512) {
                    firedRanges.remove(0);
                }
                fireRange(it.utteranceId, r[0], r[2], r[1]);
            }
        }
    }

    private void fireRange(String utteranceId, int start, int end, int frame) {
        UtteranceProgressListener l = savedListener;
        if (l != null) {
            try {
                l.onRangeStart(utteranceId, start, end, frame);
            } catch (Throwable e) {
                LOG.e(e);
            }
        }
    }

    private void fireStart(String utteranceId) {
        TTSEngine.get().fireKokoroStart(utteranceId);
    }

    private void fireDone(String utteranceId) {
        TTSEngine.get().fireKokoroDone(utteranceId);
    }

    private void toastSlow() {
        try {
            android.widget.Toast.makeText(LibreraApp.context, R.string.tts_voice_enhance_slow,
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
        }
    }

    private int lastRate = 22050;

    /** minimal RIFF/WAVE PCM16 reader (mono or first channel) */
    private short[] decodeWav(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] header = new byte[4096];
            int n = in.read(header);
            if (n < 44 || header[0] != 'R' || header[1] != 'I') {
                in.close();
                return null;
            }
            int rate = 22050;
            int channels = 1;
            int bits = 16;
            int pos = 12;
            boolean foundData = false;
            // walk chunks
            while (pos + 8 <= n) {
                String id = new String(header, pos, 4, "US-ASCII");
                int size = (header[pos + 4] & 0xFF) | (header[pos + 5] & 0xFF) << 8
                        | (header[pos + 6] & 0xFF) << 16 | (header[pos + 7] & 0xFF) << 24;
                if ("fmt ".equals(id)) {
                    channels = (header[pos + 10] & 0xFF) | (header[pos + 11] & 0xFF) << 8;
                    rate = (header[pos + 12] & 0xFF) | (header[pos + 13] & 0xFF) << 8
                            | (header[pos + 14] & 0xFF) << 16 | (header[pos + 15] & 0xFF) << 24;
                    bits = (header[pos + 22] & 0xFF) | (header[pos + 23] & 0xFF) << 8;
                } else if ("data".equals(id)) {
                    int available = n - (pos + 8);
                    int take = Math.min(size, available);
                    data.write(header, pos + 8, take);
                    int remaining = size - take;
                    byte[] buf = new byte[65536];
                    int r;
                    while (remaining > 0 && (r = in.read(buf, 0, Math.min(buf.length, remaining))) > 0) {
                        data.write(buf, 0, r);
                        remaining -= r;
                    }
                    foundData = true;
                    break;
                }
                pos += 8 + size + (size % 2);
                if (size < 0) {
                    break;
                }
            }
            if (!foundData) {
                // no data chunk in the first page: buffer everything as a fallback
                byte[] extra = new byte[65536];
                int r;
                while ((r = in.read(extra)) > 0) {
                    data.write(extra, 0, r);
                }
            }
            in.close();
            lastRate = rate;
            byte[] bytes = data.toByteArray();
            if (bits != 16) {
                return null;
            }
            int frames = bytes.length / 2 / Math.max(1, channels);
            short[] out = new short[frames];
            for (int i = 0; i < frames; i++) {
                int lo = bytes[i * 2 * channels] & 0xFF;
                int hi = bytes[i * 2 * channels + 1] & 0xFF; // first channel
                out[i] = (short) ((hi << 8) | lo);
            }
            return out;
        } catch (Throwable e) {
            LOG.e(e);
            return null;
        }
    }
}
