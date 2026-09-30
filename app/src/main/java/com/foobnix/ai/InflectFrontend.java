package com.foobnix.ai;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text frontend for the Inflect-Nano-v2 TTS model (VITS, 24 kHz, en-us).
 * <p>
 * Mirrors the model's own python frontend (owensong/Inflect-Nano-v2-ONNX):
 * text normalization (numbers, money, time, ordinals, acronyms), then IPA
 * assembly from a precomputed espeak-en-us lexicon (174k words, built with
 * the same espeak backend the model was trained on), then keithito symbol
 * ids with VITS blank interleaving. Pure Java, no Android imports - the
 * golden flows are unit-tested off-device and cross-checked against the
 * official espeak pipeline in CI.
 * <p>
 * Out-of-vocabulary words fall back to suffix stripping (s/es/ed/ing/ly)
 * and then to letter-name spelling, so rare book words still produce
 * reasonable audio instead of silence.
 */
public final class InflectFrontend {

    /** keithito symbol table, exact order of runtime/text/symbols.py */
    public static final String SYMBOLS =
            "_;:,.!?\u00a1\u00bf\u2014\u2026\"\u00ab\u00bb\u201c\u201d ABCDEFGHIJKL"
            + "MNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz\u0251\u0250\u0252\u00e6\u0253"
            + "\u0299\u03b2\u0254\u0255\u00e7\u0257\u0256\u00f0\u02a4\u0259\u0258"
            + "\u025a\u025b\u025c\u025d\u025e\u025f\u0284\u0261\u0260\u0262\u029b"
            + "\u0266\u0267\u0127\u0265\u029c\u0268\u026a\u029d\u026d\u026c\u026b"
            + "\u026e\u029f\u0271\u026f\u0270\u014b\u0273\u0272\u0274\u00f8\u0275"
            + "\u0278\u03b8\u0153\u0276\u0298\u0279\u027a\u027e\u027b\u0280\u0281"
            + "\u027d\u0282\u0283\u0288\u02a7\u0289\u028a\u028b\u2c71\u028c\u0263"
            + "\u0264\u028d\u03c7\u028e\u028f\u0291\u0290\u0292\u0294\u02a1\u0295"
            + "\u02a2\u01c0\u01c1\u01c2\u01c3\u02c8\u02cc\u02d0\u02d1\u02bc\u02b4"
            + "\u02b0\u02b1\u02b2\u02b7\u02e0\u02e4\u02de\u2193\u2191\u2192\u2197"
            + "\u2198'\u0329'\u1d7b";

    private static final Map<Character, Integer> SYMBOL_IDS = new HashMap<Character, Integer>();

    static {
        // last occurrence wins - exactly like the python dict the runner builds
        for (int i = 0; i < SYMBOLS.length(); i++) {
            SYMBOL_IDS.put(SYMBOLS.charAt(i), i);
        }
    }

    public static int symbolId(char c) {
        Integer id = SYMBOL_IDS.get(c);
        return id == null ? -1 : id;
    }

    /** IPA names of the 26 letters (letter-spelling fallback for OOV words) */
    private static String[] LETTER_PHONES;

    static {
        // "a".."z" -> espeak letter names (citation form)
        LETTER_PHONES = new String[]{
                "e\u026a", "bi\u02d0", "si\u02d0", "di\u02d0", "i\u02d0", "\u025bf",
                "d\u0292i\u02d0", "e\u026at\u0283", "a\u026a", "d\u0292e\u026a", "ke\u026a",
                "\u025bl", "\u025bm", "\u025bn", "o\u028a", "pi\u02d0", "kju\u02d0",
                "\u0251\u02d0\u0279", "\u025bs", "ti\u02d0", "ju\u02d0", "vi\u02d0",
                "d\u028cblj u\u02d0", "\u025bks", "wa\u026a", "zi\u02d0"
        };
    }

    // ------------------------------------------------------------------ lexicon

