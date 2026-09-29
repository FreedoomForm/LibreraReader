package com.foobnix.ai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** off-device checks for the audible voice polish chain */
public class VoicePolishTest {
    private static final int SR = 22050;

    private static short[] tone(double freq, double amp, int ms) {
        int n = SR * ms / 1000;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            out[i] = (short) Math.round(Math.sin(2 * Math.PI * freq * i / SR) * amp * 32767.0);
        }
        return out;
    }

    private static double rms(short[] x) {
        double acc = 0;
        for (short v : x) {
            double d = v / 32768.0;
            acc += d * d;
        }
        return Math.sqrt(acc / Math.max(1, x.length));
    }

    @Test public void lengthPreserved() {
        short[] in = tone(200, 0.05, 500);
        short[] out = VoicePolish.apply(in, SR);
        assertEquals(in.length, out.length);
    }

    @Test public void quietVoiceGetsLouderNearTarget() {
        short[] in = tone(200, 0.05, 1000);
        double before = rms(in);
        short[] out = VoicePolish.apply(in, SR);
        double after = rms(out);
        assertTrue("before=" + before + " after=" + after, after > before * 2.0);
        // -17 dBFS target -> 0.141; the compressor leaves a quiet tone untouched,
        // so the static gain lands the RMS close to the target
        assertTrue("after=" + after, after > 0.10 && after < 0.17);
    }

    @Test public void loudVoiceStaysBelowCeiling() {
        short[] in = tone(150, 0.95, 1000);
        short[] out = VoicePolish.apply(in, SR);
        int peak = 0;
        for (short v : out) {
            peak = Math.max(peak, Math.abs(v));
        }
        assertTrue("peak=" + peak / 32768.0, peak / 32768.0 <= VoicePolish.CEILING + 1e-3);
    }

    @Test public void silenceStaysSilent() {
        short[] in = new short[SR / 2];
        short[] out = VoicePolish.apply(in, SR);
        assertArrayEquals(in, out);
    }

    @Test public void badArgsReturnInput() {
        short[] x = new short[]{1, -1, 5};
        assertSame(x, VoicePolish.apply(null, SR));
        assertSame(x, VoicePolish.apply(new short[0], SR));
        assertSame(x, VoicePolish.apply(x, 0));
    }
}
