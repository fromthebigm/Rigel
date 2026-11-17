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

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
  print -u2 "No JDK found. Add the bundled jdk/ folder or set JAVA_HOME."
  exit 1
fi

# No custom dylib is built: WKWebView is hosted via JDK FFM.

rm -rf "$ROOT/build"
mkdir -p "$BUILD_DIR" "$ROOT/target"

sources=("$ROOT"/src/**/*.java(N))
if (( ${#sources} == 0 )); then
  print -u2 "No Java sources found under $ROOT/src"
  exit 1
fi

# The no-dylib WKWebView host uses the JDK Foreign Function & Memory API,
# which is stable only from JDK 22. Compile against the bundled JDK 25.
"$JAVA_HOME/bin/javac" \
  -encoding UTF-8 \
  -Xlint:all \
  -cp "$ROOT/lib/*" \
  -d "$BUILD_DIR" \
  $sources

cp "$ROOT/resources/chrome-icon.png" "$BUILD_DIR/"
cp "$ROOT/resources/rigel-icon.png" "$BUILD_DIR/"
cp "$ROOT/resources/weather-icon.png" "$BUILD_DIR/"
cp "$ROOT/resources/anchor-polyfill.js" "$BUILD_DIR/"
cp "$ROOT/resources/anchor-polyfill.LICENSE.txt" "$BUILD_DIR/"

# Rigel runs directly from compiled classes (no jar). build/classes is the
# runtime classpath root; resources and the native bridge dylib are copied
# in alongside so Class.getResourceAsStream() resolves them at runtime.
mkdir -p "$ROOT/build/classes"

# Update identity: release tag (or a date-based default for local builds) and
# the UTC build date. The updater compares these against GitHub releases.
RELEASE_TAG="${RELEASE_TAG:-portable-$(date -u '+%Y.%m.%d')}"
{
  print "$RELEASE_TAG"
  date -u '+%Y-%m-%d'
} > "$ROOT/version.txt"

print "Built $ROOT/build/classes with the bundled JDK (run by classes, no jar)."
