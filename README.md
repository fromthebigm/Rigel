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

The packaged distribution is self-contained: launch uses its bundled arm64 JDK
and paths are resolved from the distribution folder. The source repository does
not track that large JDK bundle; source checkouts can provide `jdk/`, set
`JAVA_HOME`, or use a system Java installation.

Or run the same command directly:

```bash
./jdk/Contents/Home/bin/java -XstartOnFirstThread -cp "./build/classes:./lib/*" RigelApp
```

The original SWT renderer is still available only as an explicit override.
Normal launches use WKWebView and do not silently downgrade.

For local visual diagnostics, launch `./aitest.sh`. That launcher uses the
same browser, profile, proxy, and renderer but makes only that process visible
to macOS screenshots and screen recordings. Normal `./run-rigel.sh` windows
keep Rigel's capture protection enabled. `./test-rigel.sh` remains an alias for
the diagnostic launcher.

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
- Optional built-in WebKit ad/tracker content blocker, disabled by default.
- Always-on local SOCKS5 browser ingress with TCP and UDP support, Chrome-style
  direct routing, optional HTTP/SOCKS4/SOCKS5 upstream chaining, and direct
  handling for `localhost`.
- Native-first video fullscreen and built-in media diagnostics (`Cmd+Option+M`).
- Standards-compatible HTML popovers for player settings and other overlay UI.
- TikTok compatibility for malformed HE-AACv2 timing, sticker comments, and
  transient empty-comment results.
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
WebKit cookies, signed-in accounts, website databases, caches, and default
downloads use the normal macOS home folder and are not stored with Rigel.

| File | Contents |
|---|---|
| `settings.properties` | Preferences such as homepage, layout, search engine, proxy, zoom, and toggles |
| `bookmarks.tsv` | Saved bookmarks in `title<TAB>url` format |
| `history.tsv` | Visited pages in `epochMillis<TAB>title<TAB>url` format, capped at 2000 entries |
| `hs_err_pid*.log` | A diagnostic report created only if the JVM or native bridge crashes |

These files are plain text. They are safe to edit or delete; Rigel restores
defaults when a file is missing.

Before sharing or packaging the whole distribution folder, remove personal
app-level profile data and any `hs_err_pid*.log` crash reports; both can contain
visited URLs or local filesystem paths. The built JAR itself does not include
these files. A `portable-data/home/` folder left by an older build is no longer
used, but Rigel does not delete or migrate it automatically.

The repository ignores `portable-data/` completely. Personal settings, history,
bookmarks, cookies, signed-in accounts, WebKit databases, caches, downloads, and
session state must never be committed. Fresh checkouts create local defaults at
runtime.

## Web inspection

Rigel marks its native WKWebViews as inspectable. Open **Tools → Web
Inspector…** for the Safari attachment steps, or use **Tools → JavaScript
Console…** to run JavaScript directly in the active tab. The built-in console
is useful for quick feature checks such as `canPlayType()` and
`MediaSource.isTypeSupported()`.

WebKit website data, such as cookies, signed-in accounts, local storage,
IndexedDB, media keys, and caches, is managed by macOS WebKit under the user's
normal home folder. In this Java-launched build it is usually under
`~/Library/WebKit/com.azul.zulu.java/WebsiteData`. The existing
`portable-data/home/` directory from older portable builds is left untouched.

## Notes and known limitations

### Fullscreen video

Rigel uses WebKit's native fullscreen path when a page and macOS support it.
For players that reject the W3C Fullscreen API inside an embedded view, Rigel
falls back to a compatibility layer and maximizes the OS window. Behavior can
still vary slightly by site.

### Ad/tracker blocking

Rigel cannot install Chrome or Firefox extensions such as uBlock Origin because
its renderer is an embedded Apple `WKWebView`, not an extension-capable Chrome
or Firefox process. The Privacy setting instead enables WebKit content-blocking
rules, including cosmetic hiding for common ad containers. Rigel's always-on
local proxy also applies a hostname blocklist to ordinary HTTP requests and
HTTPS `CONNECT` tunnels, though that layer cannot distinguish individual paths
inside an encrypted HTTPS tunnel.

