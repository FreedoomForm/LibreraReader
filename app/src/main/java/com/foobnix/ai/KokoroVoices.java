package com.foobnix.ai;

/**
 * Voice table of the shipped Inflect-Nano-v2 model (single male en-US
 * speaker; the checkpoint has n_speakers=0). The table-driven API is kept
 * from the Kokoro days so the reader/services/UI stay unchanged.
 */
public class KokoroVoices {
    public static final String[] CODES = {
            "inflect_nano_v2"
    };

    public static final String[] DISPLAY = {
            "Inflect Nano v2 (en-US Male)"
    };

    public static int sidOf(String code) {
        if (code != null) {
            for (int i = 0; i < CODES.length; i++) {
                if (CODES[i].equals(code)) return i;
            }
        }
        return 0;
    }

    public static String codeOf(int sid) {
        if (sid < 0 || sid >= CODES.length) return CODES[0];
        return CODES[sid];
    }

    public static String display(String code) {
        return DISPLAY[sidOf(code)];
    }

    public static String previewText(int sid) {
        return "Hello! This is my voice, and I will read your books aloud.";
    }
}
