#!/usr/bin/env zsh
# Build a runtime-only Rigel release zip without source, tests, docs, portable
# state, or transient dist payload folders. Keeps the bundled JDK untouched.
set -euo pipefail
ROOT="${0:A:h}"
TAG="${1:?usage: ./package.sh portable-YYYY.MM.DD.N}"
cd "$ROOT"
./build.sh >/tmp/rigel-package-build.log 2>&1
tail -3 /tmp/rigel-package-build.log
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rigel-package.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
PAYLOAD="$WORK/Rigel"
mkdir -p "$PAYLOAD/Support" dist
for p in run.sh run-rigel.sh jdk lib resources target build; do
  cp -R "$ROOT/$p" "$PAYLOAD/"
done
cp "$ROOT"/{Rigel.icns,Weather.icns,weather-light.png,weather-dark.png} "$PAYLOAD/"
printf '%s\n%s\n' "$TAG" "$(date +%F)" >"$PAYLOAD/version.txt"
find "$PAYLOAD" -name '.DS_Store' -delete
ZIP="$ROOT/dist/Rigel-portable-${TAG}.zip"
rm -f "$ZIP"
ditto -c -k --norsrc --noextattr --noqtn --noacl --keepParent "$PAYLOAD" "$ZIP"
./release-validate.sh "$ZIP" "$TAG" rigel
ls -lh "$ZIP"
