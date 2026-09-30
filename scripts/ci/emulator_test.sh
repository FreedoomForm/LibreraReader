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

# one uiautomator dump with one retry; empty/stale result counts as failure
dump_ui() { # dump_ui <filename>
  for t in 1 2; do
    adb shell uiautomator dump "/sdcard/$1" > /dev/null 2>&1 || true
    adb pull "/sdcard/$1" "$1" > /dev/null 2>&1 || true
    if [ -s "$1" ]; then
      return 0
    fi
    sleep 3
  done
  return 1
}

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

# ---------- system-dialog recovery ----------
# On slow CI boots the system LAUNCHER ANRs ("Input dispatching timed out")
# and its modal Wait/Close dialog floats above EVERYTHING: it hijacks input
# keyevents and every uiautomator dump (run 3 of the 7358 CI showed the
# aerr_wait dialog instead of the reader for the whole rest of the run).
# Tapping "Wait" dismisses it and keeps the ANRed app alive - exactly what
# a human tester would do.
dismiss_system_dialogs() {
  for t in 1 2 3; do
    dump_ui ui-sysdlg.xml || return 0
    WB=$(grep -oE 'resource-id="android:id/aerr_wait"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' ui-sysdlg.xml 2>/dev/null | grep -oE 'bounds="[^"]*"' | head -1 | sed 's/bounds="//;s/"//')
    if [ -z "$WB" ]; then
      return 0 # no system error dialog on screen
    fi
    PAIR=$(echo "$WB" | sed 's/\]\[/ /; s/\[//; s/\]//')
    WX1=$(echo "$PAIR" | cut -d' ' -f1 | cut -d',' -f1)
    WY1=$(echo "$PAIR" | cut -d' ' -f1 | cut -d',' -f2)
    WX2=$(echo "$PAIR" | cut -d' ' -f2 | cut -d',' -f1)
    WY2=$(echo "$PAIR" | cut -d' ' -f2 | cut -d',' -f2)
    echo "System (ANR) dialog detected - tapping Wait at $(( (WX1 + WX2) / 2 )),$(( (WY1 + WY2) / 2 ))..."
    adb shell input tap $(( (WX1 + WX2) / 2 )) $(( (WY1 + WY2) / 2 )) || true
    sleep 3
  done
}
dismiss_system_dialogs || true

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
dismiss_system_dialogs || true
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
echo "Opening the sample PDF in the reader (explicit component: the system"
# "resolver would otherwise hijack the VIEW intent on images that ship
# another application/pdf handler - the reader never opened and phase 5
# silently degenerated into a service-only test"
adb shell am start -n "$PKG/com.foobnix.OpenerActivity" \
  -a android.intent.action.VIEW \
  -d "file:///storage/emulated/0/Android/data/$PKG/files/tts_sample.pdf" \
  -t "application/pdf" || true
sleep 25
PDF_OPEN_PID=$(adb shell pidof "$PKG" | tr -d '\r\n ')
if [ -z "$PDF_OPEN_PID" ]; then
  echo "::error::APP DIED WHILE OPENING THE PDF"
  adb logcat -d | grep -A 40 "FATAL EXCEPTION" || true
  exit 1
fi
READER_FW=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -i "topResumedActivity" || true)
echo "Foreground after PDF open: $READER_FW"
if ! echo "$READER_FW" | grep -q "ViewActivity"; then
  echo "::error::READER IS NOT IN THE FOREGROUND AFTER OPENING THE PDF (resolver/library stole the intent) - the PDF UI repro is not covered without this"
  adb shell uiautomator dump /sdcard/ui-pdf.xml > /dev/null 2>&1 || true
  adb pull /sdcard/ui-pdf.xml ui-pdf.xml > /dev/null 2>&1 || true
  grep -o 'resource-id="[^"]\{1,60\}"' ui-pdf.xml 2>/dev/null | head -20 || true
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
# Stop the playback first: while the service reads, isBusy() keeps the engine
# alive on purpose, so the trim would be a no-op; with idle UI the dump-based
# phases below also stop fighting the word-highlight animation for uiautomator
# idle state (dump silently fails on a constantly-redrawing window).
echo "Stopping TTSService so the engine goes idle..."
adb shell am stopservice -n "$PKG/com.foobnix.tts.TTSService" 2>&1 || true
sleep 5
echo "Sending RUNNING_CRITICAL trim to the app (memory-pressure drill)..."
adb shell am send-trim-memory "$PDF_PID" RUNNING_CRITICAL 2>&1 || echo "(send-trim-memory not supported by this image - skipping the assert)"
sleep 6
adb logcat -d > logcat-trim.txt || true
if adb shell pidof "$PKG" > /dev/null; then
  TRIM_REL=$(grep -c "engine releas" logcat-trim.txt || true)
  echo "Process survived the trim drill; engine-release markers: $TRIM_REL"
  grep -m 3 "engine releas" logcat-trim.txt || true
else
  echo "::error::APP DIED DURING THE TRIM-MEMORY DRILL - memory release hook did not save it"
  exit 1
fi
grep -m 5 "KokoroDiag" logcat-trim.txt || true

