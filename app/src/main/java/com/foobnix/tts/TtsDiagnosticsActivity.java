package com.foobnix.tts;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.foobnix.ai.KokoroEngine;
import com.foobnix.ai.KokoroVoices;
import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.R;
import com.foobnix.ai.TtsAudio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Self-contained TTS diagnostics screen.
 *
 * Why: on some real phones both the AI (Kokoro) voice and the system fallback
 * can stay silent while the reading timer keeps running, and the release build
 * has LOG compiled out - so a bug report contains nothing useful. This screen
 * runs every stage of the TTS chain in front of the user, prints an on-screen
 * verdict and exports a report (clipboard/share) for the developer.
 */
public class TtsDiagnosticsActivity extends Activity {

    private static final String TAG = "KokoroDiag";
    private static final String SYS_UTTERANCE = "librera-diag-sys";

    private TextView logView;
    private ScrollView scrollView;
    private final StringBuilder report = new StringBuilder();
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.tts_diag_title);
        setContentView(R.layout.activity_tts_diag);
        logView = (TextView) findViewById(R.id.ttsDiagLog);
        scrollView = (ScrollView) findViewById(R.id.ttsDiagScroll);
        logView.setMovementMethod(ScrollingMovementMethod.getInstance());

        // Engine logs (LOG.*) are compiled out on real phones. Turning IS_LOG on
        // for this session makes every later Play attempt visible in logcat and
        // therefore in the report dump below.
        AppsConfig.IS_LOG = true;

        findViewById(R.id.ttsDiagRun).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTest();
            }
        });
        findViewById(R.id.ttsDiagCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyReport();
            }
        });
        findViewById(R.id.ttsDiagShare).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareReport();
            }
        });
        appendLine("Librera TTS diagnostics v1");
        appendLine(getString(R.string.tts_diag_hint));
    }

    private void appendLine(final String s) {
        android.util.Log.i(TAG, s);
        report.append(s).append('\n');
        runOnUiThread(new Runnable() {
            public void run() {
                logView.append(s);
                logView.append("\n");
                scrollView.post(new Runnable() {
                    public void run() {
                        scrollView.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void runTest() {
        if (running) {
            return;
        }
        running = true;
        ((Button) findViewById(R.id.ttsDiagRun)).setEnabled(false);
        report.setLength(0);
        logView.setText("");
        // a still-playing book would fight the diagnostics for the audio device
        try {
            TTSEngine.get().stop();
            appendLine("(playback stopped for the test)");
        } catch (Throwable t) {
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    runAll();
                } catch (Throwable e) {
                    appendLine("UNEXPECTED CRASH IN DIAGNOSTICS: " + e);
                    appendLine(LOG.toString(e));
                } finally {
                    running = false;
                    saveReport();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            ((Button) findViewById(R.id.ttsDiagRun)).setEnabled(true);
                        }
                    });
                }
            }
        }, "tts-diag").start();
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY - the system killed the app for RAM (LMK)";
            case android.app.ApplicationExitInfo.REASON_CRASH:
                return "CRASH - uncaught java exception (see the recorded log below)";
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "CRASH_NATIVE - native code died (abort signal in status)";
            case android.app.ApplicationExitInfo.REASON_ANR:
                return "ANR - the main thread was frozen and the user/system closed the app";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "EXCESSIVE_RESOURCE_USAGE";
            case android.app.ApplicationExitInfo.REASON_USER_REQUESTED:
                return "USER_REQUESTED";
            case android.app.ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED (killed by an external signal)";
            default:
                return "REASON_" + reason;
        }
    }

    private void runAll() {
        appendLine("==== Librera TTS diagnostics ====");
        appendLine("time: " + new java.util.Date());

        // ------------------------------------------------ 0. why did the process die last time
        // "Kicked to the main page" = the OS killed or restarted the process.
        // ApplicationExitInfo (Android 11+) records the EXACT death reason of
        // the previous run - LOW_MEMORY vs CRASH(signal) vs ANR - which turns
        // every future user report into a precise diagnosis.
        appendLine("");
        appendLine("[0/6] LAST PROCESS EXITS (evidence of \"kicked to the main page\")");
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                java.util.List<android.app.ApplicationExitInfo> exits = am
                        .getHistoricalProcessExitReasons(getPackageName(), 0, 6);
                if (exits.isEmpty()) {
                    appendLine("no exit records yet");
                }
                for (android.app.ApplicationExitInfo e : exits) {
                    appendLine("- " + new java.util.Date(e.getTimestamp())
                            + " | " + exitReasonName(e.getReason())
                            + " | status=" + e.getStatus()
                            + ((e.getDescription() == null || e.getDescription().isEmpty())
                               ? "" : " | " + e.getDescription()));
                }
            } else {
                appendLine("exit history needs Android 11+ (this device: SDK " + Build.VERSION.SDK_INT + ")");
            }
        } catch (Throwable e) {
            appendLine("exit history FAILED: " + e);
        }
        File crashLog = new File(getExternalFilesDir(null), "tts_crash.log");
        if (crashLog.exists()) {
            appendLine("---- recorded uncaught exceptions (" + crashLog.getName() + ") ----");
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(crashLog));
                String ln;
                int shown = 0;
                while ((ln = r.readLine()) != null && shown < 40) {
                    appendLine(ln);
                    shown++;
                }
                r.close();
            } catch (Throwable e) {
                appendLine("crash log read FAILED: " + e);
            }
        } else {
            appendLine("no uncaught java exceptions recorded");
        }

        // ------------------------------------------------ 1. device
        appendLine("");
        appendLine("[1/6] DEVICE");
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            appendLine("app: " + getPackageName() + " v" + pi.versionName + " (" + pi.versionCode + ")");
        } catch (Throwable e) {
            appendLine("app info: FAILED " + e);
        }
        appendLine("device: " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        appendLine("device ABIs: " + TextUtils.join(", ", Build.SUPPORTED_ABIS));
        try {
            File nl = new File(getApplicationInfo().nativeLibraryDir);
            String[] kids = nl.list();
            appendLine("native libs shipped in this APK: "
                    + (kids == null ? "?" : TextUtils.join(", ", kids)));
        } catch (Throwable e) {
            appendLine("native lib dir: " + e);
        }
        try {
            StatFs st = new StatFs(getFilesDir().getPath());
            appendLine("free app storage: " + st.getAvailableBytes() / 1048576 + " MB");
        } catch (Throwable e) {
        }
        appendLine("ttsUseKokoro=" + AppState.get().ttsUseKokoro
                + ", voice=" + AppState.get().ttsKokoroVoice
                + ", kokoroFallbackActive=" + TTSEngine.kokoroFallbackActive
                + ", kokoroReady=" + KokoroEngine.get().isReady());

        // ------------------------------------------------ 2. model files
        appendLine("");
        appendLine("[2/6] AI TTS MODEL FILES");
        File dir = new File(getFilesDir(), "inflect");
        if (!dir.exists()) {
            appendLine("model dir MISSING (extracted on the first Play - that is OK)");
        } else {
            appendLine("model dir: " + dir);
            appendLine("duration.onnx: " + fileLen(new File(dir, "duration.onnx")));
            appendLine("decode.onnx: " + fileLen(new File(dir, "decode.onnx")));
            appendLine("lexicon-en.txt: " + fileLen(new File(dir, "lexicon-en.txt")));
            appendLine("espeak-ng-data entries: " + countFiles(new File(dir, "espeak-ng-data")));
            appendLine("dict entries: " + countFiles(new File(dir, "dict")));
        }

        // ------------------------------------------------ 3. kokoro init
        appendLine("");
        appendLine("[3/6] KOKORO ENGINE INIT (10-60 s on the very first run)");
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            KokoroEngine.get().prepareSync();
            appendLine("OK in " + (android.os.SystemClock.elapsedRealtime() - t0)
                    + " ms, sampleRate=" + KokoroEngine.get().getSampleRate());
        } catch (Throwable e) {
            appendLine("FAILED after " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms: " + e);
            appendLine(LOG.toString(e));
            appendLine("VERDICT: the AI voice can never play - THIS is the bug.");
        }

        // ------------------------------------------------ 4. synthesis
        if (KokoroEngine.get().isReady()) {
            appendLine("");
            appendLine("[4/6] AI SYNTHESIS TEST");
            try {
                int sid = KokoroVoices.sidOf(AppState.get().ttsKokoroVoice);
                String text = "Sound check. One, two, three. If you can hear this voice, everything works.";
                long t1 = android.os.SystemClock.elapsedRealtime();
                TtsAudio audio = KokoroEngine.get().generateForDiag(text, sid, 1.0f);
                long ms = android.os.SystemClock.elapsedRealtime() - t1;
                float[] s = audio.getSamples();
                float peak = 0;
                for (float v : s) {
                    float a = Math.abs(v);
                    if (a > peak) {
                        peak = a;
                    }
                }
                appendLine("synth: " + ms + " ms, samples=" + s.length
                        + ", audio=" + String.format("%.1f", s.length / (float) audio.getSampleRate()) + " s"
                        + ", peak=" + String.format("%.4f", peak));
                if (peak < 0.001f) {
                    appendLine("VERDICT: synthesis produces SILENCE (all zeros) - model/sherpa bug!");
                } else {
                    appendLine("synthesis produces REAL sound");
                }
                File wav = new File(getFilesDir(), "kokoro-diag.wav");
                boolean saved = audio.save(wav.getAbsolutePath());
                appendLine("saved wav: " + wav.getAbsolutePath() + " (" + saved + ")");
            } catch (Throwable e) {
                appendLine("synthesis FAILED: " + e);
                appendLine(LOG.toString(e));
            }

            // -------------------------------------------- 5. playback
            appendLine("");
            appendLine("[5/6] AUDIO PLAYBACK TEST - the phrase should SOUND now");
            try {
                int sid = KokoroVoices.sidOf(AppState.get().ttsKokoroVoice);
                TtsAudio audio = KokoroEngine.get()
                        .generateForDiag("Playback check. One, two, three.", sid, 1.0f);
                String res = KokoroEngine.get().playForDiag(audio.getSamples());
                appendLine(res);
                appendLine("Did you HEAR the phrase just now? (yes = audio stack OK)");
            } catch (Throwable e) {
                appendLine("playback test FAILED: " + e);
            }
        } else {
            appendLine("");
            appendLine("[4/6] skipped - engine is not ready");
        }

        // ------------------------------------------------ 6. system tts
        appendLine("");
        appendLine("[6/6] SYSTEM TTS (the fallback voice)");
        systemTtsTest();

        appendLine("");
        appendLine("==== END OF REPORT ====");
        appendLine("Пошлите этот отчёт разработчику: кнопки «Копировать» или «Поделиться».");
    }

    private void systemTtsTest() {
        try {
            List<ResolveInfo> engines = getPackageManager().queryIntentServices(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            StringBuilder names = new StringBuilder();
            for (ResolveInfo i : engines) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(i.serviceInfo == null ? "?" : i.serviceInfo.packageName);
            }
            appendLine("installed TTS engines: " + names);
            String def = Settings.Secure.getString(getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH);
            appendLine("system default engine: " + def);
        } catch (Throwable e) {
            appendLine("engine list FAILED: " + e);
        }

        final CountDownLatch initLatch = new CountDownLatch(1);
        final int[] initStatus = {Integer.MIN_VALUE};
        TextToSpeech sys = null;
        try {
            sys = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    initStatus[0] = status;
                    initLatch.countDown();
                }
            });
            boolean inited = initLatch.await(10, TimeUnit.SECONDS);
            if (!inited) {
                appendLine("system TTS init: TIMEOUT (no callback in 10 s)");
                appendLine("VERDICT: the fallback voice can never play - THIS is the bug.");
            } else if (initStatus[0] != TextToSpeech.SUCCESS) {
                appendLine("system TTS init: FAILED, status=" + initStatus[0]);
                appendLine("VERDICT: the fallback voice can never play - THIS is the bug.");
            } else {
                appendLine("system TTS init: OK, engine=" + sys.getDefaultEngine());
                final CountDownLatch done = new CountDownLatch(1);
                final int[] err = {0};
                sys.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        if (SYS_UTTERANCE.equals(utteranceId)) {
                            done.countDown();
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        if (SYS_UTTERANCE.equals(utteranceId)) {
                            err[0] = 1;
                            done.countDown();
                        }
                    }
                });
                int lang = sys.setLanguage(new Locale("ru", "RU"));
                appendLine("setLanguage(ru-RU): " + lang);
                sys.setSpeechRate(1.0f);
                sys.speak("Проверка системного голоса. Раз, два, три.",
                        TextToSpeech.QUEUE_FLUSH, null, SYS_UTTERANCE);
                boolean spoken = done.await(15, TimeUnit.SECONDS);
                if (!spoken) {
                    appendLine("system speak: TIMEOUT (no onDone/onError in 15 s)");
                } else if (err[0] == 1) {
                    appendLine("system speak: onError - the system voice failed");
                } else {
                    appendLine("system speak: OK (utterance completed) - did you hear it?");
                }
            }
        } catch (Throwable e) {
            appendLine("system TTS test FAILED: " + e);
        } finally {
            try {
                sys.shutdown();
            } catch (Throwable e) {
            }
        }
    }

    private String fileLen(File f) {
        return f.exists() ? f.length() + " bytes" : "MISSING";
    }

    private int countFiles(File dir) {
        if (!dir.exists()) {
            return -1;
        }
        File[] kids = dir.listFiles();
        return kids == null ? -1 : kids.length;
    }

    private File reportFile() {
        return new File(getFilesDir(), "kokoro-diag-report.txt");
    }

    private void saveReport() {
        try {
            PrintWriter w = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(reportFile()), "UTF-8"));
            w.print(report.length() > 0 ? report.toString()
                    : "(report is empty - run the test first)");
            w.close();
        } catch (Throwable e) {
            android.util.Log.e(TAG, "save report", e);
        }
    }

    private void copyReport() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("tts-diag", report.toString()));
            Toast.makeText(this, R.string.tts_diag_copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    private void shareReport() {
        try {
            saveReport();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", reportFile());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "Librera TTS diagnostics");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.tts_diag_share)));
        } catch (Throwable e) {
            LOG.e(e);
            Toast.makeText(this, R.string.msg_unexpected_error, Toast.LENGTH_SHORT).show();
        }
    }
}
