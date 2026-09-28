package com.foobnix.ai;

/**
 * Built-in voices of the shipped Kokoro-7M-Distill model
 * (oddadmix/Kokoro-7M-Distill via the sherpa-onnx bundle). The distilled 7M
 * student ships a single speaker: af_msa (sid 0). Keep the table-driven API
 * so the UI code stays unchanged.
 */
public class KokoroVoices {
    public static final String[] CODES = {
            "af_msa"
    };

    public static final String[] DISPLAY = {
            "MSA (en-US Female)"
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
