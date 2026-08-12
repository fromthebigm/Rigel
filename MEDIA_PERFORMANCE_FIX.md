# Rigel media and fullscreen stability patch

This build keeps every WKWebView behind Rigel's local LittleProxy route. WebKit
connects straight to the local HTTP CONNECT endpoint instead of going through
the old local SOCKS-to-HTTP adapter first. HTTPS media remains an opaque CONNECT
tunnel: the proxy does not decrypt, decode, transcode, resize, or otherwise
touch AV1/H.264 payloads. Video decoding and compositing remain inside Apple's
WebKit media process.

## Changes

- Uses WKWebView's native Fullscreen API when WebKit exposes it.
- Keeps the fake fullscreen implementation only as a fallback.
- Removes broad `expand`/`maximize` click matching that could misread TikTok UI
  controls as fullscreen buttons.
- Runs the fake-fullscreen mutation observer only while fake fullscreen is active.
- Disables the generic media-menu placement helper on TikTok.
- On other sites, the media-menu observer is armed only briefly after a media
  control interaction instead of watching the entire document continuously.
- Observes only the page title/head for title updates instead of falling back to
  a whole-document observer.
- Removes CSS `zoom` entirely at 100% so WebKit can use its normal video
  compositing path.
- Coalesces rapid title-driven tab-strip rebuilds.
- Keeps the horizontal tab hover active over all child controls.
- Shows the address-bar accent border while focused.
- Adds **Tools -> Media Diagnostics...** (`Cmd+Option+M`) with buffering and
  dropped-frame counters.
- Removes the redundant local SOCKS adapter and its bidirectional Java copy
  threads while retaining the local proxy for TikTok comments and media.
- Keeps Direct and HTTP/SOCKS upstream routing inside LittleProxy, matching the
  same routing model for every site.
- Tunes the local proxy for long-lived media connections and larger HTTP chunks.
- Detects the malformed HE-AACv2 clock used by affected TikTok MP4s, restores
  their intended half-length timeline, and keeps TikTok's displayed playback
  speed at the user-selected value.
- Renders TikTok `cmt_sticker_struct` media when the desktop page falls back to
  a literal `[Sticker]` label.
- Retries the TikTok Comments tab when a positive comment count is paired with
  a transient `No comments` result.
- Adds `aitest.sh`, which disables window capture protection only for that
  diagnostic process while normal Rigel launches remain protected.

## Native bridge signature

The bundled WKWebView bridge contains distribution-specific script patches.
Rigel ad-hoc signs the extracted temporary copy with `/usr/bin/codesign` before
JNA loads it. Xcode is not required.
