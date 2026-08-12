#!/usr/bin/env zsh
set -e

DIR="${0:A:h}"
if [[ -x "$DIR/jdk/Contents/Home/bin/java" ]]; then
  JAVA="$DIR/jdk/Contents/Home/bin/java"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java 2>/dev/null || true)"
fi
if [[ -z "$JAVA" || ! -x "$JAVA" ]]; then
  print -u2 "No Java runtime found. Add the bundled jdk/ folder or set JAVA_HOME."
  exit 1
fi
exec "$JAVA" \
  -XstartOnFirstThread \
  -Drigel.root="$DIR" \
  -Xdock:icon="$DIR/Weather.icns" \
  -cp "$DIR/target/rigel.jar:$DIR/lib/*" \
  RigelApp "$@"
