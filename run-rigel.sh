#!/usr/bin/env zsh
set -e

DIR="${0:A:h}"

# Terminal exports its own bundle identity to child processes. If Java inherits
# it, LaunchServices can keep treating Rigel as part of Terminal: Terminal stays
# active in the menu bar and the otherwise-visible Rigel window may not receive
# mouse events. Let macOS register Java/Rigel as its own GUI process instead.
unset __CFBundleIdentifier

# Normal launches must keep Rigel's window capture protection enabled even if
# the calling shell previously exported the diagnostic flag. aitest.sh opts in
# explicitly via this private launcher-mode variable.
if [[ "${RIGEL_LAUNCH_MODE:-}" == "diagnostic-capture" ]]; then
  export RIGEL_DIAGNOSTIC_CAPTURE=1
else
  unset RIGEL_DIAGNOSTIC_CAPTURE
fi
unset RIGEL_LAUNCH_MODE

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
