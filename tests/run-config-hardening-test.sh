#!/usr/bin/env zsh
set -euo pipefail

ROOT="${0:A:h:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-config-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes"

"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all \
  -d "$OUT/classes" \
  "$ROOT/src/config/AtomicFiles.java" \
  "$ROOT/src/config/Settings.java" \
  "$ROOT/src/config/SearchEngines.java" \
  "$ROOT/src/config/Bookmarks.java" \
  "$ROOT/src/config/History.java" \
  "$ROOT/tests/java/config/RigelConfigHardeningTest.java"

"$JAVA_HOME/bin/java" \
  -Drigel.test.root="$OUT" \
  -cp "$OUT/classes" config.RigelConfigHardeningTest
