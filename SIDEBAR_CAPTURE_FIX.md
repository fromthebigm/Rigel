# Sidebar and capture-protection fix

This package keeps the Weather app dock identity and capture exclusion while
removing the crash-prone global hooks on `NSWindow` ordering methods.

## Changes

- Restored the stable dock-identity library for the Weather app identity.
- Added `ui.MacWindowCaptureProtection`, which applies `NSWindowSharingNone`
  and `CGSSetWindowCaptureExcludeShape` only to Rigel's main SWT shell.
- Capture protection is debounced after shell resize, movement, fullscreen,
  theme changes, and sidebar/top-bar switches.
- Settings now closes before the live WKWebView hierarchy is rebuilt.

The private `CGSSetWindowCaptureExcludeShape` API remains best-effort because
Apple can change private APIs between macOS releases. Ordinary
`NSWindowSharingNone` protection is still applied when possible.