This is deliberately smaller and more conservative than uBlock Origin. It is
disabled by default.

### Local proxy

Rigel's local proxy normally listens on `127.0.0.1:8888`. If that port is busy,
Rigel automatically chooses an available loopback port instead of failing to
launch. Each WKWebView uses a separate loopback-only SOCKS5 ingress. TCP
`CONNECT` requests pass from that ingress through LittleProxy, preserving the
hostname blocklist, direct routing rules, and selected upstream. In Direct mode
LittleProxy connects to destination servers itself; when an upstream is
selected, it chains the same TCP requests through that upstream.

The browser ingress implements SOCKS5 `UDP ASSOCIATE` as well. In Direct mode,
Rigel resolves domain-form destinations on the proxy side and relays UDP from a
network-capable socket. With a SOCKS5 upstream, it opens an upstream UDP
association and keeps the original SOCKS5 datagram framing. HTTP and SOCKS4
upstreams cannot carry UDP, so Rigel rejects the UDP association instead of
silently leaking it around the selected route; web protocols that support it
can then fall back to TCP.

This still is not a VPN or a guarantee that every packet from WebKit uses the
proxy. A live WKWebView WebRTC/ICE test on macOS produced working UDP candidates
without asking the configured SOCKS5 proxy for a UDP association. In other
words, normal HTTP/HTTPS website requests use Rigel's proxy, but WebRTC can use
the Mac's direct network path. That preserves calling and peer-to-peer site
functionality, but it can reveal the direct network path to those sites. Fully
forcing that traffic through one tunnel requires a system-level VPN or Network
Extension, which Rigel intentionally does not install.

The optional host and port fields in Settings configure an upstream proxy.
Leave the mode set to Direct to keep all traffic on the local Rigel proxy while
having LittleProxy connect to sites without a system or upstream proxy.

An upstream HTTP or SOCKS proxy changes where Rigel sends browser traffic, but
does not make the connection anonymous or add encryption to the proxy protocol
by itself. HTTPS still protects page contents. The proxy operator may see
destination hosts, other apps are unaffected, and this feature should not be
described as a VPN or as traffic invisibility.

`localhost` and `127.0.0.1` always connect directly beyond the local proxy hop.
Entering a private address such as `192.168.1.254`, `10.0.0.1`, or a `.local`
hostname in the address bar defaults to HTTP because many router/device pages
do not serve HTTPS. An explicitly typed `http://` or `https://` is always kept.

### AV1 and media performance

Rigel does not decode or transcode video in Java. HTTPS media passes through the
local proxy as an opaque CONNECT tunnel, while Apple's WebKit media process
selects, decodes, and composites it. The media diagnostics panel reports
page-level AV1 support and dropped-frame information; it cannot force AV1 on
hardware or macOS versions where WebKit does not offer it.

### Rebuilding

`./build.sh` rebuilds the native bridge and JAR with the bundled JDK. Native
libraries are stamped with a macOS 12 deployment target so a build made on a
newer Mac remains loadable on supported older systems. Rebuilding the native
code requires Apple's Command Line Tools, but running the packaged browser does
not.

`./tests/run-proxy-core-test.sh` checks the HTTP route, SOCKS5 TCP and UDP
relays, upstream behavior, and port cleanup. `./tests/run-wk-proxy-test.sh`
additionally launches a real WKWebView and checks proxied HTTPS, WebRTC behavior,
and a live TikTok page/video smoke test. `./tests/run-wk-lifecycle-stress-test.sh`
exercises many tabs, deferred WebContent recovery, Google Account popup routing,
rapid popup teardown, and capture-protection discovery.
`./tests/run-wk-popover-fullscreen-test.sh` verifies that the bundled Java host
exposes WebKit's HTML Popover API, that a player menu escapes transformed and
clipped controls in app fullscreen, and that fullscreen capture protection
remains configured with `NSWindowSharingNone`.
