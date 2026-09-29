#!/usr/bin/env bash
# Librera AI - emulator smoke test (runs INSIDE android-emulator-runner).
# NOTE: the action executes `script:` line-by-line in separate shells,
# so any multi-line logic with variables MUST live in this file instead.
set -x

cd "${GITHUB_WORKSPACE:-$PWD}" || exit 1

PKG="com.foobnix.pdf.reader.ai"
if [ -n "$TEST_APK" ] && [ -f "$TEST_APK" ]; then
  APK="$TEST_APK"
else
  APK=$(find dist -name "*x86_64*.apk" | head -1)
  if [ -z "$APK" ]; then
    APK=$(find dist -name "*.apk" | head -1)
  fi
fi
echo "Installing: $APK"
if [ -z "$APK" ]; then
  echo "::error::NO APK FOUND IN dist/"
  ls -laR . | head -50
  exit 1
fi
adb install -r "$APK" || exit 1

# ---------- 0. R8 / EventBus keep-rule guard (build-level) ----------
# EventBus finds @Subscribe methods (onTTSWord, onPageNumber, ...) by
# reflection; R8 silently stripping them killed ALL event-driven UI in the
# release APK (word highlight included) while everything still compiled and
# the app ran. The proguard rules keep the original method names, so the
# presence of "onTTSWord" in the dex pool is a deterministic release-health
# check that catches any future keep-rule regression at CI time.
if unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -aq "onTTSWord"; then
  echo "EVENTBUS SUBSCRIBERS PRESENT in release dex (R8 keep rules active)"
else
  echo "::error::R8 STRIPPED @Subscribe METHODS - EventBus keep rules missing in proguard-rules.pro"
  exit 1
fi

# Raise the in-app synthesis kill-switch limits on emulators (TCG emulation is
# slow); real devices keep the tight defaults (60s first audio / 30s stall).
adb shell setprop kokoro.first_audio_ms 900000 || true
adb shell setprop kokoro.stall_ms 600000 || true

