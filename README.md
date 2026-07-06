# Rigel

Rigel is a lightweight macOS desktop browser built with a Java/SWT interface,
Apple's modern `WKWebView` renderer, and a built-in local proxy.

## Running Rigel

Download and unzip the latest `Rigel Dist` release, then open Terminal and
enter the distribution folder:

```bash
cd "replace this with your Rigel dist folder"
```

Launch Rigel with the  command directly:

```bash
./jdk/Contents/Home/bin/java -XstartOnFirstThread -cp "./target/rigel.jar:./lib/*" RigelApp
```

The original SWT renderer is still available only as an explicit override.
Normal launches use WKWebView and do not silently downgrade.

## Features

- Tabbed browsing with either a combined top bar or a vertical sidebar.
- Multiple windows with `Cmd+N`.
- Shared settings, history, bookmarks, WebKit resources, and one LittleProxy
  instance across all windows.
- Persistent settings stored in `~/.rigel/`.
- Downloads to a configurable folder, with an optional save-as prompt.
- Bookmarks with `Cmd+D`.
- History of visited pages.
- Find in page with `Cmd+F`.
- Zoom controls with `Cmd +`, `Cmd -`, and `Cmd 0`.
- Configurable search engine using any URL template with `%s` for the query.
- Optional host-based ad/tracker blocklist, disabled by default.
- Upstream proxy support, with automatic direct routing for `localhost`.

## Keyboard shortcuts

| Shortcut | Action |
|---|---|
| `Cmd+N` | New window |
| `Cmd+T` | New tab |
| `Cmd+W` | Close tab |
| `Cmd+Shift+W` | Close window |
| `Cmd+L` | Focus address bar |
| `Cmd+X` | Cut |
| `Cmd+C` | Copy |
| `Cmd+V` | Paste |
| `Cmd+A` | Select all |
| `Cmd+R` | Reload |
| `Cmd+H` | Home |
| `Cmd+F` | Find in page |
| `Cmd+D` | Bookmark current page |
| `Cmd +` | Zoom in |
| `Cmd -` | Zoom out |
| `Cmd 0` | Reset zoom |
| `Cmd+Shift+S` | Toggle sidebar / top-bar layout |
| `F11` | Toggle fullscreen |
| `Esc` | Exit fullscreen / close find bar |

## Stored data

Rigel stores user data in the rigel folder, history is saved but signed in accounts, cookies and caches are saved in library webkit folder

| File | Contents |
|---|---|
| `settings.properties` | Preferences such as homepage, layout, search engine, proxy, zoom, and toggles |
| `bookmarks.tsv` | Saved bookmarks in `title<TAB>url` format |
| `history.tsv` | Visited pages in `epochMillis<TAB>title<TAB>url` format, capped at 2000 entries |

These files are plain text. They are safe to edit or delete; Rigel restores
defaults when a file is missing.

## Notes and known limitations

### Fullscreen video

Fullscreen video is emulated. Embedded web views are not always treated as a
top-level browsing context, so the engine may reject the W3C Fullscreen API
(`requestFullscreen()`).

Rigel works around this by mocking the Fullscreen API in JavaScript and
maximizing the OS window. This makes players such as YouTube and Twitch present
fullscreen, but behavior can vary slightly by site.

### Ad/tracker blocklist

The ad/tracker blocklist is host-based. It checks ordinary HTTP requests and
HTTPS `CONNECT` tunnels, but it cannot distinguish individual paths inside an
encrypted HTTPS tunnel.

The feature is conservative and disabled by default.

### Local proxy

Rigel's local proxy listens on `127.0.0.1:8888`. On macOS 14 or newer, WKWebView
traffic is routed through it.

The optional host and port fields in Settings configure a separate upstream
proxy. Leave them blank for LittleProxy to connect to sites directly.

`localhost` and `127.0.0.1` always connect directly beyond the local proxy hop.
