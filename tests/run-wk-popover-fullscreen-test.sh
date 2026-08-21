#!/usr/bin/env zsh
set -euo pipefail

ROOT="${0:A:h:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-wk-popover-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

if [[ ! -x "$JAVA_HOME/bin/java" || ! -x "$JAVA_HOME/bin/javac" ]]; then
  print -u2 "The WK popover integration test requires Rigel's bundled JDK."
  exit 1
fi

"$ROOT/build.sh"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all \
  -cp "$ROOT/target/rigel.jar:$ROOT/lib/*" \
  -d "$OUT" "$ROOT/tests/java/ui/RigelWKPopoverFullscreenIntegrationTest.java"

# Capture protection is part of this fullscreen regression. Do not inherit the
# opt-out used by aitest.sh or other local visual-diagnostics sessions.
unset RIGEL_DIAGNOSTIC_CAPTURE
"$JAVA_HOME/bin/java" \
  -XstartOnFirstThread \
  -Drigel.root="$ROOT" \
  -cp "$OUT:$ROOT/target/rigel.jar:$ROOT/lib/*" \
  ui.RigelWKPopoverFullscreenIntegrationTest