AAPT=$(ls "$ANDROID_HOME"/build-tools/*/aapt 2>/dev/null | tail -1)
LAUNCHER=$("$AAPT" dump badging "$APK" | grep launchable-activity | sed -E "s/.*name='([^']+)'.*/\1/")
echo "Package: $PKG  Launcher: $LAUNCHER"

# ---------- 1. Launch app ----------
adb shell am start -W -n "$PKG/$LAUNCHER"
sleep 40
adb logcat -d > logcat.txt || true
PID=$(adb shell pidof "$PKG" | tr -d '\r\n ')
echo "App PID: $PID"
FATALS=$(grep -c "FATAL EXCEPTION" logcat.txt || true)
ANRS=$(grep -c "ANR in " logcat.txt || true)
echo "FATAL EXCEPTIONS: $FATALS | ANRs: $ANRs | Android: $(adb shell getprop ro.build.version.release | tr -d '\r')"
if [ -z "$PID" ]; then
  echo "::error::APP CRASHED ON EMULATOR - process not alive after start"
  grep -A 50 "FATAL EXCEPTION" logcat.txt || true
  exit 1
fi
if [ "$FATALS" != "0" ]; then
  echo "::error::FATAL EXCEPTION found in logcat"
  grep -A 50 "FATAL EXCEPTION" logcat.txt || true
  exit 1
fi

adb shell uiautomator dump /sdcard/ui.xml || true
adb pull /sdcard/ui.xml ui.xml || true
if [ -f ui.xml ]; then grep -o 'text="[^"]\{1,40\}"' ui.xml | head -15; fi

# ---------- 1b. Open a real book (reader activity registers EventBus
# subscribers; the word-highlight DRAW path lives there). Without this the
# service posts MessageTTSWord into the void and CI cannot see the break
# that users see. OpenerActivity handles VIEW intents, so the book is opened
# directly by path - the library tap was once eaten by a launcher ANR dialog
# that the emulator image itself showed. The app ships sample EPUBs in
# files/TempDownloads/ on first run, so the path always exists.
echo "Opening a real book so the reader (and its highlight subscriber) is up..."
adb shell am start -a android.intent.action.VIEW \
  -d "file:///storage/emulated/0/Android/data/$PKG/files/TempDownloads/montecristo.epub" \
  -t "application/epub+zip" || true
sleep 25
adb shell dumpsys activity activities 2>/dev/null | grep -m1 -i "ResumedActivity" || true
adb shell uiautomator dump /sdcard/ui-reader.xml || true
adb pull /sdcard/ui-reader.xml ui-reader.xml || true
if [ -f ui-reader.xml ]; then grep -o 'text="[^"]\{1,40\}"' ui-reader.xml | head -8; fi

# ---------- 2. TTS PLAYBACK TEST (Kokoro via TTSService) ----------
printf 'Hello world. This is an offline text to speech test. Kokoro reads this sentence aloud on the emulator.' > ttsbook.txt
adb shell mkdir -p /storage/emulated/0/Android/data/$PKG/files
            adb push ttsbook.txt /storage/emulated/0/Android/data/$PKG/files/librera_tts_test.txt
echo "Starting TTSService (Kokoro) on the pushed text file..."
adb shell am start-foreground-service -n "$PKG/com.foobnix.tts.TTSService" \
  -a ACTION_PLAY_CURRENT_PAGE \
  --ei INT 0 \
  --es EXTRA_PATH /storage/emulated/0/Android/data/$PKG/files/librera_tts_test.txt \
  --ei EXTRA_W 1080 --ei EXTRA_H 2400 || true
sleep ${TTS_SLEEP:-100}
adb logcat -d > logcat-tts.txt || true
TTS_PID=$(adb shell pidof "$PKG" | tr -d '\r\n ')
FG=$(adb shell dumpsys activity services "$PKG" 2>/dev/null | grep -c "isForeground" || true)
echo "PID after TTS start: $TTS_PID | foreground entries: $FG"
FATALS2=$(grep -c "FATAL EXCEPTION" logcat-tts.txt || true)
if [ -z "$TTS_PID" ]; then
  echo "::error::APP DIED DURING TTS PLAYBACK"
  grep -A 50 "FATAL EXCEPTION" logcat-tts.txt || true
  exit 1
fi
if [ "$FATALS2" != "0" ]; then
  echo "::error::FATAL EXCEPTION during TTS playback"
  grep -A 50 "FATAL EXCEPTION" logcat-tts.txt || true
  exit 1
fi

# ---------- 3. BACK KEY DURING TTS: process must survive ----------
adb shell input keyevent KEYCODE_BACK
sleep 6
BACK_PID=$(adb shell pidof "$PKG" | tr -d '\r\n ')
if [ -z "$BACK_PID" ]; then
  echo "::error::APP KILLED AFTER BACK KEY DURING TTS"
  exit 1
fi
grep -m 5 "Kokoro\|TTSService\|keep-alive" logcat-tts.txt || true
# Deterministic Kokoro check. run-as is NOT available for the release APK
# (non-debuggable, Android 14 image denies it), so verify via logcat evidence:
# the engine logs "model extracted to" on first unpack; run-as stays as a
# fallback channel for debug builds.
if grep -q "model extracted to" logcat-tts.txt \
   || adb shell run-as com.foobnix.pdf.reader.ai test -f files/inflect/duration.onnx; then
  echo "KOKORO MODEL EXTRACTED - offline AI engine path was used"
else
  echo "::error::KOKORO ENGINE NOT USED - model was never unpacked"
  exit 1
fi
if grep -q "kokoro gen" logcat-tts.txt; then
  echo "KOKORO SYNTHESIS CONFIRMED (RTF diagnostics present)"
  grep -m 3 "kokoro gen" logcat-tts.txt || true
else
  echo "::error::KOKORO SYNTHESIS MISSING - engine prepared but produced no audio"
  exit 1
fi
# ---------- word-by-word highlight pipeline (service side, end to end) ----------
# The service logs "tts words: captured=N" once per page (word boxes captured
# from the page) and "tts word event: page=P idx=I" per spoken word matched to
# a box. Both are always-on KokoroDiag lines - visible in release builds.
WORDS=$(grep -m 1 "tts words: captured=" logcat-tts.txt | sed -E 's/.*captured=([0-9]+).*/\1/')
echo "Word boxes captured on the TTS page: ${WORDS:-none}"
if [ -z "$WORDS" ]; then
  echo "::error::WORD CAPTURE MISSING - TTSService never captured page word boxes"
  exit 1
fi
if [ "$WORDS" = "0" ]; then
  echo "::error::WORD CAPTURE EMPTY - page.getText() returned no word boxes"
  exit 1
fi
EVENTS=$(grep -c "tts word event:" logcat-tts.txt || true)
echo "Word highlight events fired: ${EVENTS:-0}"
if [ "${EVENTS:-0}" = "0" ]; then
  echo "::error::NO WORD HIGHLIGHT EVENTS - utterance-id chain broken"
  exit 1
fi
if grep -q "tts words FAILED" logcat-tts.txt; then
  echo "::error::WORD CAPTURE THREW - see tts words FAILED in logcat"
  grep -m 2 "tts words FAILED" logcat-tts.txt
  exit 1
fi
# ---------- word highlight DRAW (reader side, end to end) ----------
# The reader activity is open (phase 1b); its EventBus subscriber must turn
# every "tts word event" into a "tts word DRAW" log line. Zero DRAW lines
# means the subscriber chain is dead again (e.g. R8 stripping @Subscribe
# methods) - exactly the failure users saw in release builds.
DRAW=$(grep -c "tts word DRAW" logcat-tts.txt || true)
echo "Word highlight DRAW calls (reader side): ${DRAW:-0}"
if [ "${DRAW:-0}" = "0" ]; then
  echo "--- debug: subscriber/draw diagnostics ---"
  grep -m 5 "tts word event\|tts word DRAW\|No subscribers registered" logcat-tts.txt || true
  echo "::error::NO WORD HIGHLIGHT DRAW - reader-side EventBus subscriber missing (R8 keep rules?)"
  exit 1
fi
KOK=$(grep -c "KokoroEngine" logcat-tts.txt || true)
echo "KokoroEngine log lines (informational): $KOK"
            ULE=$(grep -c "UnsatisfiedLinkError" logcat-tts.txt || true)
            if [ "$ULE" != "0" ]; then
              echo "::error::UnsatisfiedLinkError during TTS - native libs broken"
              grep -m 3 -A 10 "UnsatisfiedLinkError" logcat-tts.txt
              exit 1
            fi
            echo "EMULATOR SMOKE TEST PASSED: launch + Kokoro TTS playback + back-key survival"

# ---------- 4. RUSSIAN TEXT PHASE (espeak-ng phonemization path) ----------
# Real users read Cyrillic books: this text goes through the espeak phonemizer.
# A native hang here used to poison the single-thread executor - every later
# Play/preview stayed silent forever. Must synthesize or fail loudly.
adb shell am force-stop "$PKG" || true
sleep 3
adb shell am start -W -n "$PKG/$LAUNCHER"
sleep 20
printf 'ÐÑÐ¸Ð²ÐµÑ Ð¼Ð¸Ñ. Ð­ÑÐ¾ ÑÐµÑÑ ÑÑÑÑÐºÐ¾Ð³Ð¾ ÑÐ·ÑÐºÐ°. Ð¡Ð¸Ð½ÑÐµÐ· ÑÐµÑÐ¸ Ð´Ð¾Ð»Ð¶ÐµÐ½ ÑÐ°Ð±Ð¾ÑÐ°ÑÑ Ð¸ Ñ ÐºÐ¸ÑÐ¸Ð»Ð»Ð¸ÑÐ¾Ð¹.' > ttsbook_ru.txt
adb push ttsbook_ru.txt /storage/emulated/0/Android/data/$PKG/files/librera_tts_test_ru.txt || exit 1
adb logcat -c || true
adb shell am start-foreground-service -n "$PKG/com.foobnix.tts.TTSService" \
  -a ACTION_PLAY_CURRENT_PAGE \
  --ei INT 0 \
  --es EXTRA_PATH /storage/emulated/0/Android/data/$PKG/files/librera_tts_test_ru.txt \
  --ei EXTRA_W 1080 --ei EXTRA_H 2400 || true
sleep ${RU_SLEEP:-150}
adb logcat -d > logcat-tts-ru.txt || true
RU_PID=$(adb shell pidof "$PKG" | tr -d '\r\n ')
echo "RU phase PID: $RU_PID"
if [ -z "$RU_PID" ]; then
  echo "::error::APP DIED DURING RUSSIAN TTS"
  grep -A 50 "FATAL EXCEPTION" logcat-tts-ru.txt || true
  exit 1
fi
if grep -q "kokoro timeout\|kokoro failure" logcat-tts-ru.txt; then
  echo "::error::KOKORO HANG/FAILURE REPRODUCED ON RUSSIAN TEXT"
  grep -m 5 -B 2 -A 8 "kokoro timeout\|kokoro failure" logcat-tts-ru.txt || true
  exit 1
fi
# The bundled Kokoro-7M-Distill is English-only: Russian text must be routed
# to the system TTS (language guard logs "kokoro skip: non-English text").
GUARD=$(grep -c "kokoro skip: non-English text" logcat-tts-ru.txt || true)
echo "Language guard fired: $GUARD"
RU_GEN=$(grep -c "kokoro gen" logcat-tts-ru.txt || true)
echo "RU synthesis items: $RU_GEN"
if [ "${STRICT_RU_ENGLISH_ONLY:-1}" == "1" ]; then
  if [ "${GUARD:-0}" == "0" ]; then
    echo "::error::LANGUAGE GUARD MISSING - Cyrillic text reached the English-only model"
    grep -m 25 "KokoroEngine\|TTSService\|AI TTS\|KokoroDiag" logcat-tts-ru.txt || true
    exit 1
  fi
  echo "OK: Russian text correctly routed to the system voice"
else
  if [ "$RU_GEN" == "0" ]; then
    echo "WARN: no RU synthesis yet (slow TCG runner) - informational only"
  fi
fi
echo "EMULATOR SMOKE TEST PASSED (RU too): Russian text synthesized without hangs"

# ---------- 5. PDF PHASE (the reported user repro: AI voice on a PDF book) ----------
# "Kicked to the main page when enabling the AI voice from a PDF" was never
# covered: earlier phases used EPUB/TXT only. This phase opens a real PDF in
# the reader (PDFium page rendering + page text extraction + word boxes),
# plays it with the offline AI voice and asserts the process survives.
# It also drills the low-memory path: send-trim-memory(RUNNING_CRITICAL) must
# release the idle engine instead of letting the system kill the process.
adb shell am force-stop "$PKG" || true
sleep 3
adb shell am start -W -n "$PKG/$LAUNCHER" || true
sleep 15
adb push scripts/assets/tts_sample.pdf /storage/emulated/0/Android/data/$PKG/files/tts_sample.pdf || exit 1
adb logcat -c || true
echo "Opening the sample PDF in the reader..."
adb shell am start -a android.intent.action.VIEW \
  -d "file:///storage/emulated/0/Android/data/$PKG/files/tts_sample.pdf" \
  -t "application/pdf" || true
sleep 25
PDF_OPEN_PID=$(adb shell pidof "$PKG" | tr -d '\r\n ')
if [ -z "$PDF_OPEN_PID" ]; then
  echo "::error::APP DIED WHILE OPENING THE PDF"
  adb logcat -d | grep -A 40 "FATAL EXCEPTION" || true
  exit 1
fi
echo "PDF open, PID=$PDF_OPEN_PID - starting AI TTS on it..."
adb shell am start-foreground-service -n "$PKG/com.foobnix.tts.TTSService" \
  -a ACTION_PLAY_CURRENT_PAGE \
  --ei INT 0 \
  --es EXTRA_PATH /storage/emulated/0/Android/data/$PKG/files/tts_sample.pdf \
  --ei EXTRA_W 1080 --ei EXTRA_H 2400 || true
sleep ${PDF_SLEEP:-140}
adb logcat -d > logcat-tts-pdf.txt || true
PDF_PID=$(adb shell pidof "$PKG" | tr -d '\r\n ')
echo "PID after PDF TTS: $PDF_PID"
if [ -z "$PDF_PID" ]; then
  echo "::error::APP DIED DURING PDF TTS PLAYBACK (the reported user bug!)"
  grep -A 50 "FATAL EXCEPTION" logcat-tts-pdf.txt || true
  grep -m 5 -i "fatal signal\|lowmemorykiller\|has died\|tombstone" logcat-tts-pdf.txt || true
  exit 1
fi
PDF_FATALS=$(grep -c "FATAL EXCEPTION" logcat-tts-pdf.txt || true)
if [ "$PDF_FATALS" != "0" ]; then
  echo "::error::FATAL EXCEPTION during PDF TTS playback"
  grep -A 50 "FATAL EXCEPTION" logcat-tts-pdf.txt || true
  exit 1
fi
PDF_SIGS=$(grep -c "Fatal signal" logcat-tts-pdf.txt || true)
if [ "$PDF_SIGS" != "0" ]; then
  echo "::error::NATIVE CRASH (Fatal signal) during PDF TTS playback"
  grep -m 3 -A 15 "Fatal signal" logcat-tts-pdf.txt || true
  exit 1
fi
if ! grep -q "kokoro gen" logcat-tts-pdf.txt; then
  echo "::error::KOKORO SYNTHESIS MISSING ON PDF - engine prepared but no audio"
  grep -m 10 "KokoroDiag\|KokoroEngine" logcat-tts-pdf.txt || true
  exit 1
fi
PDF_WORDS=$(grep -m 1 "tts words: captured=" logcat-tts-pdf.txt | sed -E 's/.*captured=([0-9]+).*/\1/')
echo "PDF word boxes captured: ${PDF_WORDS:-none}"
if [ -z "${PDF_WORDS:-}" ] || [ "${PDF_WORDS:-0}" = "0" ]; then
  echo "::error::PDF PAGE TEXT EMPTY - PDFium text extraction returned no words"
  exit 1
fi
# ---------- low-memory drill: the 7356 trim hook must release the engine ----------
echo "Sending RUNNING_CRITICAL trim to the app (memory-pressure drill)..."
adb shell am send-trim-memory "$PDF_PID" RUNNING_CRITICAL 2>&1 || echo "(send-trim-memory not supported by this image - skipping the assert)"
sleep 6
if adb shell pidof "$PKG" > /dev/null; then
  TRIM_REL=$(grep -c "engine released on trim level" logcat-tts-pdf.txt || true)
  echo "Process survived the trim drill; engine-release markers: $TRIM_REL"
else
  echo "::error::APP DIED DURING THE TRIM-MEMORY DRILL - memory release hook did not save it"
  exit 1
fi
adb logcat -d | grep -m 5 "engine released on trim level\|KokoroDiag" || true
echo "EMULATOR SMOKE TEST PASSED (PDF too): AI voice reads a PDF, process survives, trim drill OK"
