#!/bin/bash
# Live-Vorschau: startet den Emulator (falls nötig), baut die Debug-APK, installiert und öffnet die App.
# Aufruf: ./scripts/preview.sh            einmalig bauen + starten
#         ./scripts/preview.sh --watch    bei jeder Änderung unter app/src neu bauen und neu starten
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/Library/Java/JavaVirtualMachines/jdk-17*/Contents/Home | head -1)}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
AVD="Celander_Pixel"
APP="com.celander.app"

if ! "$ADB" devices | grep -q "emulator-"; then
    echo "▶ Starte Emulator $AVD …"
    # Host GPU: with software rendering (SwiftShader) the glass blur takes seconds per frame.
    nohup "$SDK/emulator/emulator" -avd "$AVD" -no-boot-anim -gpu host >/dev/null 2>&1 &
    "$ADB" wait-for-device
    until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
fi

deploy() {
    echo "▶ Baue und installiere …"
    (cd "$ROOT" && ./gradlew -q :app:installDebug)
    "$ADB" shell am force-stop "$APP"
    "$ADB" shell am start -n "$APP/.MainActivity" >/dev/null
    echo "✓ App läuft im Emulator ($(date +%H:%M:%S))"
}

deploy

if [ "${1:-}" = "--watch" ]; then
    echo "👀 Beobachte app/src – Strg+C zum Beenden"
    last=""
    while true; do
        current="$(find "$ROOT/app/src" -type f -newer "$ROOT/app/build.gradle.kts" -exec stat -f '%m %N' {} + 2>/dev/null | sort | tail -1)"
        if [ -n "$current" ] && [ "$current" != "$last" ]; then
            [ -n "$last" ] && deploy
            last="$current"
        fi
        sleep 2
    done
fi
