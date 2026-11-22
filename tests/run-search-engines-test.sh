#!/usr/bin/env zsh
set -euo pipefail
ROOT="${0:A:h:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-search-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all   -cp "$ROOT/build/classes:$ROOT/lib/*"   -d "$OUT"   "$ROOT/src/config/AtomicFiles.java"   "$ROOT/src/config/Settings.java"   "$ROOT/src/config/SearchEngines.java"   "$ROOT/tests/java/config/RigelSearchEnginesTest.java"
"$JAVA_HOME/bin/java" -Drigel.test.root="$OUT" -cp "$OUT:$ROOT/build/classes" config.RigelSearchEnginesTest
