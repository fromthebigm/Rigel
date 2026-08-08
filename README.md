# Rigel Browser

This custom Rigel build is a lightweight macOS desktop browser built
with a Java/SWT interface, Apple's modern `WKWebView` renderer, and a built-in
local proxy. Its internal runtime and storage identifiers remain `Rigel` so
existing profiles and settings continue to work.

## Running Rigel

Download and unzip the distribution, then open Terminal and enter its folder:

```bash
cd "replace this with your distribution folder"
```

Launch Rigel with:

```bash
./run-rigel.sh
```

The distribution is self-contained: launch uses the bundled arm64 JDK and
paths are resolved from the distribution folder. Homebrew and a system Java install
are not required.

Or run the same command directly:

```bash
./jdk/Contents/Home/bin/java -XstartOnFirstThread -cp "./target/rigel.jar:./lib/*" RigelApp
```

The original SWT renderer is still available only as an explicit override.
Normal launches use WKWebView and do not silently downgrade.

## Features

- Tabbed browsing with either a combined top bar or a vertical sidebar.
- Theme-aware translucent chrome, responsive tabs, and an accessible tab overview.
- Multiple windows with `Cmd+N`.
- Shared settings, history, bookmarks, WebKit resources, and one LittleProxy
  instance across all windows.
- Persistent settings, bookmarks, and history stored in `portable-data/profile/`.
- Downloads to a configurable folder, with an optional save-as prompt.
- Bookmarks with `Cmd+D`.
- History of visited pages.
- Find in page with `Cmd+F`.
- Zoom controls with `Cmd +`, `Cmd -`, and `Cmd 0`.
- Inspectable WKWebView pages plus a built-in JavaScript console.
- Configurable search engine using any URL template with `%s` for the query.
- Smart address resolution: public hosts default to HTTPS, while private IPs
  and local names use HTTP for router and device admin pages.
- Optional host-based ad/tracker blocklist, disabled by default.
- Upstream proxy support, with automatic direct routing for `localhost`.
- Native-first video fullscreen and built-in media diagnostics (`Cmd+Option+M`).
- WebKit-managed AV1 playback when the current macOS and Apple hardware expose
  AV1 decoding support.

## Keyboard shortcuts

| Shortcut | Action |
|---|---|
| `Cmd+N` | New window |
| `Cmd+T` | New tab |
| `Cmd+W` | Close tab |
| `Cmd+Shift+W` | Close window |
| `Cmd+L` / `Cmd+K` | Focus address bar |
| `Cmd+X` | Cut |
| `Cmd+C` | Copy |
| `Cmd+V` | Paste |
| `Cmd+A` | Select all |
| `Cmd+R` | Reload |
| `Cmd+Shift+H` | Home |
| `Ctrl+Tab` / `Ctrl+Shift+Tab` | Next / previous tab |
| `Cmd+Shift+[` / `Cmd+Shift+]` | Previous / next tab |
| `Cmd+F` | Find in page |
| `Cmd+D` | Bookmark current page |
| `Cmd +` | Zoom in |
| `Cmd -` | Zoom out |
| `Cmd 0` | Reset zoom |
| `Cmd+Shift+S` | Toggle sidebar / top-bar layout |
| `Cmd+Option+I` | Web Inspector instructions |
| `Cmd+Option+J` | JavaScript console for the active tab |
| `Ctrl+Cmd+F` / `F11` | Toggle fullscreen |
| `Esc` | Exit fullscreen / close find bar |

## Stored data

Rigel stores app-level user data in `portable-data/profile/` inside this folder.
Its launcher also uses `portable-data/home/` as the Java and macOS home, which
keeps WebKit cookies, website databases, caches, and default downloads local.

| File | Contents |
|---|---|
| `settings.properties` | Preferences such as homepage, layout, search engine, proxy, zoom, and toggles |
| `bookmarks.tsv` | Saved bookmarks in `title<TAB>url` format |
| `history.tsv` | Visited pages in `epochMillis<TAB>title<TAB>url` format, capped at 2000 entries |

These files are plain text. They are safe to edit or delete; Rigel restores
defaults when a file is missing.

Before sharing or packaging the whole distribution folder, remove personal profile
data and any `hs_err_pid*.log` crash reports; both can contain visited URLs or
local filesystem paths. The built JAR itself does not include these files.

## Web inspection

Rigel marks its native WKWebViews as inspectable. Open **Tools → Web
Inspector…** for the Safari attachment steps, or use **Tools → JavaScript
Console…** to run JavaScript directly in the active tab. The built-in console
is useful for quick feature checks such as `canPlayType()` and
`MediaSource.isTypeSupported()`.

WebKit website data, such as cookies, signed-in accounts, local storage,
IndexedDB, media keys, and caches, is redirected through the folder-local home
at `portable-data/home/`. Older Rigel builds may have left data under the host
home; those files are not needed by this portable build unless the user chooses
to migrate them.

## Notes and known limitations

### Fullscreen video

Rigel uses WebKit's native fullscreen path when a page and macOS support it.
For players that reject the W3C Fullscreen API inside an embedded view, Rigel
falls back to a compatibility layer and maximizes the OS window. Behavior can
still vary slightly by site.

### Ad/tracker blocklist

The ad/tracker blocklist is host-based. It checks ordinary HTTP requests and
HTTPS `CONNECT` tunnels, but it cannot distinguish individual paths inside an
encrypted HTTPS tunnel.

The feature is conservative and disabled by default.

### Local proxy

Rigel's local proxy normally listens on `127.0.0.1:8888`. If that port is busy,
Rigel automatically chooses an available loopback port instead of failing to
launch. On macOS 14 or newer, WKWebView traffic is routed through it.

The optional host and port fields in Settings configure a separate upstream
proxy. Leave them blank for LittleProxy to connect to sites directly.

`localhost` and `127.0.0.1` always connect directly beyond the local proxy hop.
Entering a private address such as `192.168.1.254`, `10.0.0.1`, or a `.local`
hostname in the address bar defaults to HTTP because many router/device pages
do not serve HTTPS. An explicitly typed `http://` or `https://` is always kept.

### AV1 and media performance

Rigel does not decode or transcode video in Java or in its proxy. HTTPS media
passes through as an opaque tunnel, while Apple's WebKit media process selects
the codec and hardware decoder. The media diagnostics panel reports page-level
AV1 support and dropped-frame information; it cannot force AV1 on hardware or
macOS versions where WebKit does not offer it.

### Rebuilding

`./build.sh` rebuilds the native bridge and JAR with the bundled JDK. Native
libraries are stamped with a macOS 12 deployment target so a build made on a
newer Mac remains loadable on supported older systems. Rebuilding the native
code requires Apple's Command Line Tools, but running the packaged browser does
not.
