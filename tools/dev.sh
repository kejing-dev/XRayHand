#!/usr/bin/env bash
# X-ray Hand developer helper (Git Bash). Device commands are pinned to the factory lane emulator-5570
# (AVD PICO_6.1) and refuse to run when that serial is another AVD.
#
#   tools/dev.sh build                 assembleDebug            -> .scratch/build.log
#   tools/dev.sh install | launch | stop | relaunch
#   tools/dev.sh cmd 'pose open/open'  debug broadcast (see app/src/debug/.../DebugCommands.kt)
#   tools/dev.sh waitready             block until the scene is built (QA: run after every launch)
#   tools/dev.sh log [REGEX] [LINES]   logcat, tag XrayHand
#   tools/dev.sh crash                 last crash lines of the app
DEV=${DEV:-emulator-5570}
AVD=${AVD:-PICO_6.1}
PKG=io.embodit.xrayhand
TAG=XrayHand
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
mkdir -p "$ROOT/.scratch"

check() {
  avd="$("$ADB" -s $DEV emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  if [ "$avd" != "$AVD" ]; then echo "WRONG AVD on $DEV: '$avd' (expected $AVD)" >&2; exit 2; fi
}

case "${1:-}" in
  build)
    cd "$ROOT" && ./gradlew.bat assembleDebug --console=plain > .scratch/build.log 2>&1
    grep -E '^e: |error:|BUILD|FAILED' .scratch/build.log | head -40 ;;
  install)
    check; "$ADB" -s $DEV install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" 2>&1 | tail -1 ;;
  launch)
    check; "$ADB" -s $DEV logcat -c; "$ADB" -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo launched ;;
  stop)
    check; "$ADB" -s $DEV shell am force-stop $PKG; echo stopped ;;
  relaunch)
    check; "$ADB" -s $DEV shell am force-stop $PKG; sleep 1; "$ADB" -s $DEV logcat -c; "$ADB" -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo relaunched ;;
  cmd)
    check; shift; MSYS_NO_PATHCONV=1 "$ADB" -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$*'" >/dev/null; sleep 0.6
    "$ADB" -s $DEV logcat -d -s $TAG:I | grep -E "CMD '|STATE " | tail -2 ;;
  waitready)
    for i in $(seq 1 120); do "$ADB" -s $DEV logcat -d -s $TAG:I | grep -q "SCENE built" && { echo ready; exit 0; }; sleep 1; done; echo "not ready"; exit 1 ;;
  log)
    "$ADB" -s $DEV logcat -d -s $TAG:I | grep -E "${2:-.}" | tail -${3:-40} ;;
  crash)
    "$ADB" -s $DEV logcat -d -b crash | tail -40 ;;
  *) sed -n 2,9p "$0" ;;
esac
