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
   || adb shell run-as com.foobnix.pdf.reader.ai test -f files/kokoro/model.int8.onnx; then
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
RU_GEN=$(grep -c "kokoro gen" logcat-tts-ru.txt || true)
echo "RU synthesis items: $RU_GEN"
if [ "$RU_GEN" == "0" ]; then
  if [ "${STRICT_RU:-1}" == "1" ]; then
    echo "::error::NO RUSSIAN SYNTHESIS - kokoro produced no audio for Cyrillic text"
    grep -m 25 "KokoroEngine\|TTSService\|AI TTS" logcat-tts-ru.txt || true
    exit 1
  fi
  echo "WARN: no RU synthesis yet (slow TCG runner) - informational only"
fi
echo "EMULATOR SMOKE TEST PASSED (RU too): Russian text synthesized without hangs"
