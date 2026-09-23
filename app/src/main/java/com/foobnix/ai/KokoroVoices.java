package com.foobnix.ai;

/**
 * The 54 built-in Kokoro v1.0 voices, in the exact speaker order of voices.bin
 * (sid 53 = em_santa, per the official sherpa-onnx voice table).
 */
public class KokoroVoices {
    public static final String[] CODES = {
            "af_heart", "af_alloy", "af_aoede", "af_bella", "af_jsmith", "af_kore", "af_nicole",
            "af_nova", "af_river", "af_sarah", "af_sky",
            "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
            "am_puck", "am_santa",
            "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
            "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
            "ef_dora", "em_alex",
            "ff_siwis",
            "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
            "if_sara", "im_nicola",
            "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo",
            "pf_dora", "pm_alex", "pm_santa",
            "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
            "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
            "em_santa"
    };

    public static final String[] DISPLAY = {
            "Heart (en-US Female)", "Alloy (en-US Female)", "Aoede (en-US Female)",
            "Bella (en-US Female)", "JSmith (en-US Female)", "Kore (en-US Female)",
            "Nicole (en-US Female)", "Nova (en-US Female)", "River (en-US Female)",
            "Sarah (en-US Female)", "Sky (en-US Female)",
            "Adam (en-US Male)", "Echo (en-US Male)", "Eric (en-US Male)",
            "Fenrir (en-US Male)", "Liam (en-US Male)", "Michael (en-US Male)",
            "Onyx (en-US Male)", "Puck (en-US Male)", "Santa (en-US Male)",
            "Alice (en-GB Female)", "Emma (en-GB Female)", "Isabella (en-GB Female)",
            "Lily (en-GB Female)",
            "Daniel (en-GB Male)", "Fable (en-GB Male)", "George (en-GB Male)",
            "Lewis (en-GB Male)",
            "Dora (Spanish Female)", "Alex (Spanish Male)",
            "Siwis (French Female)",
            "Alpha (Hindi Female)", "Beta (Hindi Female)", "Omega (Hindi Male)",
            "Psi (Hindi Male)",
            "Sara (Italian Female)", "Nicola (Italian Male)",
            "Alpha (Japanese Female)", "Gongitsune (Japanese Female)",
            "Nezumi (Japanese Female)", "Tebukuro (Japanese Female)", "Kumo (Japanese Male)",
            "Dora (Portuguese Female)", "Alex (Portuguese Male)", "Santa (Portuguese Male)",
            "Xiaobei (Chinese Female)", "Xiaoni (Chinese Female)",
            "Xiaoxiao (Chinese Female)", "Xiaoyi (Chinese Female)",
            "Yunjian (Chinese Male)", "Yunxi (Chinese Male)", "Yunxia (Chinese Male)",
            "Yunyang (Chinese Male)",
            "Santa (Spanish Male)"
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
        String code = codeOf(sid);
        String p = code.length() >= 2 ? code.substring(0, 2) : "af";
        if (p.equals("ef") || p.equals("em")) return "Hola. Esta es mi voz, y leeré tus libros en voz alta.";
        if (p.equals("ff")) return "Bonjour. Voici ma voix, et je lirai vos livres à voix haute.";
        if (p.equals("hf") || p.equals("hm")) return "नमस्ते। यह मेरी आवाज़ है।";
        if (p.equals("if") || p.equals("im")) return "Ciao. Questa è la mia voce, e leggerò i tuoi libri ad alta voce.";
        if (p.equals("jf") || p.equals("jm")) return "こんにちは。これが私の声です。";
        if (p.equals("pf") || p.equals("pm")) return "Olá. Esta é a minha voz, e vou ler os seus livros em voz alta.";
        if (p.equals("zf") || p.equals("zm")) return "你好，这是我的声音，我会为你朗读图书。";
        return "Hello! This is my voice, and I will read your books aloud.";
    }
}
