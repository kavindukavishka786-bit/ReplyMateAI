#!/usr/bin/env bash
set -euo pipefail
mkdir -p smoke
apk_path="built/app-debug.apk"
test -f "$apk_path"
adb install "$apk_path" > smoke/install.txt
adb logcat -c
adb shell pm grant com.replymate.ai android.permission.POST_NOTIFICATIONS
adb shell am start -W -n com.replymate.ai/.MainActivity > smoke/launch.txt
grep -q 'Status: ok' smoke/launch.txt
sleep 3
adb shell pidof com.replymate.ai > smoke/pid.txt
python3 scripts/navigation-smoke.py
adb shell input keyevent KEYCODE_HOME
sleep 1
adb shell am start -W -n com.replymate.ai/.MainActivity > smoke/resume.txt
grep -q 'Status: ok' smoke/resume.txt
sleep 2
adb shell dumpsys activity activities > smoke/activities.txt
grep -E 'mResumedActivity.*com.replymate.ai|topResumedActivity.*com.replymate.ai' smoke/activities.txt
adb exec-out screencap -p > smoke/dashboard.png
adb logcat -d > smoke/logcat.txt
if grep -Eq 'FATAL EXCEPTION|ANR in com.replymate.ai' smoke/logcat.txt; then
  exit 1
fi
printf 'Android 14: installed, launched, four tabs opened, resumed, no fatal exception.\n' > smoke/result.txt
