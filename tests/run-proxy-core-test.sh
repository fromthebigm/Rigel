#!/usr/bin/env zsh
set -euo pipefail

ROOT="${0:A:h:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-proxy-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

"$ROOT/build.sh"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all \
  -cp "$ROOT/build/classes:$ROOT/lib/*" \
  -d "$OUT" "$ROOT/tests/java/RigelProxyCoreTest.java" "$ROOT/tests/java/proxy/SecureDnsCoreTest.java"
"$JAVA_HOME/bin/java" \
  -cp "$OUT:$ROOT/build/classes:$ROOT/lib/*" RigelProxyCoreTest
"$JAVA_HOME/bin/java" \
  -cp "$OUT:$ROOT/build/classes:$ROOT/lib/*" proxy.SecureDnsCoreTest
