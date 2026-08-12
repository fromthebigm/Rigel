#!/usr/bin/env zsh
set -euo pipefail

ROOT="${0:A:h}"
if [[ -x "$ROOT/jdk/Contents/Home/bin/javac" ]]; then
  JAVA_HOME="$ROOT/jdk/Contents/Home"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  JAVA_HOME="$JAVA_HOME"
elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
BUILD_DIR="$ROOT/build/classes"
JAR_TMP="$ROOT/target/rigel.jar.tmp"

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
  print -u2 "No JDK found. Add the bundled jdk/ folder or set JAVA_HOME."
  exit 1
fi

/bin/zsh "$ROOT/native/build-wkwebview.sh"

rm -rf "$ROOT/build"
mkdir -p "$BUILD_DIR/native" "$ROOT/target"

sources=("$ROOT"/src/**/*.java(N))
if (( ${#sources} == 0 )); then
  print -u2 "No Java sources found under $ROOT/src"
  exit 1
fi

"$JAVA_HOME/bin/javac" \
  -encoding UTF-8 \
  -Xlint:all \
  -cp "$ROOT/lib/*" \
  -d "$BUILD_DIR" \
  $sources

cp "$ROOT/classes/native/libRigelWKWebView.dylib" "$BUILD_DIR/native/"
cp "$ROOT/resources/chrome-icon.png" "$BUILD_DIR/"
cp "$ROOT/resources/rigel-icon.png" "$BUILD_DIR/"
cp "$ROOT/resources/weather-icon.png" "$BUILD_DIR/"

rm -f "$JAR_TMP"
"$JAVA_HOME/bin/jar" --create \
  --file "$JAR_TMP" \
  --manifest "$ROOT/target/MANIFEST.MF" \
  -C "$BUILD_DIR" .
mv -f "$JAR_TMP" "$ROOT/target/rigel.jar"

print "Built $ROOT/target/rigel.jar with the bundled JDK."
