#!/usr/bin/env bash
# Device upgrade + verification for the VSCodroid debug build.
# Run pieces via: bash scripts/device-upgrade-test.sh <command>
set -u

PKG="com.vscodroid.debug"
APK="$HOME/Documents/GitHub/VSCodroid/android/app/build/outputs/apk/debug/app-debug.apk"

check_freshness() {
    echo "=== APK timestamp ==="
    stat -c '%y %n' "$APK"
    echo "=== Sources newer than APK ==="
    find "$HOME/Documents/GitHub/VSCodroid/android/app/src/main" \
        \( -name '*.kt' -o -name '*.xml' \) -newer "$APK" | head -10
    echo "=== Auto-bootstrap present in sources ==="
    grep -rln 'ensureNixOnDroidBootstrapped\|bootstrapNixOnDroid\|status_installing_nix' \
        "$HOME/Documents/GitHub/VSCodroid/android/app/src/main" 2>/dev/null
}

device_info() {
    adb devices -l
    echo "=== installed version of $PKG ==="
    adb shell dumpsys package "$PKG" 2>/dev/null | grep -E 'versionName|versionCode|lastUpdateTime' | head -5
}

upgrade() {
    echo "=== Installing $APK (upgrade, keep data) ==="
    adb install -r "$APK"
}

launch() {
    adb logcat -c
    echo "=== Launching $PKG ==="
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>/dev/null | tail -2
    sleep 12
}

logcat_app() {
    adb logcat -d 2>/dev/null | grep -iE "$PKG|VSCodroid|FirstRunSetup|NodeService|AndroidRuntime|FATAL" | grep -vE 'memoryleak|thermal|tranpm' | tail -60
}

process_state() {
    echo "=== processes ==="
    adb shell ps -A 2>/dev/null | grep vscodroid
    echo "=== server port listening (NodeService) ==="
    adb shell 'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null' | awk 'NR>1 {print $2}' | grep -i ':2' | head -5
}

sandbox() {
    echo "=== files/ ==="
    adb shell "run-as $PKG ls files/ 2>&1" | head -15
    echo "=== usr/bin ==="
    adb shell "run-as $PKG ls files/usr/bin/ 2>&1" | head -20
    echo "=== nix dir ==="
    adb shell "run-as $PKG ls files/nix/ 2>&1" | head -10
}

run_cmd() {
    # Run a shell command inside the app's sandbox context via run-as
    adb shell "run-as $PKG sh -c '$1' 2>&1"
}

case "${1:-}" in
    freshness) check_freshness ;;
    device)    device_info ;;
    upgrade)   upgrade ;;
    launch)    launch ;;
    logs)      logcat_app ;;
    state)     process_state ;;
    sandbox)   sandbox ;;
    run)       shift; run_cmd "$*" ;;
    all)
        check_freshness
        device_info
        upgrade
        launch
        process_state
        sandbox
        logcat_app
        ;;
    *)
        echo "usage: bash $0 freshness|device|upgrade|launch|logs|state|sandbox|run <cmd>|all"
        ;;
esac
