#!/usr/bin/env bash
# Librera - emulator smoke test (runs INSIDE android-emulator-runner).
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

# The release APK must not carry the removed offline AI TTS anymore.
if unzip -l "$APK" 2>/dev/null | grep -aqi "sherpa\|kokoro"; then
  echo "::error::STALE AI TTS ENTRIES IN APK (sherpa/kokoro) - the cleanup regressed"
  exit 1
else
  echo "NO AI TTS ENTRIES IN APK (system TTS only build)"
fi

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

# ---------- 2. TTS PLAYBACK TEST (system Google TTS via TTSService) ----------
printf 'Hello world. This is an offline text to speech test. The system voice reads this sentence aloud on the emulator.' > ttsbook.txt
adb shell mkdir -p /storage/emulated/0/Android/data/$PKG/files
            adb push ttsbook.txt /storage/emulated/0/Android/data/$PKG/files/librera_tts_test.txt
echo "Starting TTSService (system TTS) on the pushed text file..."
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
grep -m 5 "TTSService\|TextToSpeech" logcat-tts.txt || true

# ---------- word highlight pipeline (service side, end to end) ----------
# The service logs "tts words: captured=N" once per page (word boxes captured
# from the page) and "tts word event: page=P idx=I" per spoken word matched to
# a box. Both are always-on TtsDiag lines - visible in release builds. The
# system engine drives the events through UtteranceProgressListener.
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
ULE=$(grep -c "UnsatisfiedLinkError" logcat-tts.txt || true)
if [ "$ULE" != "0" ]; then
  echo "::error::UnsatisfiedLinkError during TTS - native libs broken"
  grep -m 3 -A 10 "UnsatisfiedLinkError" logcat-tts.txt
  exit 1
fi
echo "EMULATOR SMOKE TEST PASSED: launch + system TTS playback + word highlight + back-key survival"