    private final Map<String, String> lexicon = new HashMap<String, String>();

    public InflectFrontend(File lexiconFile) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(lexiconFile), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                lexicon.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } finally {
            in.close();
        }
    }

    public int lexiconSize() {
        return lexicon.size();
    }

    // ------------------------------------------------------------------ phonemize

    /**
     * Normalizes and converts a sentence chunk into the phoneme string the
     * model consumes (IPA + punctuation glued to words, spaces between words).
     * Returns null when nothing speakable is left.
     */
    public String phonemize(String text) {
        String norm = normalizeText(text);
        StringBuilder out = new StringBuilder();
        for (String raw : norm.split("\\s+")) {
            if (raw.isEmpty()) {
                continue;
            }
            StringBuilder word = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char ch = raw.charAt(i);
                if (isPunct(ch)) {
                    String phones = lookupWord(word.toString());
                    appendWord(out, phones, word.length() > 0);
                    word.setLength(0);
                    out.append(ch); // punctuation glues directly to the previous word
                } else if (ch == '-' || ch == (char) 0x2013 || ch == (char) 0x2014) {
                    // hyphen acts as a word boundary
                    String phones = lookupWord(word.toString());
                    appendWord(out, phones, true);
                    word.setLength(0);
                } else if (ch == '\'' || ch == (char) 0x2019) {
                    // contractions: don't == dont
                } else if (ch == '"' || ch == (char) 0xAB || ch == (char) 0xBB) {
                    // quotes carry no pronunciation: skipping them keeps the
                    // lexicon lookup clean - a quoted word used to become
                    // "\"word", miss the lexicon and get letter-spelled
                } else {
                    word.append(Character.toLowerCase(ch));
                }
            }
            if (word.length() > 0) {
                String phones = lookupWord(word.toString());
                appendWord(out, phones, true);
            }
        }
        String s = out.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isPunct(char ch) {
        return ch == '.' || ch == ',' || ch == ';' || ch == ':' || ch == '!' || ch == '?';
    }

    private static void appendWord(StringBuilder out, String phones, boolean withSpace) {
        if (phones == null || phones.isEmpty()) {
            return; // unspeakable fragment: skip silently
        }
        if (withSpace && out.length() > 0) {
            out.append(' ');
        }
        out.append(phones);
    }

    /** lexicon lookup with suffix-stripping and letter-spelling fallbacks */
    private String lookupWord(String w) {
        if (w.isEmpty()) {
            return null;
        }
        String p = lexicon.get(w);
        if (p != null) {
            return p;
        }
        // common suffixes (plural / past / gerund / adverb)
        String[] cut = {"s", "es", "ed", "ing", "ly"};
        for (String suf : cut) {
            if (w.length() > suf.length() + 2 && w.endsWith(suf)) {
                String base = w.substring(0, w.length() - suf.length());
                p = lexicon.get(base);
                if (p != null) {
                    return p;
                }
                // doubled consonant: stopped -> stop, running -> run
                if (base.length() > 2) {
                    char last = base.charAt(base.length() - 1);
                    if (last == base.charAt(base.length() - 2) && "bcdfgklmnprstvz".indexOf(last) >= 0) {
                        p = lexicon.get(base.substring(0, base.length() - 1));
                        if (p != null) {
                            return p;
                        }
                    }
                }
                // -ying -> -y (trying -> try), -ied -> -y (carried -> carry)
                if (suf.equals("ed") || suf.equals("ing")) {
                    p = lexicon.get(base + "y");
                    if (p != null) {
                        return p;
                    }
                }
            }
        }
        return spellLetters(w);
    }

    /** Cyrillic (and a few other Latin-extended) letters -> latin translit,
     *  so a foreign word inside mostly-English text still produces audio
     *  instead of vanishing silently (the "some words are not spoken" bug) */
    private static final Map<Character, String> TRANSLIT = new HashMap<Character, String>();

    static {
        final String cyr = "\u0430\u0431\u0432\u0433\u0434\u0435\u0451\u0436\u0437\u0438\u0439\u043a"
                + "\u043b\u043c\u043d\u043e\u043f\u0440\u0441\u0442\u0443\u0444\u0445\u0446"
                + "\u0447\u0448\u0449\u044b\u044d\u044e\u044f\u0456\u0457\u0454\u0491";
        final String[] lat = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k",
                "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts",
                "ch", "sh", "sh", "y", "e", "yu", "ya", "i", "yi", "e", "g"};
        for (int i = 0; i < cyr.length() && i < lat.length; i++) {
            TRANSLIT.put(cyr.charAt(i), lat[i]);
        }
    }

    /** letter-name spelling for out-of-vocabulary words (non-latin letters
     *  are transliterated first, so they are heard as approximated letter
     *  names instead of being dropped) */
    private String spellLetters(String w) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            String spoken = null;
            if (c >= 'a' && c <= 'z') {
                spoken = LETTER_PHONES[c - 'a'];
            } else {
                final String tr = TRANSLIT.get(c);
                if (tr != null) {
                    // speak the transliterated letters' names
                    final StringBuilder names = new StringBuilder();
                    for (int k = 0; k < tr.length(); k++) {
                        final char tc = tr.charAt(k);
                        if (tc >= 'a' && tc <= 'z') {
                            if (names.length() > 0) {
                                names.append(' ');
                            }
                            names.append(LETTER_PHONES[tc - 'a']);
                        }
                    }
                    spoken = names.toString();
                }
            }
            if (spoken != null) {
                if (any) {
                    sb.append(' ');
                }
                sb.append(spoken);
                any = true;
            }
        }
        return any ? sb.toString() : null;
    }

    // ------------------------------------------------------------------ tokens

    /**
     * Phoneme string -> token ids with VITS blank interleaving
     * (x _ x _ ... _ x), exactly like the reference runner.
     */
    public static long[] toTokenIds(String phonemeText) {
        int n = phonemeText.length();
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            int id = symbolId(phonemeText.charAt(i));
            if (id < 0) {
                return null; // unknown symbol: refuse rather than feed garbage
            }
            ids[i] = id;
        }
        long[] withBlanks = new long[n * 2 + 1];
        for (int i = 0; i < n; i++) {
            withBlanks[i * 2 + 1] = ids[i];
        }
        return withBlanks;
    }

    // ------------------------------------------------------------------ chunking

    /** decode peak RAM scales ~linearly with the chunk length (measured on the
     *  real graphs: +156 MB transient for 280 chars vs +85 MB for 140); 140
     *  keeps the engine small enough to coexist with an open PDF - bigger
     *  chunks were pushing the process over the LMK line mid-play */
    private static final int CHUNK_LIMIT = 140;

    /**
     * Hard cap on the PHONEME string length per single decode call.
     *
     * CHUNK_LIMIT caps the TEXT length, but phonemes run ~1.5-2x longer than
     * the source characters (lexicon IPA strings), and toTokenIds interleaves
     * a blank after every phoneme - so a 140-char chunk can still produce
     * 500+ tokens (T≈1300 frames). The CI emulator repro of the native
     * crash (SIGSEGV inside OrtSession.run, registers holding frame indices
     * ~1305) hit EXACTLY such a chunk while a PDF was open: the decode graph's
     * transient allocation scales with T, and at T≈1300 the process hits the
     * native memory limit while PDF bitmaps + the model are resident.
     * 200 phonemes -> <=401 tokens -> T<=~820 keeps the decode peak at the
     * measured ~85-100 MB level regardless of how "phoneme-dense" the text is.
     */
    public static final int PHONEME_LIMIT = 200;

    /**
     * Splits a phoneme string into decode-sized pieces on word boundaries
     * (spaces). Words longer than the limit are cut hard - the edge fade in
     * the engine masks the seam. Never returns empty pieces.
     */
    public static java.util.List<String> splitPhonemes(String phones, int limit) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (phones == null) {
            return out;
        }
        if (phones.length() <= limit) {
            out.add(phones);
            return out;
        }
        int start = 0;
        final int n = phones.length();
        while (start < n) {
            if (n - start <= limit) {
                out.add(phones.substring(start));
                break;
            }
            int end = start + limit;
            int space = phones.lastIndexOf(' ', end);
            if (space <= start) {
                end = start + limit; // one monster word - hard cut
            } else {
                end = space;
            }
            String piece = phones.substring(start, end).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            start = end;
            while (start < n && phones.charAt(start) == ' ') {
                start++;
            }
        }
        return out;
    }

    /** sentence split with the reference runner's length cap and pause rules */
    public static List<String> splitSentences(String text) {
        String norm = text.replaceAll("\\s+", " ").trim();
        List<String> chunks = new ArrayList<String>();
        if (norm.isEmpty()) {
            return chunks;
        }
        String[] sentences = norm.split("(?<=[.!?:;])\\s+");
        if (sentences.length == 0) {
            sentences = new String[]{norm};
        }
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            while (sentence.length() > CHUNK_LIMIT) {
                int limit = Math.min(CHUNK_LIMIT + 1, sentence.length());
                String head = sentence.substring(0, limit);
                int p = Math.max(Math.max(head.lastIndexOf(','), head.lastIndexOf(';')),
                        head.lastIndexOf(':'));
                int splitAt;
                if (p >= CHUNK_LIMIT / 2) {
                    splitAt = p + 1;
                } else {
                    splitAt = sentence.lastIndexOf(' ', CHUNK_LIMIT);
                    if (splitAt < CHUNK_LIMIT / 2) {
                        splitAt = CHUNK_LIMIT;
                    }
                }
                chunks.add(sentence.substring(0, splitAt).trim());
                sentence = sentence.substring(splitAt).trim();
            }
            if (!sentence.isEmpty()) {
                chunks.add(sentence);
            }
        }
        return chunks;
    }

    /** silence inserted between chunks (the reference runner's pacing) */
    public static float boundaryPauseSec(String chunk) {
        String ending = chunk.trim();
        char c = ending.isEmpty() ? ' ' : ending.charAt(ending.length() - 1);
        switch (c) {
            case '?': return 0.28f;
            case '!': return 0.24f;
            case '.': return 0.22f;
            case ';': return 0.16f;
            case ':': return 0.13f;
            case ',': return 0.09f;
            default: return 0.08f;
        }
    }

    // ------------------------------------------------------------------ normalization

    private static final Pattern MONEY = Pattern.compile("\\$(\\d[\\d,]*(?:\\.\\d{1,2})?)");
    private static final Pattern TIME = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\s*([AaPp]\\.?\\s*[Mm]\\.?)?\\b");
    private static final Pattern VERSION = Pattern.compile("\\b\\d+(?:\\.\\d+){2,}\\b");
    private static final Pattern DECIMAL = Pattern.compile("\\b(\\d+)\\.(\\d+)\\b");
    private static final Pattern ORDINAL = Pattern.compile("\\b(\\d+)(st|nd|rd|th)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\b\\d[\\d,]*\\b");
    private static final Pattern ACRONYM = Pattern.compile("\\b[A-Z]{2,}\\b");
    private static final Pattern LETTER_SERIES = Pattern.compile("\\b([A-Z])(?:\\.([A-Z]))+\\.");

    private static final String[] NUM_NAMES = {"zero", "one", "two", "three", "four", "five",
            "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
            "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty",
            "sixty", "seventy", "eighty", "ninety"};
    private static final String[] ORD_NAMES = {"zeroth", "first", "second", "third", "fourth",
            "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth",
            "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth",
            "nineteenth", "twentieth"};
    private static final String[] ORD_TENS = {"", "", "twentieth", "thirtieth", "fortieth",
            "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth"};

    private static String words(int n) {
        if (n < 0) {
            return "minus " + words(-n);
        }
        if (n < 20) {
            return NUM_NAMES[n];
        }
        if (n < 100) {
            return TENS[n / 10] + (n % 10 != 0 ? " " + NUM_NAMES[n % 10] : "");
        }
        if (n < 1000) {
            return NUM_NAMES[n / 100] + " hundred" + (n % 100 != 0 ? " " + words(n % 100) : "");
        }
        if (n < 1000000) {
            return words(n / 1000) + " thousand" + (n % 1000 != 0 ? " " + words(n % 1000) : "");
        }
        return words(n / 1000000) + " million" + (n % 1000000 != 0 ? " " + words(n % 1000000) : "");
    }

    private static String ordinalWords(int n) {
        if (n <= 20) {
            return ORD_NAMES[Math.min(n, ORD_NAMES.length - 1)];
        }
        if (n < 100) {
            int t = n / 10, r = n % 10;
            return r == 0 ? ORD_TENS[t] : TENS[t] + " " + ORD_NAMES[r];
        }
        return words(n) + "th";
    }

    private static String digitWords(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(NUM_NAMES[digits.charAt(i) - '0']);
        }
        return sb.toString();
    }

    private static final Map<String, String> LETTER_NAMES = new HashMap<String, String>();

    static {
        String[] names = {"ay", "bee", "see", "dee", "ee", "eff", "gee", "aitch", "eye",
                "jay", "kay", "ell", "em", "en", "oh", "pee", "cue", "ar", "ess", "tee",
                "you", "vee", "double you", "ex", "why", "zee"};
        for (int i = 0; i < 26; i++) {
            LETTER_NAMES.put(String.valueOf((char) ('A' + i)), names[i]);
        }
    }

    private static String expandMoney(Matcher m) {
        String raw = m.group(1).replace(",", "");
        String dollars = raw;
        String cents = "";
        int dot = raw.indexOf('.');
        if (dot >= 0) {
            dollars = raw.substring(0, dot);
            cents = raw.substring(dot + 1);
        }
        int d;
        try {
            d = Integer.parseInt(dollars);
        } catch (NumberFormatException e) {
            return m.group(0);
        }
        StringBuilder sb = new StringBuilder(words(d));
        sb.append(d == 1 ? " dollar" : " dollars");
        if (!cents.isEmpty()) {
            while (cents.length() < 2) {
                cents += "0";
            }
            int c;
            try {
                c = Integer.parseInt(cents.substring(0, 2));
            } catch (NumberFormatException e) {
                c = 0;
            }
            if (c > 0) {
                sb.append(" and ").append(words(c)).append(c == 1 ? " cent" : " cents");
            }
        }
        return sb.toString();
    }

    private static String expandTime(Matcher m) {
        int hour;
        int minute;
        try {
            hour = Integer.parseInt(m.group(1));
            minute = Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return m.group(0);
        }
        if (hour > 24 || minute > 59) {
            return m.group(0);
        }
        StringBuilder sb = new StringBuilder(words(hour));
        if (minute == 0) {
            sb.append(" o clock");
        } else if (minute < 10) {
            sb.append(" oh ").append(words(minute));
        } else {
            sb.append(' ').append(words(minute));
        }
        String suffix = m.group(3);
        if (suffix != null) {
            suffix = suffix.toLowerCase().replace(".", "").replace(" ", "");
            for (int i = 0; i < suffix.length(); i++) {
                sb.append(' ').append(LETTER_NAMES.get(String.valueOf(Character.toUpperCase(suffix.charAt(i)))));
            }
        }
        return sb.toString();
    }

    /**
     * The model's normalize_text, ported: punctuation translation, common
     * abbreviations, money, time, versions, decimals, ordinals, integers and
     * ALL-CAPS acronym spelling. Dates and labeled identifiers (rare in
     * books) fall through to the plain number path.
     */
    public static String normalizeText(String text) {
        // NOTE: unicode escape sequences are processed by the javac lexer
        // before tokenization, so a backslash-u escape inside a char literal
        // turns into the raw character - use (char) casts instead
        text = text
                .replace(Character.toString((char) 0x2018), "'")
                .replace(Character.toString((char) 0x2019), "'")
                .replace(Character.toString((char) 0x201C), "\"")
                .replace(Character.toString((char) 0x201D), "\"")
                .replace(Character.toString((char) 0x2013), "-")
                .replace(Character.toString((char) 0x2014), ", ")
                .replace(Character.toString((char) 0x2026), "...")
                .replace("(", ", ").replace(")", ", ")
                .replace("[", ", ").replace("]", ", ")
                .replace("{", ", ").replace("}", ", ");
        text = text.replaceAll("\\s+", " ").trim();

        String[][] abbr = {
                {"Dr.", "doctor"}, {"Mr.", "mister"}, {"Mrs.", "missus"}, {"Ms.", "miss"},
                {"Prof.", "professor"}, {"St.", "saint"}, {"vs.", "versus"},
                {"etc.", "et cetera"}, {"e.g.", "for example"}, {"i.e.", "that is"},
        };
        // word-boundary + case-insensitive, like the reference frontend -
        // a plain replace() would corrupt "first." into "firsaint"
        for (String[] a : abbr) {
            text = java.util.regex.Pattern.compile(
                            "\\b" + java.util.regex.Pattern.quote(a[0]),
                            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                    .matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(a[1]));
        }

        Matcher m = LETTER_SERIES.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder();
            for (int i = 0; i < m.group(0).length(); i++) {
                char c = m.group(0).charAt(i);
                if (Character.isLetter(c)) {
                    if (rep.length() > 0) {
                        rep.append(' ');
                    }
                    rep.append(c);
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        text = sb.toString();

        m = MONEY.matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(expandMoney(m)));
        }
        m.appendTail(sb);
        text = sb.toString();

        m = TIME.matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(expandTime(m)));
        }
        m.appendTail(sb);
        text = sb.toString();

        m = VERSION.matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder();
            for (String part : m.group(0).split("\\.")) {
                if (rep.length() > 0) {
                    rep.append(" point ");
                }
                rep.append(words(Integer.parseInt(part)));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        text = sb.toString();

        m = DECIMAL.matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    words(Integer.parseInt(m.group(1))) + " point " + digitWords(m.group(2))));
        }
        m.appendTail(sb);
        text = sb.toString();

        m = ORDINAL.matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(ordinalWords(Integer.parseInt(m.group(1)))));
        }
        m.appendTail(sb);
        text = sb.toString();

        m = NUMBER.matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            String v = m.group(0).replace(",", "");
            String rep;
            try {
                rep = v.length() >= 5 && !v.startsWith("20") ? digitWords(v) : words(Integer.parseInt(v));
            } catch (NumberFormatException e) {
                rep = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        text = sb.toString();

        m = ACRONYM.matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder();
            for (int i = 0; i < m.group(0).length(); i++) {
                if (rep.length() > 0) {
                    rep.append(' ');
                }
                String letter = LETTER_NAMES.get(m.group(0).substring(i, i + 1));
                rep.append(letter == null ? m.group(0).substring(i, i + 1) : letter);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        text = sb.toString();

        text = text.replaceAll(",(?:\\s*,)+", ",");
        text = text.replaceAll(",\\s*([.!?])", "$1");
        text = text.replaceAll("\\s+([,;:.!?])", "$1");
        text = text.replaceAll("([,;:.!?])(?=\\S)", "$1 ");
        return text.replaceAll("\\s+", " ").trim();
    }
}
