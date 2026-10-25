#!/usr/bin/env bash
# Tests JVM de la logique pure du telephone, sans prebuild : ./phone/scripts/jvm-tests/run.sh
# Fichier pur = aucun import android/androidx/expo/okhttp. Un test qui touche une classe Android est exclu (liste SKIP).
# La suite officielle reste ./gradlew :aura-device:testReleaseUnitTest apres expo prebuild.
set -euo pipefail
H="$(cd "$(dirname "$0")" && pwd)"
M="$H/../../modules/aura-device/android/src"
S="$M/main/java/dev/aura/mobile/device"; T="$M/test/java/dev/aura/mobile/device"
# dependent d'Aura/PhoneContext (Android) : hors harnais
IMPURS=" EventLogic WatchConvs "
SKIP=" PhoneContextTest PhoneEventsTest CallLogicTest NatifTest0310 "
rm -rf "$H/build/w" && mkdir -p "$H/build/w/main" "$H/build/w/test"
for f in "$S"/*.kt; do
  b=$(basename "$f" .kt)
  [[ "$IMPURS" == *" $b "* ]] && continue
  grep -qE '^import (android|androidx|expo|okhttp3|com\.facebook)\.' "$f" || cp "$f" "$H/build/w/main/"
done
for t in "$T"/*.kt; do b=$(basename "$t" .kt); [[ "$SKIP" == *" $b "* ]] || cp "$t" "$H/build/w/test/"; done
cd "$H" && "$H/../../../watch/gradlew" -p "$H" --offline -q test "$@"
python3 - "$H/build/test-results/test" <<'P'
import glob, re, sys
t = f = e = 0
xs = glob.glob(sys.argv[1] + "/*.xml")
for x in xs:
  m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', open(x).read(800))
  t += int(m[1]); f += int(m[2]); e += int(m[3])
print(f"jvm-tests : {len(xs)} classes, {t} tests, {f} echec(s), {e} erreur(s)")
sys.exit(1 if f or e else 0)
P
