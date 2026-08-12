# Rigel Browser - Fullscreen Fix

## Problem Fixed
- YouTube and other video streaming sites showed "fullscreen not supported on this device"
- Videos weren't properly fullscreening to the entire desktop, or were being blocked by other UI elements (like nav bars, comments, etc.)
- Previous CSS class-based injection was fragile and didn't work for dynamically injected UI elements.

## Solution Overview

The fix prefers WebKit's native Fullscreen API and keeps a JavaScript/OS-window
compatibility path for players that reject fullscreen inside an embedded view:

### 1. **JavaScript Fullscreen API Mocking** (in `TabManager.java` - FULLSCREEN_JS)
- `document.fullscreenEnabled` → true (all variants)
- `document.fullscreenElement` → tracks current element
- `Element.requestFullscreen()` → calls Java, styles elements, fires events
- `document.exitFullscreen()` → restores original state, calls Java
- Dispatches proper, bubbling `fullscreenchange` events

### 2. **Inline Styling & Backdrop** (in FULLSCREEN_JS)
Instead of generic CSS classes, the JS code now dynamically manipulates the DOM:
- **Forces Fullscreen:** Pins the target edge-to-edge with `position: fixed`, clips overflow at the player boundary, and sizes its video to `100% × 100%` so viewport scrollbars cannot be created by `vw` rounding or nested containers.
- **Backdrop:** Locks the `html` and `body` scroll roots and paints them black behind the player.
- **Hide Siblings:** Walks up the DOM tree and sets `visibility: hidden` and `width: 0` on *every* sibling element. This guarantees that sidebars, navbars, and comment sections from *any* site are hidden without needing site-specific CSS selectors.
- **Unclip Ancestors:** Removes clipping from layout ancestors while deliberately leaving the `body` and `html` scroll locks intact.
- **State Restoration:** Saves the original `cssText` of every modified element and restores them exactly on exit.

### 3. **Site-Specific Hooks**
- **YouTube:** Manually toggles the `.ytp-fullscreen` class on the exact selected player and hides specific YouTube UI elements that sit outside the normal DOM hierarchy (like the masthead and chat frame).
- **Custom players:** Known Video.js, JW Player, Plyr, Shaka, Clappr, MediaElement, and Netflix-style wrappers stay intact so their control and caption layers remain with the video.
- **Native media UI:** The former global media-menu placement helper was removed entirely. Rigel no longer rewrites site-owned player classes, popup positions, or overflow on any website.

### 4. **Java-Side Integration** (in `BrowserUI.java`)
- `enterFullscreen()`:
  - Hides UI chrome (nav bar, sidebar)
  - Calls `shell.setFullScreen(true)` for OS fullscreen
- `exitFullscreen()`:
  - Restores UI chrome
  - Calls `shell.setFullScreen(false)`

## Supported Video Platforms
✅ YouTube - fullscreen button and double-click
✅ Netflix-style players - generic fullscreen handler (protected playback remains dependent on macOS WebKit and the service)
✅ Crunchyroll - generic fullscreen handler
✅ Twitch - fullscreen button
✅ Vimeo - fullscreen button and double-click
✅ Generic video players - double-click or requestFullscreen()

## Keyboard Shortcuts
- `F11` - Toggle fullscreen
- `Esc` - Exit fullscreen
- Double-click video - Toggle fullscreen

## Build & Run

**Build:**
```bash
cd /path/to/rigel-dist
./build.sh
```

**Run:**
```bash
./run-rigel.sh
```
