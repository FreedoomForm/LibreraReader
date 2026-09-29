package com.foobnix.ai;

import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Synthesis result container (samples + rate) with a pure-Java WAV writer.
 * Replaces the sherpa-onnx GeneratedAudio POJO, whose save() was a native
 * call - the app no longer ships the sherpa native library.
 */
public class TtsAudio {
    private final float[] samples;
    private final int sampleRate;

    public TtsAudio(float[] samples, int sampleRate) {
        this.samples = samples;
        this.sampleRate = sampleRate;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public float[] getSamples() {
        return samples;
    }

    /** 16-bit mono PCM RIFF/WAVE writer; returns true on success */
    public boolean save(String filename) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(filename);
            int dataLen = samples.length * 2;
            byte[] header = new byte[44];
            putAscii(header, 0, "RIFF");
            putInt(header, 4, 36 + dataLen);
            putAscii(header, 8, "WAVE");
            putAscii(header, 12, "fmt ");
            putInt(header, 16, 16);
            putShort(header, 20, (short) 1);        // PCM
            putShort(header, 22, (short) 1);        // mono
            putInt(header, 24, sampleRate);
            putInt(header, 28, sampleRate * 2);     // byte rate
            putShort(header, 32, (short) 2);        // block align
            putShort(header, 34, (short) 16);       // bits
            putAscii(header, 36, "data");
            putInt(header, 40, dataLen);
            out.write(header);
            byte[] buf = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                float v = Math.max(-1f, Math.min(1f, samples[i]));
                int s = Math.round(v * 32767f);
                s = Math.max(-32768, Math.min(32767, s));
                buf[i * 2] = (byte) (s & 0xFF);
                buf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            out.write(buf);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable e) {
                }
            }
        }
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int off, short v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
