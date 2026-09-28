package com.foobnix.ai;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.foobnix.LibreraApp;
import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppState;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A custom "my voice" profile: a short voice sample recorded in the app or
 * imported from an audio file (MP3/M4A/WAV/...). The sample is decoded and
 * analyzed offline: the median fundamental frequency (F0) of the voiced
 * frames becomes the target of a real-time voice filter that shifts the
 * pitch of the CURRENT engine voice towards the recorded one.
 *
 * This is a lightweight on-device approximation of voice conversion: the
 * dominant perceptual cue of "whose voice it is" is F0, so shifting the
 * active voice's F0 to the recorded F0 (plus picking gender-matching engine
 * voices) changes how the voice sounds without a heavy neural network.
 */
public class VoiceProfile {
    private static final String TAG = "VoiceProfile";
    /** assumed F0 of the active system voice when the filter is applied to it */
    public static final float BASELINE_SYSTEM = 165f;
    /** analysis is limited to the first seconds of the sample */
    private static final int MAX_ANALYSIS_SECONDS = 30;

    /** display name, e.g. "My voice 1" */
    public String label = "";
    /** audio file name inside the voices dir */
    public String file = "";
    /** median fundamental frequency, Hz (0 = analysis failed) */
    public float f0 = 0;
    /** fraction of analyzed frames that were voiced, 0..1 */
    public float voiced = 0;
    /** duration of the analyzed sample */
    public int durationMs = 0;
    public long created = 0;

