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

# Emit Java 21 class files (major 65) so the jar also runs on machines that
# only have a system JDK 21, even though the bundle carries JDK 25. Without
# this, classes built by the bundled JDK land at major 69 and older runtimes
# fail with UnsupportedClassVersionError.
"$JAVA_HOME/bin/javac" \
  --release 21 \
  -encoding UTF-8 \
  -Xlint:all \
  -cp "$ROOT/lib/*" \
  -d "$BUILD_DIR" \
  $sources

cp "$ROOT/classes/native/libRigelWKWebView.dylib" "$BUILD_DIR/native/"
cp "$ROOT/resources/chrome-icon.png" "$BUILD_DIR/"
cp "$ROOT/resources/rigel-icon.png" "$BUILD_DIR/"
cp "$ROOT/resources/weather-icon.png" "$BUILD_DIR/"
cp "$ROOT/resources/anchor-polyfill.js" "$BUILD_DIR/"
cp "$ROOT/resources/anchor-polyfill.LICENSE.txt" "$BUILD_DIR/"

if [[ ! -f "$ROOT/target/MANIFEST.MF" ]]; then
  # Fresh checkouts do not carry target/ (gitignored); a runbook manifest is
  # enough because run-rigel.sh puts lib/* on the classpath explicitly.
  mkdir -p "$ROOT/target"
  print 'Manifest-Version: 1.0' > "$ROOT/target/MANIFEST.MF"
fi

rm -f "$JAR_TMP"
"$JAVA_HOME/bin/jar" --create \
  --file "$JAR_TMP" \
  --manifest "$ROOT/target/MANIFEST.MF" \
  -C "$BUILD_DIR" .
mv -f "$JAR_TMP" "$ROOT/target/rigel.jar"

# Update identity: release tag (or a date-based default for local builds) and
# the UTC build date. The updater compares these against GitHub releases.
RELEASE_TAG="${RELEASE_TAG:-portable-$(date -u '+%Y.%m.%d')}"
{
  print "$RELEASE_TAG"
  date -u '+%Y-%m-%d'
} > "$ROOT/version.txt"

print "Built $ROOT/target/rigel.jar with the bundled JDK."
