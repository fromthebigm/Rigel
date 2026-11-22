#!/usr/bin/env zsh
set -euo pipefail
ROOT="${0:A:h:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-google-smoke.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all \
  -cp "$ROOT/build/classes:$ROOT/lib/*" \
  -d "$OUT" \
  "$ROOT/src/ui/RigelBrowser.java" \
  "$ROOT/tests/java/ui/RigelGoogleSearchSmoke.java" \
  "$ROOT/tests/java/ui/RigelGoogleTapDiag.java"
"$JAVA_HOME/bin/java" -XstartOnFirstThread -cp "$OUT:$ROOT/build/classes:$ROOT/lib/*" ui.RigelGoogleSearchSmoke
"$JAVA_HOME/bin/java" -XstartOnFirstThread -cp "$OUT:$ROOT/build/classes:$ROOT/lib/*" ui.RigelGoogleTapDiag