    public static File voicesDir(final Context c) {
        final File dir = new File(c.getFilesDir(), "tts_voices");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** all saved profiles, oldest first */
    public static List<VoiceProfile> list(final Context c) {
        final List<VoiceProfile> out = new ArrayList<VoiceProfile>();
        final File[] files = voicesDir(c).listFiles();
        if (files == null) {
            return out;
        }
        final List<File> jsons = new ArrayList<File>();
        for (final File f : files) {
            if (f.getName().endsWith(".json")) {
                jsons.add(f);
            }
        }
        Collections.sort(jsons);
        for (final File j : jsons) {
            final VoiceProfile p = load(j);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    public static VoiceProfile loadByName(final Context c, final String jsonName) {
        if (jsonName == null || jsonName.isEmpty()) {
            return null;
        }
        return load(new File(voicesDir(c), jsonName));
    }

    /**
     * The profile selected in AppState (AppState.ttsCustomVoice), or null.
     */
    public static VoiceProfile active() {
        try {
            return loadByName(LibreraApp.context, AppState.get().ttsCustomVoice);
        } catch (final Throwable t) {
            LOG.e(t);
            return null;
        }
    }

    public File audioFile(final Context c) {
        return new File(voicesDir(c), file);
    }

    public File jsonFile(final Context c) {
        return new File(voicesDir(c), file + ".json");
    }

    public void save(final Context c) {
        try {
            final JSONObject o = new JSONObject();
            o.put("label", label);
            o.put("file", file);
            o.put("f0", f0);
            o.put("voiced", voiced);
            o.put("durationMs", durationMs);
            o.put("created", created);
            java.io.FileWriter w = null;
            try {
                w = new java.io.FileWriter(jsonFile(c));
                w.write(o.toString());
            } finally {
                if (w != null) {
                    w.close();
                }
            }
        } catch (final Throwable t) {
            LOG.e(t);
        }
    }

    public static VoiceProfile load(final File json) {
        try {
            final StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = null;
            try {
                r = new java.io.BufferedReader(new java.io.FileReader(json));
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            } finally {
                if (r != null) {
                    r.close();
                }
            }
            final JSONObject o = new JSONObject(sb.toString());
            final VoiceProfile p = new VoiceProfile();
            p.label = o.optString("label", "");
            p.file = o.optString("file", "");
            p.f0 = (float) o.optDouble("f0", 0);
            p.voiced = (float) o.optDouble("voiced", 0);
            p.durationMs = (int) o.optDouble("durationMs", 0);
            p.created = o.optLong("created", 0);
            return p.f0 > 0 ? p : null;
        } catch (final Throwable t) {
            LOG.e(t);
            return null;
        }
    }

    /**
     * Pitch factor for the given baseline: how much the active voice's pitch
     * must be multiplied to land on the recorded F0. Clamped to a sane range.
     */
    public float pitchFactorFor(final float baselineF0) {
        if (f0 <= 0 || baselineF0 <= 0) {
            return 1f;
        }
        return clampFactor(f0 / baselineF0);
    }

    public static float clampFactor(final float f) {
        return Math.max(0.6f, Math.min(1.6f, f));
    }

    /** assumed F0 of a Kokoro voice by its code prefix (af_heart -> female) */
    public static float kokoroBaseline(final String code) {
        if (code != null && code.length() >= 2) {
            final String p = code.substring(0, 2);
            final boolean female = p.endsWith("f");
            return female ? 185f : 118f;
        }
        return BASELINE_SYSTEM;
    }

    /**
     * Pitch factor for the Kokoro engine: the recorded F0 relative to the
     * typical F0 of the currently selected Kokoro voice.
     */
    public static float kokoroFactor(final String profileJson, final String kokoroVoiceCode) {
        final VoiceProfile p = loadByName(LibreraApp.context, profileJson);
        if (p == null) {
            return 1f;
        }
        return p.pitchFactorFor(kokoroBaseline(kokoroVoiceCode));
    }

    /**
     * Pitch factor for the system TextToSpeech: relative to the average
     * androgynous baseline (the real F0 of the engine voice is unknown
     * without a calibration utterance).
     */
    public static float systemFactor(final String profileJson) {
        final VoiceProfile p = loadByName(LibreraApp.context, profileJson);
        if (p == null) {
            return 1f;
        }
        return p.pitchFactorFor(BASELINE_SYSTEM);
    }

    /**
     * Decodes the audio file (any format Android can decode: MP3, M4A, AAC,
     * WAV, OGG...) to mono PCM and estimates the median F0.
     *
     * @throws IllegalArgumentException when the sample is too short/quiet
     */
    public static VoiceProfile analyze(final File src) throws Throwable {
        final byte[][] pcmBytes = {new byte[0]};
        final int[][] fmt = new int[2][1]; // [0]=sampleRate, [1]=channels
        decodeToPcm(src, pcmBytes, fmt);

        final int sampleRate = Math.max(4000, fmt[0][0]);
        final int channels = Math.max(1, fmt[1][0]);
        byte[] raw = pcmBytes[0];
        short[] all = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(all);
        raw = null;
        pcmBytes[0] = null;

        // downmix to mono
        final short[] mono;
        if (channels > 1) {
            final int n = all.length / channels;
            mono = new short[n];
            for (int i = 0; i < n; i++) {
                int s = 0;
                for (int ch = 0; ch < channels; ch++) {
                    s += all[i * channels + ch];
                }
                mono[i] = (short) (s / channels);
            }
        } else {
            mono = all;
        }
        all = null;

        // decimate to <= 16 kHz: F0 sits well below that, and the
        // autocorrelation gets ~2x cheaper per halving of the rate
        final int factor = Math.max(1, (int) Math.ceil(sampleRate / 16000.0));
        final int sr = sampleRate / factor;
        final short[] x = factor > 1 ? decimate(mono, factor) : mono;

        final VoiceProfile p = new VoiceProfile();
        p.durationMs = (int) (x.length * 1000L / Math.max(1, sr));
        analyzeF0(p, x, sr);
        if (p.f0 <= 0 || p.durationMs < 2000) {
            throw new IllegalArgumentException("voice sample too short or silent");
        }
        return p;
    }

    /** generic MediaExtractor + MediaCodec decode to 16-bit PCM bytes */
    private static void decodeToPcm(final File src, final byte[][] outBytes, final int[][] outFmt)
            throws Throwable {
        final MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(src.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            String mime = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                final MediaFormat f = ex.getTrackFormat(i);
                final String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    mime = m;
                    break;
                }
            }
            if (track < 0) {
                throw new IllegalArgumentException("no audio track in " + src.getName());
            }
            int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 21);
            final long maxBytes = (long) MAX_ANALYSIS_SECONDS * sampleRate * 2 * channels;
            boolean inputDone = false;
            boolean outputDone = false;
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (!inputDone) {
                    final int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        final ByteBuffer ib = codec.getInputBuffer(inIdx);
                        final int n = (buf.size() < maxBytes) ? ex.readSampleData(ib, 0) : -1;
                        if (n < 0) {
                            // end of file OR the analysis cap is reached - drain and stop
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                final int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        final ByteBuffer ob = codec.getOutputBuffer(outIdx);
                        if (ob != null) {
                            final byte[] chunk = new byte[info.size];
                            ob.position(info.offset);
                            ob.get(chunk);
                            buf.write(chunk, 0, chunk.length);
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }
            outBytes[0] = buf.toByteArray();
            outFmt[0][0] = sampleRate;
            outFmt[1][0] = channels;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (final Throwable t) { }
                try { codec.release(); } catch (final Throwable t) { }
            }
            try { ex.release(); } catch (final Throwable t) { }
        }
    }

    private static short[] decimate(final short[] in, final int factor) {
        final int n = in.length / factor;
        final short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int s = 0;
            final int base = i * factor;
            for (int k = 0; k < factor; k++) {
                s += in[base + k];
            }
            out[i] = (short) (s / factor);
        }
        return out;
    }

