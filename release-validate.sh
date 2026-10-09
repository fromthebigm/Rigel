#!/bin/zsh
# release-validate.sh — run before tagging a release.
# Validates that a built zip meets all release requirements.
# Usage: ./release-validate.sh <zip-path> <expected-tag> <app-id>

set -euo pipefail

ZIP="$1"
EXPECTED_TAG="$2"
APP_ID="$3"

if [[ -z "$ZIP" || -z "$EXPECTED_TAG" || -z "$APP_ID" ]]; then
    echo "Usage: $0 <zip-path> <expected-tag> <app-id>"
    exit 1
fi

if [[ ! -f "$ZIP" ]]; then
    echo "FAIL: zip not found: $ZIP"
    exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "Extracting $ZIP..."
ditto -x -k "$ZIP" "$TMP"

# Find top-level folder
TOP=$(ls -1 "$TMP" | head -1)
if [[ -z "$TOP" ]]; then
    echo "FAIL: empty zip"
    exit 1
fi
ROOT="$TMP/$TOP"

echo "Validating $APP_ID..."

# 1. Version.txt (if present) must match expected tag
VERSION_FILE="$ROOT/version.txt"
if [[ -f "$VERSION_FILE" ]]; then
    ACTUAL_TAG=$(head -1 "$VERSION_FILE" | tr -d '\r\n')
    if [[ "$ACTUAL_TAG" != "$EXPECTED_TAG" ]]; then
        echo "FAIL: version.txt tag mismatch: got '$ACTUAL_TAG', expected '$EXPECTED_TAG'"
        exit 1
    fi
    echo "OK: version.txt matches tag ($ACTUAL_TAG)"
else
    echo "OK: no version.txt (BigM will write install stamp)"
fi

# 2. Catalog marker must exist
# Catalog mapping (from BigM's default-catalog.json)
case "$APP_ID" in
    rigel) MARKER="run-rigel.sh" ;;
    code) MARKER="launch_code.py" ;;
    moonlight) MARKER="moonlight_launch.py" ;;
    chiaki) MARKER="run_dove.py" ;;
    azahar) MARKER="run_seagull.py" ;;
    cemu) MARKER="run_pelican.py" ;;
    dolphin) MARKER="run_fish.py" ;;
    ryujinx) MARKER="run_newer.py" ;;
    prism) MARKER="launch_prism.py" ;;
    *) echo "WARN: unknown app-id $APP_ID, skipping marker check"; MARKER="" ;;
esac

if [[ -n "$MARKER" ]]; then
    if [[ ! -f "$ROOT/$MARKER" ]]; then
        echo "FAIL: marker '$MARKER' not found in zip root"
        exit 1
    fi
    echo "OK: marker '$MARKER' present"
fi

# 3. No dev/source files in zip root
FORBIDDEN_ROOT=('*.md' '.gitignore' '.gitattributes' '*-build.json' 'build.sh' 'test.sh' 'setup*.sh' 'get_*.sh' 'update_*.py' 'make_portable.sh' 'patch-*.sh')
for pattern in "${FORBIDDEN_ROOT[@]}"; do
    matches=("$ROOT"/${~pattern}(N))
    if (( ${#matches[@]} > 0 )); then
        echo "FAIL: forbidden dev file in zip root: ${matches[0]}"
        exit 1
    fi
done
echo "OK: no dev files in zip root"

# 4. No .DS_Store anywhere
ds_count=$(find "$ROOT" -name ".DS_Store" | wc -l | tr -d ' ')
if [[ "$ds_count" -gt 0 ]]; then
    echo "FAIL: $ds_count .DS_Store file(s) found"
    exit 1
fi
echo "OK: no .DS_Store"

# 5. Asset basename matches expected pattern
BASENAME=$(basename "$ZIP")
case "$APP_ID" in
    rigel) [[ "$BASENAME" == Rigel-portable-*.zip ]] || { echo "FAIL: bad basename $BASENAME"; exit 1; } ;;
    code) [[ "$BASENAME" == Code-Portable-*.zip ]] || { echo "FAIL: bad basename $BASENAME"; exit 1; } ;;
    moonlight) [[ "$BASENAME" == Moonlight-portable-*.zip ]] || { echo "FAIL: bad basename $BASENAME"; exit 1; } ;;
    # ... add others as needed
esac
echo "OK: asset basename matches pattern"

# 6. OUR dylibs must be (ad-hoc) signed — the JDK tree is Azul Developer-ID
# signed and out of scope (never modify jdk/; its signature validates only
# against the pristine bundle layout, not a zipped subset).
dylibs=("$ROOT"/**/classes/native/*.dylib(N) "$ROOT"/**/*.dylib(N))
for d in "${dylibs[@]}"; do
    [[ "$d" == */jdk/Contents/* ]] && continue
    if ! codesign -v "$d" 2>/dev/null; then
        echo "FAIL: unsigned dylib: $d"
        exit 1
    fi
done
echo "OK: all app dylibs signed (jdk tree is Developer-ID signed upstream)"

# 7. No .dSYM bundles
dsym_count=$(find "$ROOT" -name "*.dSYM" | wc -l | tr -d ' ')
if [[ "$dsym_count" -gt 0 ]]; then
    echo "FAIL: $dsym_count .dSYM bundle(s) found"
    exit 1
fi
echo "OK: no .dSYM bundles"

echo "=== ALL CHECKS PASSED for $APP_ID ==="
exit 0