#!/usr/bin/env bash
# Build of the Wear OS app: unit tests, then a release APK (signed only if a keystore is provided).
#
# Signing is optional and comes from the environment, never from a tracked file:
#   AURA_KEYSTORE_PATH   path to a .jks / .keystore
#   AURA_KEYSTORE_PASS   keystore password
#   AURA_KEY_ALIAS       key alias (default: aura)
#   AURA_KEY_PASS        key password (default: same as the keystore password)
# The phone app and the watch app must be signed with the same key, otherwise the
# Wearable Data Layer will not link them.
#
# SKIP_TESTS=1 skips the unit tests.
set -euo pipefail

WATCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -z "${ANDROID_HOME:-}" ] && [ ! -f "$WATCH_DIR/local.properties" ]; then
  echo "Set ANDROID_HOME or create watch/local.properties with sdk.dir=/path/to/Android/Sdk" >&2
  exit 1
fi

cd "$WATCH_DIR"
TASKS=("assembleRelease")
[ "${SKIP_TESTS:-0}" = "1" ] || TASKS=("testDebugUnitTest" "${TASKS[@]}")
trap './gradlew --stop >/dev/null 2>&1 || true' EXIT
./gradlew "${TASKS[@]}" --console=plain "$@"

ls -l "$WATCH_DIR"/app/build/outputs/apk/release/*.apk
