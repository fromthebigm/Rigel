#!/usr/bin/env zsh
set -e

DIR="${0:A:h}"

# A copied folder (AirDrop, USB/exFAT, cloud sync, zip from some tools) can
# arrive with every executable bit stripped and/or a quarantine flag applied to
# bundled binaries. Both failure modes surface later as "permission denied" at
# exec time, so repair them up front; this makes the folder launch identically
# no matter how it was transferred.
chmod -f +x "$DIR"/*.sh(N) "$DIR"/tests/*.sh(N) "$DIR"/native/*.sh(N) 2>/dev/null || true
if [[ -d "$DIR/jdk/Contents/Home" ]]; then
  chmod -f +x "$DIR/jdk/Contents/Home/bin/"*(N) 2>/dev/null || true
  # jspawnhelper backs ProcessBuilder/Runtime.exec; jexec only ships in Linux
  # JDKs. Guard each so a missing helper cannot trip `set -e`.
  for helper in "$DIR/jdk/Contents/Home/lib/jspawnhelper" "$DIR/jdk/Contents/Home/lib/jexec"; do
    if [[ -e "$helper" ]]; then
      chmod -f +x "$helper" 2>/dev/null || true
    fi
  done
fi
xattr -dr com.apple.quarantine "$DIR" 2>/dev/null || true

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

# This app is portable: it must run on its bundled JDK, never a system Java.
if [[ ! -x "$DIR/jdk/Contents/Home/bin/java" ]]; then
  print -u2 "Bundled JDK not found at $DIR/jdk/Contents/Home/bin/java."
  print -u2 "This portable app requires the bundled JDK that ships with it."
  exit 1
fi
JAVA="$DIR/jdk/Contents/Home/bin/java"

# Keep fatal JVM/native crash reports beside Rigel's portable settings so a
# failure on another Mac leaves actionable evidence. The JVM writes this file
# only after a fatal error; Rigel never uploads it.
mkdir -p "$DIR/portable-data/profile"

# JDK 24+ warns (and future JDKs will block) when JNA calls System::load
# without explicit native access. The flag does not exist on older runtimes
# that a source checkout may fall back to, so only pass it when supported.
NATIVE_ACCESS=()
if "$JAVA" --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
  NATIVE_ACCESS=(--enable-native-access=ALL-UNNAMED)
fi

exec "$JAVA" \
  -XstartOnFirstThread \
  "${NATIVE_ACCESS[@]}" \
  -XX:ErrorFile="$DIR/portable-data/profile/hs_err_pid%p.log" \
  -Drigel.root="$DIR" \
  -Xdock:icon="$DIR/Weather.icns" \
  -cp "$DIR/build/classes:$DIR/lib/*" \
  RigelApp "$@"
