#!/usr/bin/env zsh
set -euo pipefail

ROOT="${0:A:h:h}"
JAVA_HOME="$ROOT/jdk/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-wk-lifecycle-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

"$ROOT/build.sh"
# Portable fixed-string binary search: prefer ripgrep when present, fall back
# to grep so the test also runs on machines without extra tooling.
bin_contains() {
  if command -v rg >/dev/null 2>&1; then
    rg -a -q -F -- "$1" "$2"
  else
    grep -a -q -F -e "$1" "$2"
  fi
}
if [[ -f "$ROOT/classes/native/libRigelWKWebView.dylib" ]]; then
  if bin_contains '_setExtensibleSSOEnabled:' "$ROOT/classes/native/libRigelWKWebView.dylib"; then
    print -u2 "Unsafe AppSSO preference toggle is present in the native bridge"
    exit 1
  fi
  bin_contains 'decidePolicyForSOAuthorizationLoadWithCurrentPolicy:' \
    "$ROOT/classes/native/libRigelWKWebView.dylib"
else
  print "No custom dylib — native bridge binary checks skipped (FFM host)"
fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all \
  -cp "$ROOT/build/classes:$ROOT/lib/*" \
  -d "$OUT" "$ROOT/tests/java/ui/RigelWKLifecycleStressTest.java"
RIGEL_WK_LIFECYCLE_TEST=1 RIGEL_LOGIN_DIAGNOSTICS=1 \
  "$JAVA_HOME/bin/java" \
  -XstartOnFirstThread \
  -Drigel.root="$ROOT" \
  -Drigel.tests.allowContentProcessTermination=true \
  -cp "$OUT:$ROOT/build/classes:$ROOT/lib/*" ui.RigelWKLifecycleStressTest
