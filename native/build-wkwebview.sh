#!/bin/zsh
set -euo pipefail

ROOT="${0:A:h:h}"
OUT="$ROOT/classes/native/libRigelWKWebView.dylib"
DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-12.0}"
mkdir -p "${OUT:h}"

xcrun clang -fobjc-arc -fmodules -Wall -Wextra -Werror -O2 -dynamiclib \
  -mmacosx-version-min="$DEPLOYMENT_TARGET" -Wl,-dead_strip \
  -framework Cocoa -framework WebKit -framework Network \
  -install_name @rpath/libRigelWKWebView.dylib \
  -o "$OUT" \
  "$ROOT/native/RigelWKWebView.m"

codesign --force --sign - "$OUT"

xcrun clang -fobjc-arc -fmodules -Wall -Wextra -Werror -O2 -dynamiclib \
  -mmacosx-version-min="$DEPLOYMENT_TARGET" -Wl,-dead_strip \
  -framework Foundation -framework AppKit -framework Security \
  -install_name @rpath/dock_identity.dylib \
  -o "$ROOT/dock_identity.dylib" \
  "$ROOT/native/RigelDockIdentity.m"
codesign --force --sign - "$ROOT/dock_identity.dylib"
echo "Built $OUT"
