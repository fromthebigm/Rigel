#!/bin/zsh
# Headless-safe Rigel audit: build + non-GUI harnesses only. GUI smokes
# (login, arrow, wk lifecycle) stay in their own scripts because they need a
# real window/WebKit process.
set -euo pipefail
ROOT="${0:A:h:h}"
cd "$ROOT"

./build.sh >/tmp/rigel-headless-build.log 2>&1
tail -3 /tmp/rigel-headless-build.log

for t in tests/run-proxy-core-test.sh tests/run-config-hardening-test.sh tests/run-search-engines-test.sh; do
  echo "[audit] $t"
  timeout 180 "$t" || { echo "FAIL: $t" >&2; exit 1; }
done

echo "[audit] static gates"
if grep -RIn --exclude-dir=build --exclude-dir=classes --exclude-dir=target --exclude-dir=portable-data --exclude-dir=.git \
    -e 'System\.loadLibrary\(' -e 'System\.load\(' -e 'libraryLookup\("[^"]+\.dylib' src 2>/dev/null \
    | grep -v '/usr/lib/libobjc.A.dylib' | grep -vE '/System/Library|/usr/lib/'; then
  echo "FAIL: unexpected native load" >&2
  exit 1
fi
# Identity must be Google Chrome for the menu bar/Dock name, and the launcher
# must run through java — no Rigel.app executable bundle.
grep -q 'Google Chrome' src/RigelApp.java || { echo "FAIL: Rigel identity is not Google Chrome" >&2; exit 1; }
if [ -e Rigel.app ]; then
  echo "FAIL: Rigel.app exists — launcher must not have its own executable" >&2
  exit 1
fi

echo "HEADLESS AUDIT PASS"
