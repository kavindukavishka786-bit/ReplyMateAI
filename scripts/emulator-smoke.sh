#!/usr/bin/env bash
set -euo pipefail
mkdir -p smoke
apk_path="$(find built -name app-debug.apk -type f -print -quit)"
test -n "$apk_path"
adb install "$apk_path"
adb logcat -c
adb shell pm grant com.replymate.ai android.permission.POST_NOTIFICATIONS
adb shell am start -W -n com.replymate.ai/.MainActivity | tee smoke/launch.txt
grep -q 'Status: ok' smoke/launch.txt
sleep 4
adb shell pidof com.replymate.ai | tee smoke/pid.txt
adb shell dumpsys activity activities > smoke/activities.txt
grep -E 'mResumedActivity.*com.replymate.ai|topResumedActivity.*com.replymate.ai' smoke/activities.txt
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am start -W -n com.replymate.ai/.MainActivity > smoke/resume.txt
grep -q 'Status: ok' smoke/resume.txt
sleep 2
adb exec-out screencap -p > smoke/dashboard.png
adb logcat -d > smoke/logcat.txt
if grep -q 'FATAL EXCEPTION' smoke/logcat.txt; then
  cat smoke/logcat.txt
  exit 1
fi
printf 'Android 14: APK installed, dashboard launched, resumed, no fatal exception.\n' > smoke/result.txt
