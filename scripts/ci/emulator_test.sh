#!/usr/bin/env bash
# Librera AI - emulator smoke test (runs INSIDE android-emulator-runner).
# NOTE: the action executes `script:` line-by-line in separate shells,
# so any multi-line logic with variables MUST live in this file instead.
set -x

cd "${GITHUB_WORKSPACE:-$PWD}" || exit 1

PKG="com.foobnix.pdf.reader.ai"
APK=$(find dist -name "*x86_64*.apk" | head -1)
if [ -z "$APK" ]; then
  APK=$(find dist -name "*.apk" | head -1)
fi
echo "Installing: $APK"
if [ -z "$APK" ]; then
  echo "::error::NO APK FOUND IN dist/"
  ls -laR . | head -50
  exit 1
fi
adb install -r "$APK" || exit 1

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

# ---------- 2. TTS PLAYBACK TEST (Kokoro via TTSService) ----------
printf 'Hello world. This is an offline text to speech test. Kokoro reads this sentence aloud on the emulator.' > ttsbook.txt
adb push ttsbook.txt /sdcard/Download/librera_tts_test.txt
echo "Starting TTSService (Kokoro) on the pushed text file..."
adb shell am start-foreground-service -n "$PKG/com.foobnix.tts.TTSService" \
  -a ACTION_PLAY_CURRENT_PAGE \
  --ei INT 0 \
  --es EXTRA_PATH /sdcard/Download/librera_tts_test.txt \
  --es EXTRA_ANCHOR "" \
  --ei EXTRA_W 1080 --ei EXTRA_H 2400 || true
sleep 75
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
echo "EMULATOR SMOKE TEST PASSED: launch + TTS playback + back-key survival"
