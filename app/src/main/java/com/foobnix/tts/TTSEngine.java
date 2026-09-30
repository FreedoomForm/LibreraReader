package com.foobnix.tts;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.MediaPlayer.OnCompletionListener;
import android.provider.Settings;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.EngineInfo;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.speech.tts.TextToSpeech.OnUtteranceCompletedListener;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.session.MediaSessionCompat;
import android.widget.Toast;

import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.MyMath;
import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.android.utils.Vibro;
import com.foobnix.mobi.parser.IOUtils;
import com.foobnix.mobi.parser.MobiParserIS;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.sys.TempHolder;
import com.github.axet.lamejni.Lame;

import com.foobnix.LibreraApp;

import org.greenrobot.eventbus.EventBus;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class TTSEngine {

    public static final String FINISHED_SIGNAL = "Finished";
    public static final String STOP_SIGNAL = "Stoped";
    public static final String UTTERANCE_ID_DONE = "LirbiReader";
    public static final String WAV = ".wav";
    public static final String MP3 = ".mp3";
    private static final String TAG = "TTSEngine";
    private static TTSEngine INSTANCE = new TTSEngine();
    volatile TextToSpeech ttsEngine;
    volatile MediaPlayer mp;
    volatile UtteranceProgressListener utteranceListener;
    volatile OnUtteranceCompletedListener legacyListener;
    Timer mTimer;
    Object helpObject = new Object();
    HashMap<String, String> map = new HashMap<String, String>();
    HashMap<String, String> mapTemp = new HashMap<String, String>();
    /** char start of every readable token + its flat page word index per
     *  utterance id - used by onRangeStart to highlight the word being
     *  spoken in continuous (not word-by-word) reading with the system voice */
    private final java.util.Map<String, int[][]> ttsRangeIndex =
            new java.util.concurrent.ConcurrentHashMap<String, int[][]>();
    /** true after the system engine reported a successful init */
    private volatile boolean systemTtsReady = false;

    public boolean isInit() {
        return ttsEngine != null;
    }

    OnInitListener listener = new OnInitListener() {

        @Override public void onInit(int status) {
            LOG.d(TAG, "onInit", "SUCCESS", status == TextToSpeech.SUCCESS);
            systemTtsReady = status == TextToSpeech.SUCCESS;
            if (status == TextToSpeech.ERROR) {
                Toast.makeText(LibreraApp.context, R.string.msg_unexpected_error, Toast.LENGTH_LONG)
                     .show();
            }

        }
    };
    private String text = "";

    {
        map.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID_DONE);
    }

    {
        mapTemp.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "Temp");
    }

    public static TTSEngine get() {
        return INSTANCE;
    }

    public static AppBookmark fastTTSBookmakr(DocumentController dc) {
        AppBookmark bookmark = fastTTSBookmakr(dc.getActivity(), dc.getCurrentBook()
                                                                   .getPath(), dc.getCurentPageFirst1(), dc.getPageCount());
        if (bookmark != null) {
            bookmark.pt = dc.getBookmarkText();
            BookmarksData.get().add(bookmark);
        }
        return bookmark;
    }

    public static AppBookmark fastTTSBookmakr(Context c, String bookPath, int page, int pages) {
        LOG.d("fastTTSBookmakr", page, pages);

        if (pages == 0) {
            LOG.d("fastTTSBookmakr skip");
            return null;
        }
        boolean hasBookmark = BookmarksData.get()
                                           .hasBookmark(bookPath, page, pages);

        if (!hasBookmark) {
            final AppBookmark bookmark =
                    new AppBookmark(bookPath, c.getString(R.string.fast_bookmark), MyMath.percent(page, pages));
            BookmarksData.get()
                         .add(bookmark);

            String TEXT = c.getString(R.string.fast_bookmark) + " " + TxtUtils.LONG_DASH1 + " " + c.getString(
                    R.string.page) + " " + page + "";
            Toast.makeText(c, TEXT, Toast.LENGTH_SHORT)
                 .show();
            return bookmark;
        }
        Vibro.vibrate();
        return null;

    }

    public static String engineToString(EngineInfo info) {
        return info.label;
    }

    public void shutdown() {
        LOG.d(TAG, "shutdown");
        synchronized (helpObject) {
            if (ttsEngine != null) {

                ttsEngine.shutdown();
            }
            ttsEngine = null;
        }

    }

    public TextToSpeech getTTS() {
        return getTTS(null);
    }

    public TextToSpeech getTTS(OnInitListener onLisnter) {
        if (LibreraApp.context == null) {
            return null;
        }

        synchronized (helpObject) {

            if (TTSEngine.get()
                         .isMp3() && mp == null) {
                TTSEngine.get()
                         .loadMP3(BookCSS.get()
                                         .mp3BookPathGet());
            }

            if (ttsEngine != null) {
                return ttsEngine;
            }
            if (onLisnter == null) {
                onLisnter = listener;
            }
            // The system default engine can point at a package that is no longer installed
            // (Android then logs "is not allowed to bind to private engine" and stays silent).
            // In that case pick an engine that is actually present instead of inheriting the
            // broken default.
            final String fallback = resolveUsableEngine(LibreraApp.context);
            if (fallback != null) {
                LOG.d(TAG, "default TTS engine unusable, falling back to", fallback);
                ttsEngine = new TextToSpeech(LibreraApp.context, onLisnter, fallback);
            } else {
                ttsEngine = new TextToSpeech(LibreraApp.context, onLisnter);
            }
            // A listener registered BEFORE the engine existed (setUtteranceProgress
            // ListenerCompat skipped the attach because ttsEngine was null) must
            // not be lost: word highlight depends on it from the very first page.
            if (utteranceListener != null) {
                try {
                    ttsEngine.setOnUtteranceProgressListener(utteranceListener);
                } catch (Throwable t) {
                    LOG.e(t);
                }
            }
        }

        return ttsEngine;

    }

    /**
     * @return package of an installed engine to use, or null when the system default is fine.
     */
    static String resolveUsableEngine(Context c) {
        try {
            final List<ResolveInfo> installed = c.getPackageManager()
                    .queryIntentServices(new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            if (installed == null || installed.isEmpty()) {
                return null;
            }

            String defaultEngine = null;
            try {
                defaultEngine = Settings.Secure.getString(c.getContentResolver(),
                        Settings.Secure.TTS_DEFAULT_SYNTH);
            } catch (Exception e) {
                LOG.e(e);
            }

            String google = null;
            String first = null;
            for (ResolveInfo info : installed) {
                if (info == null || info.serviceInfo == null) {
                    continue;
                }
                final String pkg = info.serviceInfo.packageName;
                if (first == null) {
                    first = pkg;
                }
                if ("com.google.android.tts".equals(pkg)) {
                    google = pkg;
                }
                if (pkg.equals(defaultEngine)) {
                    // The configured default is installed - leave the system to use it.
                    return null;
                }
            }
            return google != null ? google : first;
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    public synchronized boolean isShutdown() {
        return ttsEngine == null;
    }

    public void stop() {
        stop(null);
    }

    public boolean isSystemTtsReady() {
        return systemTtsReady && ttsEngine != null;
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) public void stop(MediaSessionCompat mediaSessionCompat) {
        if (mediaSessionCompat != null) {
            mediaSessionCompat.setActive(false);
        }
        if (!AppState.get().allowOtherMusic) {
            try {

                TTSService.abandonAudioFocusCompat();
            } catch (Exception e) {
                LOG.e(e);
            }
        }

        LOG.d(TAG, "stop");
        ttsRangeIndex.clear();
        synchronized (helpObject) {

            if (ttsEngine != null) {
                if (Build.VERSION.SDK_INT >= 15) {
                    ttsEngine.setOnUtteranceProgressListener(null);
                } else {
                    ttsEngine.setOnUtteranceCompletedListener(null);
                }
                ttsEngine.stop();
                EventBus.getDefault()
                        .post(new TtsStatus());
            }
        }
    }

    public void stopDestroy() {
        LOG.d(TAG, "stop");
        TxtUtils.dictHash = "";
        ttsRangeIndex.clear();
        systemTtsReady = false;
        synchronized (helpObject) {
            if (ttsEngine != null) {
                ttsEngine.shutdown();
            }
            ttsEngine = null;
        }
        AppSP.get().lastBookParagraph = 0;
    }

    public TextToSpeech setTTSWithEngine(String engine) {
        shutdown();
        synchronized (helpObject) {
            ttsEngine = new TextToSpeech(LibreraApp.context, listener, engine);
        }
        return ttsEngine;
    }

    /**
     * Stores the service's utterance listener (word highlight + page turns)
     * and forwards it to the system TextToSpeech.
     */
    public void setUtteranceProgressListenerCompat(UtteranceProgressListener l) {
        utteranceListener = l;
        if (ttsEngine != null) {
            if (Build.VERSION.SDK_INT >= 15) {
                ttsEngine.setOnUtteranceProgressListener(l);
            }
        }
    }

    public void setLegacyUtteranceListenerCompat(OnUtteranceCompletedListener l) {
        legacyListener = l;
        if (ttsEngine != null) {
            ttsEngine.setOnUtteranceCompletedListener(l);
        }
    }

    /**
     * Word boxes of the page currently being read (flat, reading order) plus
     * the number of leading tokens carried over from the previous page
     * (preText) - used to highlight the word being spoken in the book.
     */
    private volatile java.util.List<org.ebookdroid.droids.mupdf.codec.TextWord> ttsSourceWords;
    private volatile int ttsSourceOffset = 0;
    /** bumped whenever the source word list is replaced (page change) - stale
     *  highlight events are dropped instead of marking the wrong page */
    private final java.util.concurrent.atomic.AtomicLong ttsWordEpoch =
            new java.util.concurrent.atomic.AtomicLong(0);

    public void setTTSSourceWords(final java.util.List<org.ebookdroid.droids.mupdf.codec.TextWord> words,
                                  final int preTextTokens) {
        this.ttsSourceWords = words;
        this.ttsSourceOffset = Math.max(0, preTextTokens);
        this.ttsWordEpoch.incrementAndGet();
    }

    /** epoch of the current page word list (changes on every page change) */
    public long getTTSWordEpoch() {
        return ttsWordEpoch.get();
    }

    /**
     * @return a copy of the page-word rectangle for the flat index, or null -
     * used to highlight the exact word being spoken without re-reading the
     * page on the UI thread.
     */
    public android.graphics.RectF getTTSWordRect(final int flat) {
        final java.util.List<org.ebookdroid.droids.mupdf.codec.TextWord> src = ttsSourceWords;
        if (src == null || flat < 0 || flat >= src.size()) {
            return null;
        }
        return new android.graphics.RectF(src.get(flat));
    }

    /**
     * Finds the page word matching the spoken token starting at {@code ptr}.
     * Tolerant matching (punctuation/case stripped, hyphenated line breaks
     * joined by MuPdf on one side only). Returns -1 when nothing matches.
     */
    private int matchSourceWord(final String token, final int ptr) {
        final java.util.List<org.ebookdroid.droids.mupdf.codec.TextWord> src = ttsSourceWords;
        if (src == null || ptr < 0 || ptr >= src.size()) {
            return -1;
        }
        final String norm = normalizeWord(token);
        if (norm.isEmpty()) {
            return -1;
        }
        final int limit = Math.min(src.size(), ptr + 4);
        for (int k = ptr; k < limit; k++) {
            String wn = normalizeWord(src.get(k).getWord());
            if (wn.isEmpty()) {
                continue;
            }
            if (wn.equals(norm) || (wn.length() >= 2 && norm.startsWith(wn))
                    || (norm.length() >= 2 && wn.startsWith(norm))) {
                return k;
            }
        }
        return -1;
    }

    private static String normalizeWord(String w) {
        if (w == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            final char ch = w.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }

    /**
     * Number of tokens the word aligner (forEachWordToken) actually consumes
     * from the given text: TTS_PAUSE markers split paragraphs first and
     * punctuation-only tokens are skipped - the exact inverse of WordAlign,
     * so the carried-over (preText) offset never drifts.
     */
    public static int countAlignerTokens(final String text) {
        if (TxtUtils.isEmpty(text)) {
            return 0;
        }
        int n = 0;
        for (final String part : text.split(TxtUtils.TTS_PAUSE)) {
            for (final String w : part.split("\\s+")) {
                if (TxtUtils.isEmpty(w) || normalizeWord(w).isEmpty()) {
                    continue;
                }
                n++;
            }
        }
        return n;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP) public void speek(final String text) {
        synchronized (helpObject) {
            speekLocked(text);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP) private void speekLocked(final String text) {
        this.text = text;
        // utterance ids repeat from page to page - drop the previous page's
        // word offsets so onRangeStart can never highlight a stale word
        ttsRangeIndex.clear();

        if (AppSP.get().tempBookPage != AppSP.get().lastBookPage) {
            AppSP.get().tempBookPage = AppSP.get().lastBookPage;
            AppSP.get().lastBookParagraph = 0;
        }

        LOG.d(TAG, "speek", AppSP.get().lastBookPage, "par", AppSP.get().lastBookParagraph);

        if (TxtUtils.isEmpty(text)) {
            return;
        }
        if (ttsEngine == null) {
            LOG.d("getTTS-status was null");
        } else {
            LOG.d("getTTS-status not null");
        }

        ttsEngine = getTTS(new OnInitListener() {

            @Override public void onInit(int status) {
                LOG.d("getTTS-status", status);
                if (status == TextToSpeech.SUCCESS) {
                    systemTtsReady = true;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                    speek(text);
                } else {
                    // a dead system engine used to fail silently: no sound, no message
                    LOG.e(new IllegalStateException("system TTS init failed: " + status));
                    try {
                        Toast.makeText(LibreraApp.context, R.string.tts_system_init_failed, Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                    }
                }
            }
        });

        if (ttsEngine == null) {
            LOG.d(TAG, "speek: no TTS engine available");
            try {
                Toast.makeText(LibreraApp.context, R.string.tts_system_init_failed, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
            }
            return;
        }

        // selected system voice + custom voice filter pitch (safe to call
        // before the engine finishes init: the params are applied on connect)
        applyVoiceSettings();
        if (AppState.get().ttsSpeed == 0.0f) {
            AppState.get().ttsSpeed = 0.01f;
        }
        ttsEngine.setSpeechRate(AppState.get().ttsSpeed);
        LOG.d(TAG, "Speek s", AppState.get().ttsSpeed);
        LOG.d(TAG, "Speek AppSP.get().lastBookParagraph", AppSP.get().lastBookParagraph);

        if (AppState.get().ttsPauseDuration > 0 && text.contains(TxtUtils.TTS_PAUSE)) {
            String[] parts = text.split(TxtUtils.TTS_PAUSE);
            ttsEngine.playSilence(0l, TextToSpeech.QUEUE_FLUSH, mapTemp);
            final WordAlign sysAlign = new WordAlign(ttsSourceOffset);
            // resuming mid-page: advance the word matcher over the paragraphs
            // before the resume point (they belong to earlier highlights)
            for (int i = 0; i < AppSP.get().lastBookParagraph && i < parts.length; i++) {
                advanceAlign(parts[i] == null ? "" : parts[i], sysAlign);
            }
            for (int i = AppSP.get().lastBookParagraph; i < parts.length; i++) {

                String big = parts[i];
                big = big.trim();

                if (TxtUtils.isNotEmpty(big)) {
                    if (big.length() == 1 && !Character.isLetterOrDigit(big.charAt(0))) {
                        LOG.d("Skip: " + big);
                        continue;

                    }
                    if (big.contains(TxtUtils.TTS_SKIP)) {
                        continue;
                    }

                    if (big.contains(TxtUtils.TTS_STOP)) {
                        HashMap<String, String> mapStop = new HashMap<String, String>();
                        mapStop.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, STOP_SIGNAL);
                        ttsEngine.playSilence(AppState.get().ttsPauseDuration, TextToSpeech.QUEUE_ADD, mapStop);
                        LOG.d("Add stop signal");
                    }
                    if (big.contains(TxtUtils.TTS_NEXT)) {
                        ttsEngine.playSilence(0L, TextToSpeech.QUEUE_ADD, map);
                        LOG.d("next-page signal");
                        break;
                    }

                    HashMap<String, String> mapTemp1 = new HashMap<String, String>();
                    mapTemp1.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, FINISHED_SIGNAL + i);
                    // continuous reading: register the word offsets so
                    // onRangeStart can highlight the word being spoken
                    registerRangeIndex(FINISHED_SIGNAL + i, big, sysAlign);

                    ttsEngine.speak(big, TextToSpeech.QUEUE_ADD, mapTemp1);
                    ttsEngine.playSilence(AppState.get().ttsPauseDuration, TextToSpeech.QUEUE_ADD, mapTemp);
                    LOG.d("pageHTML-parts", i, big);
                }
            }
            ttsEngine.playSilence(0L, TextToSpeech.QUEUE_ADD, map);
        } else {
            String textToPlay = text.replace(TxtUtils.TTS_PAUSE, "");
            LOG.d("pageHTML-parts-single", text);
            final WordAlign sysAlign = new WordAlign(ttsSourceOffset);
            registerRangeIndex(UTTERANCE_ID_DONE, textToPlay, sysAlign);
            ttsEngine.speak(textToPlay, TextToSpeech.QUEUE_FLUSH, map);
        }

    }

    public void speakToFile(final DocumentController controller, final ResultResponse<String> info, int from, int to) {
        File dirFolder = new File(BookCSS.get().ttsSpeakPath, "TTS_" + controller.getCurrentBook()
                                                                                 .getName());
        if (!dirFolder.exists()) {
            dirFolder.mkdirs();
        }
        if (!dirFolder.exists()) {
            info.onResultRecive(controller.getActivity()
                                          .getString(R.string.file_not_found) + " " + dirFolder.getPath());
            return;
        }

        String path = dirFolder.getPath();
        speakToFile(controller, from - 1, path, info, from - 1, to);
    }

    public void speakToFile(final DocumentController controller, final int page, final String folder,
                            final ResultResponse<String> info, int from, int to) {
        LOG.d("speakToFile", page, controller.getPageCount());
        if (ttsEngine == null) {
            LOG.d("TTS is null");
            if (controller != null && controller.getActivity() != null) {
                Toast.makeText(controller.getActivity(), R.string.msg_unexpected_error, Toast.LENGTH_SHORT)
                     .show();
            }
            return;
        }

        ttsEngine.setPitch(AppState.get().ttsPitch);
        ttsEngine.setSpeechRate(AppState.get().ttsSpeed);

        if (page >= to || !TempHolder.isRecordTTS) {
            LOG.d("speakToFile finish", page, controller.getPageCount());
            info.onResultRecive((controller.getActivity()
                                           .getString(R.string.success)));
            TempHolder.isRecordTTS = false;
            return;
        }

        info.onResultRecive((page + 1) + " / " + to);

        DecimalFormat df = new DecimalFormat("0000");
        String pageName = "page-" + df.format(page + 1);
        final String wav = new File(folder, pageName + WAV).getPath();
        String fileText = controller.getTextForPage(page);
        controller.recyclePage(page);

        LOG.d("synthesizeToFile", fileText);
        if (TxtUtils.isEmpty(fileText)) {
            speakToFile(controller, page + 1, folder, info, from, to);
        } else {

            if (fileText.length() > 3950) {
                fileText =
                        TxtUtils.substringSmart(fileText, 3950) + " " + controller.getString(R.string.text_is_too_long);
                LOG.d("Text-too-long", page);
            }

            ttsEngine.synthesizeToFile(fileText, map, wav);

            TTSEngine.get()
                     .getTTS()
                     .setOnUtteranceCompletedListener(new OnUtteranceCompletedListener() {

                         @Override public void onUtteranceCompleted(String utteranceId) {
                             LOG.d("speakToFile onUtteranceCompleted", page, controller.getPageCount());

                             if (AppState.get().isConvertToMp3) {
                                 try {
                                     File file = new File(wav);
                                     Lame lame = new Lame();

                                     InputStream input = new BufferedInputStream(new FileInputStream(file));
                                     input.mark(44);
                                     int bitrate = MobiParserIS.asInt_LITTLE_ENDIAN(input, 24, 4);
                                     LOG.d("bitrate", bitrate);
                                     input.close();
                                     input = new FileInputStream(file);

                                     byte[] bytes = IOUtils.toByteArray(input);

                                     short[] shorts = new short[bytes.length / 2];
                                     ByteBuffer.wrap(bytes)
                                               .order(ByteOrder.LITTLE_ENDIAN)
                                               .asShortBuffer()
                                               .get(shorts);

                                     lame.open(1, bitrate, 128, 4);
                                     byte[] res = lame.encode(shorts, 44, shorts.length);
                                     lame.close();
                                     File toFile = new File(wav.replace(".wav", ".mp3"));
                                     toFile.delete();
                                     IO.copyFile(new ByteArrayInputStream(res), toFile);
                                     input.close();
                                     file.delete();

                                 } catch (Exception e) {
                                     LOG.e(e);
                                 }
                             }
                             //lame.encode();

                             speakToFile(controller, page + 1, folder, info, from, to);
                         }

                     });
        }

    }

    public boolean isTempPausing() {
        if (AppState.get().isEnableAccessibility) {
            return true;
        }
        return mp != null || ttsEngine != null;
    }

    public boolean isPlaying() {
        if (TempHolder.isRecordTTS) {
            return false;
        }
        if (isMp3()) {
            return mp != null && mp.isPlaying();
        }

        synchronized (helpObject) {
            if (ttsEngine == null) {
                return false;
            }
            return ttsEngine != null && ttsEngine.isSpeaking();
        }
    }

    public boolean hasNoEngines() {
        try {
            return ttsEngine != null && (ttsEngine.getEngines() == null || ttsEngine.getEngines()
                                                                                    .size() == 0);
        } catch (Exception e) {
            return true;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP) public String getCurrentLang() {
        try {
            if (Build.VERSION.SDK_INT >= 21 && ttsEngine != null && ttsEngine.getDefaultVoice() != null && ttsEngine.getDefaultVoice()
                                                                                                                    .getLocale() != null) {
                return ttsEngine.getDefaultVoice()
                                .getLocale()
                                .getDisplayLanguage();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return "---";
    }

    public int getEngineCount() {
        try {
            if (ttsEngine == null || ttsEngine.getEngines() == null) {
                return -1;
            }

            return ttsEngine.getEngines()
                            .size();
        } catch (Exception e) {
            LOG.e(e);
        }
        return 0;
    }

    public String getCurrentEngineName() {
        try {
            if (ttsEngine != null) {
                String enginePackage = ttsEngine.getDefaultEngine();
                List<EngineInfo> engines = ttsEngine.getEngines();
                for (final EngineInfo eInfo : engines) {
                    if (eInfo.name.equals(enginePackage)) {
                        return engineToString(eInfo);
                    }
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return "---";
    }

    public void loadMP3(String ttsPlayMp3Path) {
        loadMP3(ttsPlayMp3Path, false);
    }

    public void loadMP3(String ttsPlayMp3Path, final boolean play) {
        LOG.d("loadMP3-", ttsPlayMp3Path);
        if (TxtUtils.isEmpty(ttsPlayMp3Path) || !new File(ttsPlayMp3Path).isFile()) {
            LOG.d("loadMP3-skip mp3");
            return;
        }
        try {
            mp3Destroy();
            mp = new MediaPlayer();
            mp.setDataSource(ttsPlayMp3Path);
            mp.prepare();
            mp.setOnCompletionListener(new OnCompletionListener() {

                @Override public void onCompletion(MediaPlayer mp) {
                    mp.pause();
                }
            });
            if (play) {
                mp.start();
            }

            mTimer = new Timer();

            mTimer.schedule(new TimerTask() {

                @Override public void run() {
                    AppState.get().mp3seek = mp.getCurrentPosition();
                    //LOG.d("Run timer-task");
                    EventBus.getDefault()
                            .post(new TtsStatus());
                }

                ;
            }, 1000, 1000);

        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public MediaPlayer getMP() {
        return mp;
    }

    public void mp3Destroy() {
        if (mp != null) {
            mp.stop();
            mp.reset();
            mp = null;
            if (mTimer != null) {
                mTimer.purge();
                mTimer.cancel();
                mTimer = null;
            }
        }
        LOG.d("mp3Desproy");
    }

    public void mp3Next() {
        int seek = mp.getCurrentPosition();
        mp.seekTo(seek + 5 * 1000);
    }

    public void mp3Prev() {
        int seek = mp.getCurrentPosition();
        mp.seekTo(seek - 5 * 1000);
    }

    public boolean isMp3PlayPause() {
        if (isMp3()) {
            if (mp == null) {
                loadMP3(BookCSS.get()
                               .mp3BookPathGet());
            }
            if (mp == null) {
                return false;
            }
            if (mp.isPlaying()) {
                mp.pause();
            } else {
                mp.start();
            }
            TTSNotification.showLast();
            return true;
        }
        return false;
    }

    public void playMp3() {
        if (mp != null) {
            mp.start();
        }
    }

    public void pauseMp3() {
        if (mp != null) {
            mp.pause();
        }
    }

    public boolean isMp3() {
        return TxtUtils.isNotEmpty(BookCSS.get()
                                          .mp3BookPathGet());
    }

    public void seekTo(int i) {
        if (mp != null) {
            mp.seekTo(i);
        }

    }


    /** Rolling state for matching spoken tokens to the page's word boxes. */
    private static final class WordAlign {
        int pagePtr;
        int preLeft;

        WordAlign(int preLeft) {
            this.preLeft = preLeft;
        }
    }

    /** consumer of one readable word token with its flat page index */
    private interface WordSink {
        void accept(final String word, final int flat);
    }

    /**
     * Walks the readable tokens of a paragraph in reading order and feeds each
     * one to the sink together with its flat index in the page's word list
     * (-1 for tokens carried over from the previous page or unmatched).
     */
    private void forEachWordToken(final String paragraph, final WordAlign align, final WordSink sink) {
        for (final String w : paragraph.split("\\s+")) {
            if (TxtUtils.isEmpty(w)) {
                continue;
            }
            if (normalizeWord(w).isEmpty()) {
                // punctuation-only token: the synthesizers produce no audio for
                // it - just skip it
                continue;
            }
            final int flat;
            if (align.preLeft > 0) {
                align.preLeft--;
                flat = -1;
            } else {
                flat = matchSourceWord(w, align.pagePtr);
                if (flat >= 0) {
                    align.pagePtr = flat + 1;
                }
            }
            sink.accept(w, flat);
        }
    }

    /** advances the aligner over every token of the given text (resume support) */
    private void advanceAlign(final String text, final WordAlign align) {
        forEachWordToken(text, align, new WordSink() {
            @Override public void accept(final String word, final int flat) {
            }
        });
    }

    /**
     * Continuous reading with the SYSTEM voice: the word highlight is driven
     * by UtteranceProgressListener.onRangeStart (API 26+, supported by Google
     * TTS). This registers the char offset of every readable token of one
     * utterance together with its flat page word index; onRangeStart then maps
     * the spoken range to the word that is being heard - no separate audio per
     * word, the sentence is spoken continuously.
     */
    private void registerRangeIndex(final String utteranceId, final String spokenText,
                                    final WordAlign align) {
        if (TxtUtils.isEmpty(spokenText)) {
            return;
        }
        final java.util.List<Integer> starts = new java.util.ArrayList<Integer>();
        final java.util.List<Integer> flats = new java.util.ArrayList<Integer>();
        int scan = 0;
        for (final String w : spokenText.split("\\s+")) {
            if (TxtUtils.isEmpty(w)) {
                continue;
            }
            final int at = spokenText.indexOf(w, scan);
            if (at < 0) {
                continue;
            }
            scan = at + w.length();
            if (normalizeWord(w).isEmpty()) {
                // punctuation-only token: produces no audio and no range event
                continue;
            }
            if (align.preLeft > 0) {
                align.preLeft--;
                continue;
            }
            final int flat = matchSourceWord(w, align.pagePtr);
            if (flat >= 0) {
                align.pagePtr = flat + 1;
            }
            starts.add(at);
            flats.add(flat);
        }
        if (!starts.isEmpty()) {
            ttsRangeIndex.put(utteranceId, new int[][]{toIntArray(starts), toIntArray(flats)});
        }
    }

    /**
     * @return the flat page word index of the token that covers the given
     * char start of the utterance (onRangeStart), -1 when nothing matches
     */
    public int flatForRangeStart(final String utteranceId, final int charStart) {
        final int[][] idx = ttsRangeIndex.get(utteranceId);
        if (idx == null) {
            return -1;
        }
        final int[] starts = idx[0];
        final int[] flats = idx[1];
        int lo = 0, hi = starts.length - 1, res = -1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (starts[mid] <= charStart) {
                res = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return res < 0 ? -1 : flats[res];
    }

    /**
     * Applies the selected system voice (AppState.ttsSystemVoice) and the
     * pitch of the custom voice filter. Safe to call before the engine
     * finishes init: TextToSpeech applies the params on connect.
     */
    public void applyVoiceSettings() {
        final TextToSpeech t = ttsEngine;
        if (t == null) {
            return;
        }
        try {
            final String want = AppState.get().ttsSystemVoice;
            if (TxtUtils.isNotEmpty(want) && t.getVoices() != null) {
                for (final android.speech.tts.Voice v : t.getVoices()) {
                    if (v != null && want.equals(v.getName())) {
                        t.setVoice(v);
                        break;
                    }
                }
            }
            t.setPitch(effectiveSystemPitch());
        } catch (final Throwable e) {
            LOG.e(e);
        }
    }

    /** system voice pitch: the user's pitch setting, clamped to the engine range */
    private float effectiveSystemPitch() {
        return Math.max(0.5f, Math.min(2.0f, AppState.get().ttsPitch));
    }

    /**
     * @return the voices of the system engine (may be empty before init),
     * locally synthesized ones first
     */
    public java.util.List<android.speech.tts.Voice> listSystemVoices() {
        final java.util.List<android.speech.tts.Voice> out =
                new java.util.ArrayList<android.speech.tts.Voice>();
        final TextToSpeech t = ttsEngine;
        if (t == null) {
            return out;
        }
        try {
            final java.util.Set<android.speech.tts.Voice> voices = t.getVoices();
            if (voices != null) {
                out.addAll(voices);
            }
            java.util.Collections.sort(out, new java.util.Comparator<android.speech.tts.Voice>() {
                @Override public int compare(final android.speech.tts.Voice a,
                                             final android.speech.tts.Voice b) {
                    final int na = a == null || a.isNetworkConnectionRequired() ? 1 : 0;
                    final int nb = b == null || b.isNetworkConnectionRequired() ? 1 : 0;
                    if (na != nb) {
                        return na - nb;
                    }
                    final String sa = a == null ? "" : String.valueOf(a.getLocale()) + a.getName();
                    final String sb = b == null ? "" : String.valueOf(b.getLocale()) + b.getName();
                    return sa.compareTo(sb);
                }
            });
        } catch (final Throwable e) {
            LOG.e(e);
        }
        return out;
    }

    private static int[] toIntArray(final java.util.List<Integer> list) {
        final int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }


}
