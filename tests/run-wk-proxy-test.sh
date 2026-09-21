#!/usr/bin/env zsh
set -euo pipefail

ROOT="${0:A:h:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-wk-proxy-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

"$ROOT/build.sh"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all \
  -cp "$ROOT/build/classes:$ROOT/lib/*" \
  -d "$OUT" "$ROOT/tests/java/RigelWKProxyIntegrationTest.java"
RIGEL_PROXY_DIAGNOSTICS=1 RIGEL_MEDIA_TRACE=1 "$JAVA_HOME/bin/java" \
  -XstartOnFirstThread \
  -Drigel.root="$ROOT" \
  -cp "$OUT:$ROOT/build/classes:$ROOT/lib/*" RigelWKProxyIntegrationTest
