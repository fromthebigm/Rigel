#!/usr/bin/env zsh
set -euo pipefail

ROOT="${0:A:h:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-chess-diag.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

"$ROOT/build.sh"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all \
  -cp "$ROOT/build/classes:$ROOT/lib/*" \
  -d "$OUT" "$ROOT/tests/java/RigelChessDiagnostic.java"
"$JAVA_HOME/bin/java" \
  -XstartOnFirstThread \
  -Drigel.root="$ROOT" \
  -cp "$OUT:$ROOT/build/classes:$ROOT/lib/*" RigelChessDiagnostic
