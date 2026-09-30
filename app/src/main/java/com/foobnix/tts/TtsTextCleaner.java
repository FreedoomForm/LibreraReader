package com.foobnix.tts;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local (offline) smart cleanup of the page text right before it is spoken by
 * the system TTS engine. For every page it takes the whole page text and:
 *
 * <ul>
 * <li>expands abbreviated words to their full spoken form ("т.д." ->
 * "так далее", "т.е." -> "то есть", "Dr." -> "Doctor", "1812 г." ->
 * "1812 года");</li>
 * <li>turns numbers into speech friendly text: thousands split by line breaks
 * are glued back ("1 812" -> "1812"), Roman numerals in century/chapter
 * context become digits ("XVIII век" -> "18 век");</li>
 * <li>removes the words that are not part of the book text: page numbers,
 * watermarks of electronic libraries ("скачать бесплатно", "читать онлайн"),
 * site domains, urls, e-mails, ISBN/UDK lines, copyright lines, citation
 * brackets and emoji.</li>
 * </ul>
 *
 * The pipeline is fully deterministic: no network, no model files, no extra
 * RAM - it is a pure text pass that runs in a few milliseconds per page. It
 * replaces what an on-device LLM (Gemini Nano) cannot legally do for third
 * party apps: AICore is closed to custom prompts and exists only on a few
 * flagship devices.
 *
 * Highlight safety: TTSEngine matches the spoken tokens against the page's
 * word boxes (onRangeStart). When this cleaner removes words from the spoken
 * text, {@link #dropUnspokenWords} removes the same words from the box list,
 * so the sequential matcher never stalls. Abbreviation expansions only make
 * the highlight hold on the previous word for a token or two - the next real
 * word matches and the highlight continues.
 */
public class TtsTextCleaner {

    private static final String TAG = "TtsTextCleaner";

    private TtsTextCleaner() {
    }

    // =====================================================================
    // public API
    // =====================================================================

    /**
     * Cleans one page of TTS text (the text already went through
     * TxtUtils.replaceHTMLforTTS, so ttsPAUSE markers separate the fragments).
     * Never returns a blank string for a non-blank input and never throws.
     */
    public static String clean(final String pageHTML) {
        if (pageHTML == null || pageHTML.isEmpty()) {
            return pageHTML;
        }
        try {
            final String[] fragments = pageHTML.split(TxtUtils.TTS_PAUSE);
            final StringBuilder out = new StringBuilder(pageHTML.length());
            boolean first = true;
            for (final String fragment : fragments) {
                final boolean hadContent = hasLetterOrDigit(fragment);

                // keep the engine's service markers intact (ttsSKIP / ttsSTOP / ttsNEXT)
                String body = fragment;
                final StringBuilder tail = new StringBuilder();
                for (final String marker : new String[]{TxtUtils.TTS_SKIP, TxtUtils.TTS_STOP,
                        TxtUtils.TTS_NEXT}) {
                    int at;
                    while ((at = body.indexOf(marker)) >= 0) {
                        body = body.substring(0, at) + body.substring(at + marker.length());
                        tail.append(' ').append(marker);
                    }
                }

                final String cleaned = cleanFragment(body);
                if (cleaned.isEmpty() && tail.length() == 0) {
                    if (hadContent) {
                        // the fragment was junk (watermark, page number...) - drop
                        // it together with its pause slot
                        continue;
                    }
                    // originally empty fragment: keep the pause structure intact
                }
                if (!first) {
                    out.append(' ').append(TxtUtils.TTS_PAUSE).append(' ');
                }
                out.append(cleaned).append(tail);
                first = false;
            }
            final String result = out.toString().trim();
            return result.isEmpty() ? pageHTML : result;
        } catch (final Throwable t) {
            LOG.e(t);
            return pageHTML;
        }
    }

    /**
     * Removes from the page's word boxes every word that is no longer present
     * in the cleaned spoken text (junk lines removed by {@link #clean}). The
     * highlight aligner walks both streams in parallel - boxes of unspoken
     * words would stall it, so they must go. Safe to call with anything.
     */
    public static void dropUnspokenWords(final List<org.ebookdroid.droids.mupdf.codec.TextWord> words,
                                         final String spokenText) {
        if (words == null || words.isEmpty() || TxtUtils.isEmpty(spokenText)) {
            return;
        }
        try {
            // the bag must tokenize the spoken text exactly like the highlight
            // aligner does: whitespace tokens, then strip punctuation/case
            // ("Жила-была" -> "жилабыла") - otherwise boxes of hyphenated words
            // would be dropped even though the aligner can match them
            final Map<String, Integer> bag = new HashMap<String, Integer>();
            for (final String token : spokenText.split("\\s+")) {
                final String norm = normalize(token);
                if (norm.isEmpty()) {
                    continue;
                }
                final Integer count = bag.get(norm);
                bag.put(norm, count == null ? Integer.valueOf(1) : Integer.valueOf(count + 1));
            }
            final Iterator<org.ebookdroid.droids.mupdf.codec.TextWord> it = words.iterator();
            while (it.hasNext()) {
                final String w = normalize(it.next().getWord());
                if (w.isEmpty()) {
                    continue; // punctuation-only boxes are skipped by the matcher anyway
                }
                final Integer count = bag.get(w);
                if (count == null || count == 0) {
                    it.remove();
                } else {
                    bag.put(w, Integer.valueOf(count - 1));
                }
            }
        } catch (final Throwable t) {
            LOG.e(t); // keep the original boxes on any problem
        }
    }

    // =====================================================================
    // fragment pipeline
    // =====================================================================

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    /** [12] [12-15] [1, 3] citation marks */
    private static final Pattern CITATION = Pattern.compile("\\[\\s*\\d{1,4}(?:\\s*[-–,]\\s*\\d{1,4})*\\s*\\]");
    // NOTE: only \x{...} code point escapes here - raw surrogate escapes inside
    // a character class make java.util.regex match way beyond the intent (it
    // even ate the plain hyphen in "Жила-была")
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}]");
    /** thousands glued by spaces/nbsp: "1 812" -> "1812" */
    private static final Pattern SPLIT_THOUSANDS = Pattern.compile("(?<=\\d)[ \\x{00A0}\\x{2009}\\x{202F}](?=\\d{3}(?!\\d))");
    private static final Pattern MULTI_WS = Pattern.compile("[\\t \\x{00A0}\\x{2009}\\x{202F}]+");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([,.!?;:])");

    private static String cleanFragment(String frag) {
        if (frag == null) {
            return "";
        }
        // 1. inline junk that never belongs to the spoken text
        frag = URL.matcher(frag).replaceAll(" ");
        frag = EMAIL.matcher(frag).replaceAll(" ");
        frag = CITATION.matcher(frag).replaceAll(" ");
        frag = EMOJI.matcher(frag).replaceAll(" ");
        frag = frag.replace('\u00A0', ' ');

        // 2. whole-fragment junk (page numbers, watermarks, footers)
        if (isJunk(frag)) {
            return "";
        }

        // 3. numbers: glue split thousands, roman numerals -> digits
        frag = SPLIT_THOUSANDS.matcher(frag).replaceAll("");
        frag = convertRoman(frag);

        // 4. abbreviation expansions
        for (final Replacement r : REPLACEMENTS) {
            frag = r.apply(frag);
        }

        // 5. whitespace hygiene
        frag = MULTI_WS.matcher(frag).replaceAll(" ");
        frag = SPACE_BEFORE_PUNCT.matcher(frag).replaceAll("$1");
        return frag.trim();
    }

    // =====================================================================
    // junk fragment detection
    // =====================================================================

    /** fragments made of punctuation, symbols and lone digits only ("12", "—", "...") */
    private static final Pattern ONLY_SYMBOLS = Pattern.compile("^[\\p{P}\\p{S}\\s\u00A0\\d]*$");
    /** lowercase roman page number footer ("xviii") */
    private static final Pattern ROMAN_PAGE = Pattern.compile("^[ivxlc]{1,7}$");
    /** classification lines: ISBN 978-5-..., УДК 621.3, ББК ... */
    private static final Pattern CLASSIFICATION = Pattern.compile("^(?:isbn|удк|ббк|udc|bbk)[\\s:.№#].*");
    /** "стр. 12", "страница 12 из 347", "page 12 of 347" */
    private static final Pattern PAGE_LABEL = Pattern.compile("^(?:страница|стр|page)[.\\s]*\\d+(?:[.\\s]*(?:из|of|/)\\s*\\d+)?[.\\s]*$");
    /** a bare site domain as the whole footer line */
    private static final Pattern DOMAIN_LINE = Pattern.compile(
            "^[\\p{L}\\d-]+(?:\\.[\\p{L}\\d-]+)*\\.(?:ru|su|рф|com|net|org|info|biz|online|site|xyz|me|io|ua|by|kz|cz|pl|de|fr)[\\s.]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DOWNLOAD_1 = Pattern.compile("скача|загрузит|download|раздач");
    private static final Pattern DOWNLOAD_2 = Pattern.compile("бесплатно|регистрац|онлайн|без\\s?смс|free");

    private static boolean isJunk(final String s) {
        final String t = s.trim();
        if (t.isEmpty()) {
            return true;
        }
        if (ONLY_SYMBOLS.matcher(t).matches()) {
            return true;
        }
        if (ROMAN_PAGE.matcher(t).matches()) {
            return true;
        }
        final String low = t.toLowerCase(Locale.ROOT);
        if (CLASSIFICATION.matcher(low).matches()) {
            return true;
        }
        if (PAGE_LABEL.matcher(low).matches()) {
            return true;
        }
        if (DOMAIN_LINE.matcher(low).matches()) {
            return true;
        }
        if (low.startsWith("©") || low.startsWith("copyright") || low.startsWith("все права")
                || low.contains("all rights reserved")) {
            return true;
        }
        // watermarks of electronic libraries
        if (low.contains("читать онлайн") || low.contains("правообладател")) {
            return true;
        }
        if (low.contains("электрон") && low.contains("библиотек")) {
            return true;
        }
        if (low.contains("подписк") && low.contains("канал")) {
            return true;
        }
        if (DOWNLOAD_1.matcher(low).find() && DOWNLOAD_2.matcher(low).find()) {
            return true;
        }
        return false;
    }

    // =====================================================================
    // roman numerals (XVIII век -> 18 век)
    // =====================================================================

    /** strong trailing context: a century/chapter word right after the numeral */
    private static final String ROMAN_STRONG =
            "век(?:а|е|ов|ах)?|вв?\\.|столет|тысячел|гл\\.|глав|част|том(?:а|е)?|chapter|part|volume|centur";
    private static final Pattern ROMAN = Pattern.compile(
            "((?:[вВ]|[гГ]лава|[тТ]ом|[чЧ]асть|chapter|Chapter|part|Part|volume|Volume|book|Book)\\s+)?"
                    + "([MDCLXVI]{1,7})\\b"
                    + "(?:\\s*(?=(" + ROMAN_STRONG + "|[,.;:!?]|$)))?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ROMAN_STRONG_P = Pattern.compile("^(?:" + ROMAN_STRONG + ")");
    /** english words made of roman letters ("mix", "civ") - never convert them */
    private static final java.util.Set<String> ROMAN_BLACKLIST = new java.util.HashSet<String>(
            java.util.Arrays.asList("mix", "civ"));

    private static final Pattern VALID_ROMAN = Pattern
            .compile("M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})");

    /**
     * Converts roman numerals to digits when the context says it is a number
     * (century, chapter, part...) and not an english word.
     */
    private static String convertRoman(final String s) {
        final Matcher m = ROMAN.matcher(s);
        if (!m.find()) {
            return s;
        }
        final StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        do {
            final String roman = m.group(2);
            final String rest = s.substring(m.end(2));
            final String low = roman.toLowerCase(Locale.ROOT);
            // the numeral is real when a context word precedes it ("в XVIII",
            // "Глава XII") or follows it ("XVIII век"); a trailing punctuation
            // or the end of the line is a weak context ("Глава XII.")
            final boolean viaPrecede = m.group(1) != null;
            final boolean viaTrailing = m.group(3) != null;
            if (!viaPrecede && !viaTrailing) {
                continue;
            }
            final boolean strong = viaPrecede || ROMAN_STRONG_P.matcher(rest).lookingAt();
            if (!strong && (roman.length() < 2 || ROMAN_BLACKLIST.contains(low))) {
                continue; // a weak-context short numeral or a real english word
            }
            if (!VALID_ROMAN.matcher(roman.toUpperCase(Locale.ROOT)).matches()) {
                continue; // letters of the roman alphabet but not a numeral
            }
            final int value = romanValue(roman);
            if (value <= 0) {
                continue;
            }
            // group(1) is already inside s[start(2)-len..start(2)) - only the
            // numeral itself must be replaced by the value
            out.append(s, last, m.start(2));
            out.append(String.valueOf(value));
            last = m.end(2);
        } while (m.find());
        if (last == 0) {
            return s;
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    private static int romanValue(final String r) {
        int total = 0;
        int prev = 0;
        for (int i = r.length() - 1; i >= 0; i--) {
            final int v;
            switch (Character.toUpperCase(r.charAt(i))) {
                case 'I':
                    v = 1;
                    break;
                case 'V':
                    v = 5;
                    break;
                case 'X':
                    v = 10;
                    break;
                case 'L':
                    v = 50;
                    break;
                case 'C':
                    v = 100;
                    break;
                case 'D':
                    v = 500;
                    break;
                case 'M':
                    v = 1000;
                    break;
                default:
                    return -1;
            }
            if (v < prev) {
                total -= v;
            } else {
                total += v;
            }
            prev = v;
        }
        return total > 0 && total < 5000 ? total : -1;
    }

    // =====================================================================
    // abbreviation expansions
    // =====================================================================

    private static final class Replacement {
        final Pattern pattern;
        final String replacement;

        Replacement(final Pattern pattern, final String replacement) {
            this.pattern = pattern;
            this.replacement = replacement;
        }

        String apply(final String s) {
            return pattern.matcher(s).replaceAll(this.replacement);
        }
    }

    private static final Replacement[] REPLACEMENTS = {
            // ---- russian: the dotted abbreviations are what the speech engine reads worst
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])до\\s?н\\.\\s?э\\."), "до нашей эры"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])н\\.\\s?э\\."), "нашей эры"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])т\\.\\s?е\\."), "то есть"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])т\\.\\s?д\\."), "так далее"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])т\\.\\s?п\\."), "тому подобное"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])т\\.\\s?к\\."), "так как"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])т\\.\\s?н\\."), "так называемый"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])т\\.\\s?о\\."), "таким образом"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])в\\s?т\\.\\s?ч\\."), "в том числе"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])напр\\."), "например"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])и\\s?пр\\."), "и прочее"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])др\\."), "другие"),
            // "см." after a digit is a centimeter, not "see"
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])(?<!\\d\\s)см\\."), "смотри"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}\\d])рис\\.(?=\\s*\\d)"), "рисунок"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}\\d])табл\\.(?=\\s*\\d)"), "таблица"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}\\d])стр\\.(?=\\s*\\d)"), "страница"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}\\d])гл\\.(?=\\s*\\d)"), "глава"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])им\\."), "имени"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])проф\\."), "профессор"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])акад\\."), "академик"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])тов\\."), "товарищ"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}])ул\\."), "улица"),
            // ---- quantities
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}\\d])тыс\\.?"), "тысяч"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}\\d])млн\\.?"), "миллионов"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}\\d])млрд\\.?"), "миллиардов"),
            // ---- years and centuries (roman numerals are already digits here)
            new Replacement(Pattern.compile("(\\d{3,4})\\s?[—–-]\\s?(\\d{3,4})\\s?гг?\\."), "$1 — $2 годов"),
            new Replacement(Pattern.compile("(\\d{3,4})\\s?г\\."), "$1 года"),
            new Replacement(Pattern.compile("(\\d{1,3})\\s?[—–-]\\s?(\\d{1,3})\\s?вв?\\."), "$1 — $2 веков"),
            new Replacement(Pattern.compile("(\\d{1,3})\\s?в\\."), "$1 век"),
            new Replacement(Pattern.compile("(?iu)(?<![\\p{L}\\d.])г\\.\\s(?=[А-ЯЁA-Z])"), "город "),
            new Replacement(Pattern.compile("№\\s*"), "номер "),
            // ---- english
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])Mr\\."), "Mister"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])Mrs\\."), "Missus"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])Ms\\."), "Miss"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])Dr\\."), "Doctor"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])Prof\\."), "Professor"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])vs\\.?"), "versus"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])etc\\."), "et cetera"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])e\\.\\s?g\\."), "for example"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])i\\.\\s?e\\."), "that is"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])fig\\.(?=\\s*\\d)"), "Figure"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])vol\\.(?=\\s*\\d)"), "Volume"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])ch\\.(?=\\s*\\d)"), "Chapter"),
            new Replacement(Pattern.compile("(?<![A-Za-z])[pP]{1,2}\\.(?=\\s*\\d)"), "page"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])no\\.(?=\\s*\\d)"), "number"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])approx\\."), "approximately"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])st\\.(?=\\s*[A-ZА-ЯЁ])"), "Saint"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])st\\."), "street"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])ave\\."), "Avenue"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])blvd\\."), "Boulevard"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])mt\\."), "Mount"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])sept\\."), "September"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])jan\\."), "January"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])feb\\."), "February"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])mar\\."), "March"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])apr\\."), "April"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])jun\\."), "June"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])jul\\."), "July"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])aug\\."), "August"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])sep\\."), "September"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])oct\\."), "October"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])nov\\."), "November"),
            new Replacement(Pattern.compile("(?i)(?<![A-Za-z])dec\\."), "December"),
    };

    // =====================================================================
    // helpers
    // =====================================================================

    private static boolean hasLetterOrDigit(final String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** letters and digits only, lowercased - the same rule TTSEngine uses */
    private static String normalize(final String w) {
        if (w == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            final char ch = w.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }
}