    /**
     * Median F0 over voiced frames: frames are 32 ms with 50% overlap, a
     * frame counts as voiced when its energy clears an adaptive gate and the
     * normalized autocorrelation shows a strong periodic peak in the
     * 60..400 Hz band (with an octave-error guard).
     */
    private static void analyzeF0(final VoiceProfile p, final short[] x, final int sr) {
        final int frame = 512;
        final int hop = 256;
        final int win = 256;
        final int minLag = Math.max(2, sr / 400);
        final int maxLag = Math.min(win - 1, sr / 60);
        if (x.length < frame + maxLag) {
            return;
        }
        final int frames = (x.length - frame - maxLag) / hop;
        if (frames <= 0) {
            return;
        }
        // pass 1: energy gate
        final float[] rms = new float[frames];
        float rmsMax = 0;
        for (int f = 0; f < frames; f++) {
            final int start = f * hop;
            double s = 0;
            for (int i = 0; i < frame; i++) {
                final double v = x[start + i];
                s += v * v;
            }
            rms[f] = (float) Math.sqrt(s / frame);
            if (rms[f] > rmsMax) {
                rmsMax = rms[f];
            }
        }
        final float gate = Math.max(120f, 0.15f * rmsMax);

        final List<Float> f0s = new ArrayList<Float>();
        int voicedFrames = 0;
        for (int f = 0; f < frames; f++) {
            if (rms[f] < gate) {
                continue;
            }
            voicedFrames++;
            final int start = f * hop;
            final int wlen = win + maxLag;
            double mean = 0;
            for (int i = 0; i < wlen; i++) {
                mean += x[start + i];
            }
            mean /= wlen;
            double e0 = 0;
            for (int i = 0; i < win; i++) {
                final double v = x[start + i] - mean;
                e0 += v * v;
            }
            if (e0 <= 0) {
                continue;
            }
            float bestNorm = 0;
            int bestLag = -1;
            for (int lag = minLag; lag <= maxLag; lag++) {
                double r = 0, e1 = 0;
                for (int i = 0; i < win; i++) {
                    final double a = x[start + i] - mean;
                    final double b = x[start + i + lag] - mean;
                    r += a * b;
                    e1 += b * b;
                }
                if (e1 <= 0) {
                    continue;
                }
                final float norm = (float) (r / Math.sqrt(e0 * e1));
                if (norm > bestNorm) {
                    bestNorm = norm;
                    bestLag = lag;
                }
            }
            if (bestLag <= 0 || bestNorm < 0.45f) {
                continue; // unvoiced / noisy frame
            }
            // octave guard: prefer the FIRST lag reaching ~the peak (the
            // strongest peak can sit at a multiple of the true period)
            int lagPick = bestLag;
            for (int lag = minLag; lag < bestLag; lag++) {
                double r = 0, e1 = 0;
                for (int i = 0; i < win; i++) {
                    final double a = x[start + i] - mean;
                    final double b = x[start + i + lag] - mean;
                    r += a * b;
                    e1 += b * b;
                }
                if (e1 <= 0) {
                    continue;
                }
                final float norm = (float) (r / Math.sqrt(e0 * e1));
                if (norm >= 0.85f * bestNorm) {
                    lagPick = lag;
                    break;
                }
            }
            f0s.add((float) sr / lagPick);
        }
        p.voiced = voicedFrames > 0 ? f0s.size() / (float) voicedFrames : 0;
        if (f0s.size() < 10) {
            return; // not enough voiced speech for a reliable estimate
        }
        Collections.sort(f0s);
        p.f0 = f0s.get(f0s.size() / 2);
    }
}