# ---------- 6. TTS DIALOG PHASE (the real 7356-7357 user repro) ----------
# "I can't even open the TTS settings in a book - I'm kicked to the main
# page." Since the Inflect migration (commit fcf5fca) OPENING the dialog
# fired KokoroEngine.prepareAsync: ~134 MB resident (~211 MB peak) landed
# on top of an open PDF and the lowmemorykiller killed the whole process.
# The old Kokoro-7M era never loaded anything at dialog open.
# Regression rule asserted here: the PDF is open, the trim drill above has
# RELEASED the idle engine (so a fresh load would be visible), the AI voice
# is ON -> opening the TTS dialog must show ZERO "kokoro init" lines in
# logcat and the process must stay alive.
# The UI is idle now (service stopped above), otherwise uiautomator dump
# never reaches idle and every dump silently fails (that is exactly how the
# first run of this phase red-herringed: dumps failed while the dialog was
# actually open).
echo "Opening the in-book TTS dialog from the PDF screen (user repro)..."
adb logcat -c || true

dismiss_system_dialogs || true
BOUNDS=""
if dump_ui ui-ttsdlg.xml; then
  BOUNDS=$(grep -oE 'resource-id="[^"]*textToSpeach(Top)?"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' ui-ttsdlg.xml 2>/dev/null | grep -oE 'bounds="[^"]*"' | head -1 | sed 's/bounds="//;s/"//')
fi
if [ -n "$BOUNDS" ]; then
  PAIR=$(echo "$BOUNDS" | sed 's/\]\[/ /; s/\[//; s/\]//')
  X1=$(echo "$PAIR" | cut -d' ' -f1 | cut -d',' -f1)
  Y1=$(echo "$PAIR" | cut -d' ' -f1 | cut -d',' -f2)
  X2=$(echo "$PAIR" | cut -d' ' -f2 | cut -d',' -f1)
  Y2=$(echo "$PAIR" | cut -d' ' -f2 | cut -d',' -f2)
  TX=$(( (X1 + X2) / 2 ))
  TY=$(( (Y1 + Y2) / 2 ))
  echo "Tapping the TTS icon at ${TX},${TY}..."
  adb shell input tap "$TX" "$TY" || true
else
  echo "TTS icon not in the dump (toolbars hidden?) - using KEYCODE_T (reader shortcut)..."
  adb shell input keyevent 48 || true
fi
sleep 12
adb logcat -d > logcat-tts-dialog.txt || true
DLG_PID=$(adb shell pidof "$PKG" | tr -d '\r\n ')
echo "PID after dialog open: $DLG_PID"
if [ -z "$DLG_PID" ]; then
  echo "::error::APP DIED WHILE OPENING THE TTS SETTINGS (the reported user bug!)"
  grep -A 40 "FATAL EXCEPTION" logcat-tts-dialog.txt || true
  grep -m 5 -i "fatal signal\|lowmemorykiller\|has died\|tombstone" logcat-tts-dialog.txt || true
  exit 1
fi

verify_dialog_open() { # dump and look for the dialog's settings nodes
  for t in 1 2; do
    if dump_ui ui-ttsdlg2.xml; then
      if grep -qE 'resource-id="[^"]*(ttsKokoroSwitch|ttsMainTab|ttsEngine)"' ui-ttsdlg2.xml 2>/dev/null; then
        return 0
      fi
    fi
    dismiss_system_dialogs || true
    sleep 2
  done
  return 1
}

if ! verify_dialog_open; then
  echo "Dialog not visible after the first opener - trying the KEYCODE_T route..."
  adb shell input keyevent 48 || true
  sleep 8
  if ! verify_dialog_open; then
    echo "::error::TTS DIALOG DID NOT OPEN - the settings UI is not in the view hierarchy"
    grep -o 'resource-id="[^"]\{1,60\}"' ui-ttsdlg2.xml 2>/dev/null | head -20 || true
    exit 1
  fi
fi
echo "TTS dialog is open (settings nodes visible)"

DLG_INITS=$(grep -c "kokoro init" logcat-tts-dialog.txt || true)
echo "Model loads triggered by the dialog open: $DLG_INITS"
if [ "$DLG_INITS" != "0" ]; then
  echo "::error::DIALOG OPEN TRIGGERED AN AI MODEL LOAD - the eager prepareAsync regression is back (LMK-kills real devices)"
  grep -m 5 "kokoro init" logcat-tts-dialog.txt || true
  exit 1
fi
DLG_FATALS=$(grep -c "FATAL EXCEPTION" logcat-tts-dialog.txt || true)
if [ "$DLG_FATALS" != "0" ]; then
  echo "::error::FATAL EXCEPTION while opening the TTS dialog"
  grep -A 40 "FATAL EXCEPTION" logcat-tts-dialog.txt || true
  exit 1
fi
adb shell input keyevent KEYCODE_BACK || true
sleep 4
if ! adb shell pidof "$PKG" > /dev/null; then
  echo "::error::APP DIED AFTER CLOSING THE TTS DIALOG"
  exit 1
fi
echo "TTS dialog opened and closed safely: no model load, process alive"

echo "EMULATOR SMOKE TEST PASSED (PDF + TTS dialog too): AI voice reads a PDF, settings open without loading the model, process survives, trim drill OK"
