#!/usr/bin/env zsh
set -euo pipefail
ROOT="${0:A:h:h}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/rigel-objc-core.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
"$ROOT/jdk/Contents/Home/bin/javac" -encoding UTF-8 \
  -cp "$ROOT/build/classes" -d "$OUT" "$ROOT/tests/java/ui/objc/ObjCBlockLayoutTest.java"
"$ROOT/jdk/Contents/Home/bin/java" --enable-native-access=ALL-UNNAMED \
  -cp "$OUT:$ROOT/build/classes" ui.objc.ObjCBlockLayoutTest
