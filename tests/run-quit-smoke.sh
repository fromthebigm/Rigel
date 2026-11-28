#!/usr/bin/env zsh
# Full java-process smoke: isolated portable HOME, real SWT/WK teardown, no
# terminal timeout as the successful exit mechanism.
set -euo pipefail
ROOT="${0:A:h:h}"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/rigel-quit-smoke.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/app/Support" "$TMP/real-home"
cp "$ROOT/run.sh" "$ROOT/run-rigel.sh" "$TMP/app/"
for p in jdk lib build resources; do ln -s "$ROOT/$p" "$TMP/app/$p"; done
cp "$ROOT"/{Rigel.icns,Weather.icns,weather-light.png,weather-dark.png} "$TMP/app/"
START=$(python3 -c 'import time; print(time.monotonic())')
set +e
HOME="$TMP/real-home" RIGEL_QUIT_AFTER_MS=1800 /bin/zsh "$TMP/app/run.sh" >"$TMP/quit.log" 2>&1
RC=$?
set -e
END=$(python3 -c 'import time; print(time.monotonic())')
ELAPSED=$(python3 -c "print(round($END-$START,2))")
cat "$TMP/quit.log" | tail -12
if [[ $RC -ne 0 ]]; then
  print -u2 "FAIL: Rigel exited $RC after ${ELAPSED}s"
  exit 1
fi
CLEANUP_MS=$(sed -n 's/.*quit-cleanup-ms=\([0-9][0-9]*\).*/\1/p' "$TMP/quit.log" | tail -1)
if [[ -z "$CLEANUP_MS" || "$CLEANUP_MS" -gt 4000 ]]; then
  print -u2 "FAIL: quit cleanup was ${CLEANUP_MS:-unreported}ms (total ${ELAPSED}s)"
  exit 1
fi
print "Rigel full-process quit smoke passed: cleanup=${CLEANUP_MS}ms total=${ELAPSED}s"
