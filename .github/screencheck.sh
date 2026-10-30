#!/bin/bash
# Emulator screen check: every state of the app in light and dark (and large text), as PNGs in shots/.
set -u
PKG=com.techmare.shuttercount
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put global window_animation_scale 1
mkdir -p shots
shot() {  # state, file name, scroll (0/1)
  adb shell am start -S -W -n $PKG/.MainActivity --es check "$1" >/dev/null
  sleep 3
  adb exec-out screencap -p > "shots/$2.png"
  if [ "$3" = 1 ]; then
    adb shell input swipe 540 1700 540 500 400; sleep 1
    adb exec-out screencap -p > "shots/$2-b.png"
  fi
}
STATES="photo-idle:0 photo-ok:1 photo-seen:1 photo-high:1 photo-fail:1 usb-idle:1 usb-ok:1 usb-fail:1 usb-error:0 history:0 history-empty:0 history-detail:1 history-detail-empty:0"
for theme in light dark; do
  [ $theme = dark ] && adb shell cmd uimode night yes || adb shell cmd uimode night no
  sleep 2
  for s in $STATES; do shot "${s%%:*}" "$theme-${s%%:*}" "${s##*:}"; done
done
# large text
adb shell cmd uimode night no
adb shell settings put system font_scale 1.3
sleep 2
shot photo-ok big-photo-ok 1
shot history big-history 0
adb shell settings put system font_scale 1.0
# crashes?
adb logcat -d -b crash > shots/crashes.txt || true
ls -la shots
# tap the first History row: the check opens; back returns to the list
adb shell cmd uimode night no; sleep 1
adb shell am start -S -W -n $PKG/.MainActivity --es check history >/dev/null; sleep 2
adb shell input tap 300 520; sleep 2
adb exec-out screencap -p > shots/tap-open.png
adb shell input keyevent KEYCODE_BACK; sleep 2
adb exec-out screencap -p > shots/tap-back.png
# search
adb shell am start -S -W -n $PKG/.MainActivity --es check history >/dev/null; sleep 2
adb shell input tap 540 330; sleep 1; adb shell input text "canon"; sleep 2
adb exec-out screencap -p > shots/search-canon.png
adb shell input keyevent KEYCODE_MOVE_END; for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_DEL; done
adb shell input text "zzz"; sleep 2
adb exec-out screencap -p > shots/search-none.png
