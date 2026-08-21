#!/usr/bin/env zsh
set -euo pipefail

ROOT="${0:A:h:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-wk-lifecycle-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

"$ROOT/build.sh"
if rg -a -q '_setExtensibleSSOEnabled:' "$ROOT/classes/native/libRigelWKWebView.dylib"; then
  print -u2 "Unsafe AppSSO preference toggle is present in the native bridge"
  exit 1
fi
rg -a -q 'decidePolicyForSOAuthorizationLoadWithCurrentPolicy:' \
  "$ROOT/classes/native/libRigelWKWebView.dylib"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all \
  -cp "$ROOT/build/classes:$ROOT/lib/*" \
  -d "$OUT" "$ROOT/tests/java/ui/RigelWKLifecycleStressTest.java"
RIGEL_WK_LIFECYCLE_TEST=1 RIGEL_LOGIN_DIAGNOSTICS=1 \
  "$JAVA_HOME/bin/java" \
  -XstartOnFirstThread \
  -Drigel.root="$ROOT" \
  -Drigel.tests.allowContentProcessTermination=true \
  -cp "$OUT:$ROOT/build/classes:$ROOT/lib/*" ui.RigelWKLifecycleStressTest
