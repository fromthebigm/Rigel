# Rigel (Java version)

A lightweight macOS desktop browser with an SWT interface, Apple's modern
`WKWebView` renderer, and a built-in local proxy.

## Running

Ensure you have a terminal (or VS Code). Download the latest `Rigel Dist` zip
from the releases.

Set your path to the rigel dist folder:

```
cd "Replace this quotation with your rigel dist folder"
```

The original SWT renderer remains available only as an explicit override;
normal launches never silently downgrade from WKWebView:

```
./jdk/Contents/Home/bin/java -XstartOnFirstThread -Drigel.browser.backend=swt -jar ./rigel-wk-fat.jar
```

## Features

- **Tabbed browsing** with two layouts: a combined top bar (Chrome/Arc style)
  or a vertical sidebar. Toggle with `Cmd+Shift+S` or in Settings.
- **Multiple windows** with `Cmd+N`; windows share settings, history,
  bookmarks, WebKit resources, and the single LittleProxy instance.
- **Persistent settings** stored in `~/.rigel/` (see below). Your layout,
  homepage, search engine, proxy, zoom, and toggles survive restarts.
- **Downloads** to a configurable folder, with an optional save-as prompt.
- **Bookmarks** (`Cmd+D` to toggle the current page).
- **History** of visited pages.
- **Find in page** (`Cmd+F`).
- **Zoom** (`Cmd +`, `Cmd -`, `Cmd 0`).
- **Configurable search engine** (any URL template with `%s` for the query).
- **Optional ad/tracker blocklist** — off by default, toggle in Settings.
- **Upstream proxy** support, with automatic direct routing for `localhost`.

## Keyboard shortcuts

| Shortcut | Action |
|---|---|
| `Cmd+N` | New window |
| `Cmd+T` | New tab |
| `Cmd+W` | Close tab |
| `Cmd+Shift+W` | Close window |
| `Cmd+L` | Focus address bar |
| `Cmd+R` | Reload |
| `Cmd+H` | Home |
| `Cmd+F` | Find in page |
| `Cmd+D` | Bookmark current page |
| `Cmd +/-/0` | Zoom in / out / reset |
| `Cmd+Shift+S` | Toggle sidebar / top-bar layout |
| `F11` | Toggle fullscreen |
| `Esc` | Exit fullscreen / close find bar |

## Stored data (`~/.rigel/`)

| File | Contents |
|---|---|
| `settings.properties` | All preferences (homepage, layout, search, proxy, zoom, toggles) |
| `bookmarks.tsv` | Saved bookmarks (`title<TAB>url`) |
| `history.tsv` | Visited pages (`epochMillis<TAB>title<TAB>url`), capped at 2000 |

These are plain text and safe to edit or delete; defaults are restored if a
file is missing.

## Notes and known limitations

- **Fullscreen video is emulated.** Embedded web views are not always treated as a
  top-level browsing context, so the W3C Fullscreen API (`requestFullscreen()`)
  is always rejected by the engine. Rigel works around this by mocking the
  Fullscreen API in JavaScript and maximizing the OS window, which makes
  players (YouTube, Twitch, etc.) present fullscreen. This is the only viable
  approach in this embed; behavior can vary slightly by site.
- **The ad/tracker blocklist is host-based.** It checks ordinary HTTP requests
  and HTTPS `CONNECT` tunnels, but cannot distinguish individual paths inside
  an encrypted HTTPS tunnel. The feature is conservative and disabled by default.
- **Local proxy** listens on `127.0.0.1:8888`, and WKWebView traffic is always
  routed through it on macOS 14 or newer. The optional host/port fields in
  Settings configure a separate upstream proxy; leave them blank for
  LittleProxy to connect to sites directly. `localhost`/`127.0.0.1` always
  connect directly beyond the local hop.
