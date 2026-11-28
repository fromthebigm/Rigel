#!/bin/zsh
# Headless-safe Rigel audit: build + non-GUI harnesses only. GUI smokes
# (login, arrow, wk lifecycle) stay in their own scripts because they need a
# real window/WebKit process.
set -euo pipefail
ROOT="${0:A:h:h}"
cd "$ROOT"

./build.sh >/tmp/rigel-headless-build.log 2>&1
tail -3 /tmp/rigel-headless-build.log

for t in tests/run-proxy-core-test.sh tests/run-config-hardening-test.sh tests/run-search-engines-test.sh tests/run-objc-core-test.sh; do
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
# Backspace/Delete must never navigate back (regression: delete went to the
# previous page). The BS handler block must not call tabManager.back().
if sed -n '/e.keyCode == SWT.BS/,/^        }/p' src/ui/BrowserUI.java | grep -q 'tabManager\.back'; then
  echo "FAIL: Backspace still navigates back" >&2
  exit 1
fi
# Edit hotkeys must not send unguarded NSResponder selectors to WKWebView from a
# bare java process (ObjC NSException aborts the JVM; Cmd+Z/Cmd+X crash).
if grep -RIn --exclude-dir=build --exclude-dir=classes --exclude-dir=target --exclude-dir=portable-data --exclude-dir=.git \
    -e 'ObjC\.callVoid(get(handle)\.view, names\[' src 2>/dev/null; then
  echo "FAIL: unguarded native edit selector" >&2
  exit 1
fi
# The global key filter must not intercept edit shortcuts (that made Cmd+V a
# no-op and forced the macOS Paste pill).
if sed -n '/private void handleGlobalKey/,/^    }/p' src/ui/BrowserUI.java | grep -q 'performEditAction(RigelBrowser.EditAction.'; then
  echo "FAIL: global key filter intercepts edit shortcuts" >&2
  exit 1
fi
# Shutdown must not synchronously fetch cookies on the UI thread or defer WK
# disposal to a later tick. Native transient blocks must not use GLOBAL.
if sed -n '/} finally {/,/CookieBridge.markShuttingDown/p' src/ui/BrowserApplication.java | grep -q 'saveCookies()'; then
  echo "FAIL: synchronous cookie save in application teardown" >&2
  exit 1
fi
if grep -q 'asyncExec(this::disposeAndReleaseClosedWindow)' src/ui/BrowserUI.java; then
  echo "FAIL: deferred window teardown" >&2
  exit 1
fi
if grep -n 'ObjC\.block(.*ObjC.GLOBAL\|ObjC\.block($' src/ui/wk/FFMWKWebView.java src/ui/wk/CookieBridge.java | grep -q .; then
  echo "FAIL: permanent GLOBAL completion block" >&2
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
