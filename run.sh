#!/usr/bin/env zsh
set -e

DIR="${0:A:h}"

# ── Full portable state: EVERYTHING saves inside this folder ──────────────
# The process home is redirected into portable-data BEFORE java starts, so
# WebKit keys ALL of its persistent state inside the folder:
#   Library/HTTPStorages/<id>.binarycookies   — the cookie jar (logins)
#   Library/WebKit/<id>/WebsiteData            — per-origin localStorage +
#       IndexedDB partitions, MediaKeys (DRM), device salts, ITP state
#   Library/Caches/<id>/WebKit                 — network caches
# Logins, cookies, site sessions: all of it lives beside the app, survives
# updates (BigM never deletes portable-data), and copying the folder carries
# every session to another Mac. Java (user.home), AWT and SWT follow HOME
# automatically; settings already live in portable-data/profile via rigel.root.
# Side effects, both intentional: `~` in the address bar and the default
# downloads folder now resolve inside portable-data.
PORTABLE_HOME="$DIR/portable-data"
REAL_HOME="$HOME"
WK_ID="com.azul.zulu.java"
mkdir -p \
  "$PORTABLE_HOME/Library/HTTPStorages" \
  "$PORTABLE_HOME/Library/WebKit" \
  "$PORTABLE_HOME/Downloads" \
  "$PORTABLE_HOME/Support"
export HOME="$PORTABLE_HOME"
export CFFIXED_USER_HOME="$PORTABLE_HOME"

# One-time migration of existing sign-ins from the real home into the
# portable home. Copy, never move: the originals stay untouched as a
# fallback. Covers the cookie jar (plus its sqlite sidecar) and the whole
# WebsiteData store; a jar left by an older JDK identity is also carried.
migrate_portable_store() {
  local src="$1" dst="$2"
  if [[ -e "$src" && ! -e "$dst" ]]; then
    cp -R "$src" "$dst" 2>/dev/null || true
  fi
}
migrate_portable_store "$REAL_HOME/Library/HTTPStorages/$WK_ID.binarycookies" \
                       "$PORTABLE_HOME/Library/HTTPStorages/$WK_ID.binarycookies"
migrate_portable_store "$REAL_HOME/Library/HTTPStorages/$WK_ID" \
                       "$PORTABLE_HOME/Library/HTTPStorages/$WK_ID"
migrate_portable_store "$REAL_HOME/Library/WebKit/$WK_ID" \
                       "$PORTABLE_HOME/Library/WebKit/$WK_ID"
for legacy in net.java.openjdk.java com.oracle.java.jdk com.rigel.weather-host; do
  migrate_portable_store "$REAL_HOME/Library/HTTPStorages/$legacy.binarycookies" \
                         "$PORTABLE_HOME/Library/HTTPStorages/$WK_ID.binarycookies"
done

# Cookie-jar belt-and-suspenders: back the jar into Support/ at every launch
# (it reflects the previous session), restore it if WebKit ever loses it.
COOKIES="$PORTABLE_HOME/Library/HTTPStorages/$WK_ID.binarycookies"
BACKUP="$DIR/Support/cookie-jar.binarycookies"
if [[ -f "$COOKIES" ]]; then
  cp -f "$COOKIES" "$BACKUP" 2>/dev/null || true
elif [[ -f "$BACKUP" ]]; then
  cp -f "$BACKUP" "$COOKIES" 2>/dev/null || true
fi

# Launch via zsh instead of exec'ing the script directly so this still works
# even when a copied folder arrives with run-rigel.sh's execute bit stripped.
exec /bin/zsh "$DIR/run-rigel.sh" "$@"
