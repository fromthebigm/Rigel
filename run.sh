#!/usr/bin/env zsh
set -e

DIR="${0:A:h}"

# Portable sign-in continuity (runs before run-rigel.sh execs the bundled JDK).
# WebKit keys the cookie jar on the process identity, which for the bundled
# java is the JDK vendor's stub — normally stable, but it can flip if the
# JDK ever changes. The rescue layer makes logins permanent regardless:
# each launch backs the previous session's jar into Support/, restores it
# if WebKit ever loses it, and (once) migrates a jar left behind by any
# older identity. The backup travels with the folder, so copying Rigel to
# another Mac carries the sign-ins too.
COOKIE_ID="com.azul.zulu.java"
COOKIES_DIR="$HOME/Library/HTTPStorages"
COOKIES="$COOKIES_DIR/$COOKIE_ID.binarycookies"
SUPPORT_DIR="$DIR/Support"
BACKUP="$SUPPORT_DIR/cookie-jar.binarycookies"
mkdir -p "$SUPPORT_DIR" "$COOKIES_DIR"
if [[ -f "$COOKIES" ]]; then
  cp -f "$COOKIES" "$BACKUP" 2>/dev/null || true
elif [[ -f "$BACKUP" ]]; then
  cp -f "$BACKUP" "$COOKIES" 2>/dev/null || true
else
  for legacy in net.java.openjdk.java com.oracle.java.jdk com.rigel.weather-host; do
    if [[ -f "$COOKIES_DIR/$legacy.binarycookies" ]]; then
      cp -f "$COOKIES_DIR/$legacy.binarycookies" "$BACKUP" 2>/dev/null || true
      break
    fi
  done
  if [[ -f "$BACKUP" ]]; then
    cp -f "$BACKUP" "$COOKIES" 2>/dev/null || true
  fi
fi

# Launch via zsh instead of exec'ing the script directly so this still works
# even when a copied folder arrives with run-rigel.sh's execute bit stripped.
exec /bin/zsh "$DIR/run-rigel.sh" "$@"
