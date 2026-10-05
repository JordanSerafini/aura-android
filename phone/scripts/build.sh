#!/usr/bin/env bash
# Build of the phone app: type-check, Expo prebuild (generates the throwaway android/ project), release APK.
#
# Configuration comes from the environment, never from a tracked file:
#   AURA_MOBILE_DEVICE_TOKEN  device token issued by your own server
#   AURA_BRIDGE_URL           WebSocket endpoint of your server (wss://...)
#   AURA_PWA_URL              URL of the web UI shown in the Aura tab (https://...)
# Release signing (required by assembleRelease; for an unsigned debug build run ./gradlew assembleDebug in android/):
#   AURA_KEYSTORE_FILE, AURA_KEYSTORE_ALIAS (default: aura), AURA_MOBILE_KEYSTORE_PASS
# Requires Node 20+, a JDK 17+ and the Android SDK (ANDROID_HOME).
#
# SKIP_PREBUILD=1 reuses an already generated android/ directory.
set -euo pipefail

PHONE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK path}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export NODE_ENV=production

cd "$PHONE_DIR"
if [ -z "${AURA_BUILD_ID:-}" ]; then
  sha="$(git rev-parse --short HEAD 2>/dev/null || true)"
  dirty=""
  [ -n "$sha" ] && [ -n "$(git status --porcelain -- . 2>/dev/null)" ] && dirty="-dirty"
  AURA_BUILD_ID="$(date '+%Y-%m-%d %H:%M')${sha:+ $sha$dirty}"
fi
export AURA_BUILD_ID
echo "build: $AURA_BUILD_ID"

[ -d node_modules ] || npm ci
npx tsc --noEmit

if [ "${SKIP_PREBUILD:-0}" != "1" ] || [ ! -d android ]; then
  CI=1 npx expo prebuild -p android --clean --no-install
fi

cd android
trap './gradlew --stop >/dev/null 2>&1 || true' EXIT
./gradlew assembleRelease --console=plain "$@"

APK="$PHONE_DIR/android/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
ls -l "$APK"
