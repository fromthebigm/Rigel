# AGENTS.md — Rigel (macOS portable WebKit browser)

Java/SWT shell hosting Apple `WKWebView` through a JNA native bridge, with a built-in
LittleProxy/SOCKS5 proxy chain. Distribution is a self-contained portable folder with a
bundled Azul JDK. Presented to macOS as **"Google Chrome"** with a Weather preset icon
(see `macos_presentation_identity.h`) — this identity is a hard product requirement;
never change menu-bar/Dock names away from Google Chrome.

> **✅ INCIDENT RESOLVED (2026-09-25), shipped as `portable-2026.09.26`:** the ported
> launchers crashed on the user's second MacBook after the 09.25 update ("code error 11").
> Root cause found by direct repro, fixed, and real-flow-verified for all 8 ports. Before
> touching anything port-related, read **"PORT INCIDENT 2026-09-25 → .26: the MRC
> use-after-free + minos regression"** at the bottom of this file — the process rules at
> the end of that section are load-bearing (they exist because this incident only shipped
> broken builds that were never launched even once).

## Hard constraints (violating these ruins trust — learn before touching anything)

- **Never modify `jdk/Contents/Home/bin/java`.** It is Azul Developer-ID signed. An
  earlier plist-patch experiment was explicitly rejected ("hell no") and reverted.
- **`run-rigel.sh` stays battery-era pristine.** All user-facing launch logic (portable
  HOME redirect, migrations, cookie-jar rescue) goes in `run.sh`, which execs it.
- **Never commit or ship `portable-data/`** (settings, history, bookmarks, cookies.store,
  cookie-import.store, cookie-jar-imports.properties, WebKit stores) or
  `Support/cookie-jar.binarycookies` / `Support/cookies.store`. Release zips must not
  contain user data.
- Release zips are **runtime-only**. Keep: `run.sh`, `run-rigel.sh`, `jdk/`, `lib/`,
  `target/`, `classes/`, `resources/`, `Support/` (empty), `*.icns`, `*.dylib`,
  preset PNGs, `Weather.app/`, `Rigel.app/` stub. Exclude: `src/`,
  `native/`, `tests/`, `build.sh`, `test-rigel.sh`, `aitest.sh`, `*.md`, `.git/`,
  `.gitignore`, `macos_presentation_identity.h` (source header — it's compiled INTO
  `dock_identity.dylib`; nothing reads it at runtime; dropped from zips 2026-09-27),
  `build/`, `icon-flow/`, `portable-data/`, and both runtime `Support/` files
  (`cookie-jar.binarycookies`, `cookies.store`) — the zip ships `Support/` EMPTY.
- **Port zips (2026-09-26-r1 rule): no dev/source files at all.** Payload roots must
  contain zero `*.md`, `.gitignore`, `.gitattributes`, `*-build.json`, loader sources
  (`*.mm`/`*.h`/`*.swift`), or build scripts — runtime `.py` launchers stay (they're the
  entry point; they carry no secrets — the ticket key lives in BigM's Support and the
  update token is compiled into BigM's dylib). Legal notices *inside* `.app` bundles stay
  put. Asset basenames must match the catalog's `assetPattern` exactly — a stripped zip
  uploaded as `Chiaki.zip` instead of `Chiaki-portable-*.zip` silently breaks updates
  (burned once today, fixed same-day).
  **2026-09-27 tightening:** the zero-list also covers the legacy in-zip updater stacks
  (`update_*.py`, `update.sh`, `setup*.sh`, `get_*.sh`), dev harnesses (`test.sh`),
  example configs (`*.example.json`), server-side ops scripts (Moonlight `relay/*.py` —
  the *client* relay lives inside `moonlight_launch.py`; keep the `relay/` dir itself,
  `relay/identity.json` is read at runtime), `.DS_Store`, and `*.dSYM`. "Runtime .py"
  means the catalog `marker` entry file plus its import/subprocess closure — grep before
  deleting (e.g. Ryujinx `run_version.py` imports `run_ryujinx_embedded`; Prism's
  `launch_prism.py` needs `direct_proxy.py`). Payloads shipping a root `version.txt`
  (Rigel, Code) must stamp it with the release's own tag — BigM's update flow verifies
  the landed stamp against `release.tagName` and rolls back on mismatch. Boot
  verification must be a TRUE first-run from a pristine extract, never a directory that
  has run before: the 09.27 audit found two live zips that only worked on reused owner
  folders — Moonlight shipped `Moonlight.app.zip` requiring a manual `setup.sh` step
  (now ships pre-expanded, codesign re-verified) and Ryujinx's validator asserts
  `userdata/` + `userdata-1.1.1397/` dirs no zip ever contained (now shipped empty).
- **rsync gotcha:** unanchored `--exclude 'native'` also strips `classes/native/`
  (dropping the bridge dylib from the zip). Always anchor: `--exclude='/native'`.
- Deep-audit before shipping; the user has been burned by rushed broken releases.
  Test before cutting a release — the harness recipes below make this cheap.

## Build & test

```zsh
./build.sh        # native/build-wkwebview.sh (clang -Werror, min macOS 12) + javac
./test-rigel.sh   # alias of aitest.sh — NOT a test suite
tests/run-proxy-core-test.sh          # proxy core suite (builds, compiles, runs)
tests/run-wk-lifecycle-stress-test.sh # WKWebView lifecycle stress
```

- `build.sh` emits Java **21** class files (major 65) from the bundled JDK 25 so the
  jar also runs on system JDK 21. Don't drop `--release 21`.
- The dylibs are ad-hoc signed (`codesign --force --sign -`) after every build.
- Launch uses `-XstartOnFirstThread` (SWT/Cocoa requirement): see `run-rigel.sh`.
- Settings root resolves from the jar location (`Settings.distRoot()`); the
  `rigel.root` system property can override it. Profile lives in
  `portable-data/profile/` (settings.properties, history.tsv, bookmarks.tsv,
  cookies.store).
## Launch scripts & diagnostics
|---|---|
| `run.sh` | User-facing: redirects `HOME`+`CFFIXED_USER_HOME` into `portable-data/` **before** java starts, exports `RIGEL_REAL_HOME` so the browser can sync outside sign-ins in, migrates legacy stores once (jar + newest legacy WebsiteData), backs up/restores the cookie jar via `Support/`, then execs `run-rigel.sh`. |
| `run-rigel.sh` | Pristine launcher: unsets `__CFBundleIdentifier`, bundled-JDK priority, execs java. |
| `aitest.sh` | Same app but visible to screenshots (`RIGEL_LAUNCH_MODE=diagnostic-capture`). Normal launches keep capture protection ON. |

Diagnostic env vars: `RIGEL_PROXY_DIRECT=1` (bypass proxy chain entirely — A/B testing),
`RIGEL_KEY_DIAG` (arrow-key diagnostics), `RIGEL_COOKIE_DEBUG` (writes
`portable-data/profile/cookies.debugNN.store` snapshots at fixed seconds after launch,
for watching replayed cookies live or die over the session).

## Architecture map

- `src/ui/RigelApp.java` — main; loads `dock_identity.dylib` (presentation identity),
  sets app name, SWT TaskBar icon, runs `BrowserApplication`.
- `src/ui/BrowserApplication.java` — one SWT event loop, window list, proxy lifecycle,
  cookie snapshot/restore hooks (restore before first window; 45s autosave; save in
  `finally`; guarded JVM shutdown hook for TERM/kill paths).
- `src/ui/BrowserUI.java` / `TabManager.java` — window + tabs; tab-sleep engine puts
  background tabs on `about:blank` (`rigel://home` is the built-in start page).
- `src/ui/MacWKWebView.java` — JNA host. `Bridge extends Library` mirrors EVERY exported
  native function; dylib extracted from the jar to `classes/native/`. To add a native
  function: implement in `native/RigelWKWebView.m` (with
  `__attribute__((visibility("default")))`), add to `Bridge`, wrap in a static helper.
- `src/ui/CookieImport.java` — sync-in: decodes real-home binarycookies jars
  (`RIGEL_REAL_HOME`, per-identity sha256 fingerprint stamps) into a transient XML
  plist replayed before the portable snapshot.
- `native/RigelWKWebView.m` — the WKWebView bridge: view create/destroy, proxy config,
  downloads (explicit `startDownloadUsingRequest:` — the nav→download morph
  instant-cancels at 0 bytes on this WebKit; response/action policy sites both route
  through it), popups, snapshots, cookie save/restore, storage-sweep disable,
  `rigel_webkit_memory_bytes`. Diag channels: `RIGEL_DL_DIAG` (downloads),
  `RIGEL_KEY_DIAG`, `RIGEL_LOGIN_DIAGNOSTICS`, `RIGEL_COOKIE_DEBUG`,
  `RIGEL_PROXY_DIRECT`.
- `native/RigelDockIdentity.m` + `dock_identity.dylib` — installs presentation identity
  via the shared header; includes the root-level `macos_presentation_identity.h`.
- `src/proxy/` — LittleProxy + `LoopbackSocks5Server` (browser ingress), SecureDns/DoH,
  blocklist. WKWebView routes through the loopback SOCKS5; `localhost` handled direct.
  `SecureDns` resolves BOTH families but rotates **_within IPv4 whenever any A record
  exists`** — never hand a dead-IPv6 network (that system or the DoH answer) a bare-AAAA
  CONNECT; sites like tailscale.com break otherwise. The dual-stack regression test lives
  in `tests/java/proxy/SecureDnsCoreTest.java`.

### Native teardown rule (learned the hard way)

`rigel_wk_destroy` is **never synchronous**: it can be reached from inside a WebKit
delegate stack via JNA callbacks, so view/controller release is deferred through
`dispatch_async` on the main queue (`destroyed` flag guards double-release). Keep that
pattern for anything reachable from WebKit callbacks.

### Async AppKit/WK patterns

WKHTTPCookieStore (and similar) completions land on the **main queue**. From the SWT
main thread you must pump the runloop — a semaphore wait deadlocks:
`[[NSRunLoop mainRunLoop] runMode:NSDefaultRunLoopMode beforeDate:…]` with a deadline.
From other threads (e.g. JVM shutdown hook): `dispatch_async(main_queue, work)` then
semaphore-wait with timeout. Both paths are implemented in `RigelCookieStoreFetchAll()`
and `rigel_wk_cookies_restore()` — copy them.

## Login persistence (r7+) — the FULL failure set, all fixed; don't regress

Four independent bugs stacked up to produce "logins don't save". All four are fixed;
each fix has a dedicated trap for the next agent:

1. **Frozen jar** (r7): WebKit's NetworkProcess only flushes the legacy
   `HTTPStorages/<id>.binarycookies` jar when it exits cleanly; this host exits the JVM
   with the last window, so the jar on disk froze forever. `httpstorages.sqlite` only
   ever holds an `alt_services` table — no cookies table — so don't chase it.
2. **ITP sweep** (the big one, found by RIGEL_COOKIE_DEBUG timeline): with
   resource-load statistics enabled, the data store DELETES cookies for every domain
   without recorded user interaction — which after any snapshot replay is ALL of them.
   Observed: 157 replayed cookies present at +0s and +4s, gone by +17s, while
   same-session-visited domains survived. This also poisoned the original r7
   "verification": the harness reloaded the tested site via session restore, so the
   cookies looked restored but were actually re-fetched. **A real restore test must use
   a launch that loads NO external page** (`homepage=rigel://home`). Fix:
   `rigel_wk_disable_resource_load_statistics()` (SPI-gated via respondsToSelector:)
   before the replay in `BrowserApplication.restoreCookies()`.
3. **Death-fetch clobber**: the TERM-path save's cross-thread `getAllCookies` during
   JVM/AppKit teardown can return the STALE on-disk view and overwrite the good
   snapshot (seen: 161 live -> wrote the old 4). Fix: 45 s display-timer autosave
   (first tick +20 s) on the reliable main-thread path; the shutdown hook SKIPS its
   fetch whenever the last successful save is fresh (< 1.5 x autosave period). Only
   young sessions still take the death-fetch risk.
4. **Outside sign-ins stranded**: pre-portable identities left jars in the real home
   (`~/Library/HTTPStorages/*.binarycookies` for `com.rigel.weather-host`,
   `net.java.openjdk.java`, ...). Fix: `CookieImport` decodes any jar whose sha256
   changed since its stamp, writes an XML plist same-shaped as `rigel_wk_cookies_save`,
   and replays it BEFORE the portable snapshot (portable wins conflicts; fingerprint
   stamps mean an unchanged jar is never re-imported, so portable logouts aren't
   resurrected). binarycookies layout: file header big-endian ("cook", page count,
   page sizes); page tables + records little-endian on modern macOS — BOTH orders
   appear in one file.

Persistence topology after all four fixes:
- `portable-data/profile/cookies.store` — primary snapshot (XML plist of NSHTTPCookie
  property dicts; keys: Created real, Domain, Expires date, HttpOnly/Secure as "TRUE"
  strings, Name, Path, Value, Version "1").
- `Support/cookies.store` — fallback mirror written after every successful save;
  restored when the primary is missing/unreadable ("exactly how the normal way would"
  fallback floor). Top-level Support/, so it survives a wiped profile.
- `run.sh` cookie-jar backup + one-time legacy migration — compatibility floor for the
  pre-r7 jar world. The snapshot, not the jar, is the source of truth since r7.
- The whole folder is machine-independent: copying it moves every session.

**Harness recipe (no GUI needed) — run after touching anything cookie-related:**

```zsh
# boot 1: force a deterministic cookie-setting page load:
python3 - <<'EOF'
import pathlib
p = pathlib.Path("portable-data/profile/settings.properties")
p.parent.mkdir(parents=True, exist_ok=True)
p.write_text("#Rigel\nhomepage=https://www.wikipedia.org/\n")  # clears saved session too
EOF
(./run.sh >/tmp/rigel-t1.log 2>&1 &); sleep 30
kill -TERM $(pgrep -f rigel.jar | head -1); sleep 10
python3 -c "import plistlib;d=plistlib.load(open('portable-data/profile/cookies.store','rb'));print(len(d),[c['Name'] for c in d])"
# boot 2 with homepage=rigel://home (loads NO page) must STILL show the boot-1
# cookies — if they vanish the restore (or the sweep disable) regressed.
```

## Presentation identity — the header bug class (fixed; don't regress)

`macos_presentation_identity.h` implements the Google Chrome name + adaptive
(light/dark/system) icon system. It is **duplicated across repos** — BigM Launcher
(`Native/include/`), this repo (root), and compiled into every ported launcher's dylib.
Any fix here must be propagated to the other copy and eventually re-cut ports.

Two bugs lived here for weeks and caused "icon never changes" reports:

1. `presentation_load_icon_environment()` read env/preset values into locals but never
   assigned `presentationIconLightPath/DarkPath` — overrides were silently discarded.
2. `presentation_apply()` checked those (empty) globals BEFORE ever loading them, so the
   first apply permanently cached the bundled icon. Fix: call
   `presentation_load_icon_environment()` at the top of `presentation_apply()`.

Preset resolution uses `dladdr()` — preset PNGs (`weather-light.png`, etc.) must sit
next to the dylib at the package root. Env contract (set by BigM for user-picked
icons): `BIGM_PRESENTATION_ICON_LIGHT/DARK/MODE` (mode: `light|dark|system`); env wins
over presets.

## Storage identity & debugging locations

Web/storage keys on `com.azul.zulu.java` (the bundled JDK's embedded bundle ID wins
after `__CFBundleIdentifier` is unset). Under the redirected HOME everything lives in
`portable-data/Library/…`; real-home copies stay as migration fallback:

- `Library/HTTPStorages/com.azul.zulu.java.binarycookies` — legacy cookie jar (frozen
  since the deep-audit release; kept only as rescue/migration input)
- `Library/HTTPStorages/com.azul.zulu.java/httpstorages.sqlite` — alt_services only
- `Library/WebKit/com.azul.zulu.java/WebsiteData/Default/<hash>/<hash>/origin` —
  per-origin partitions; the `origin` files are printable and prove page loads

Useful probes: `lsappinfo list | grep "Google Chrome"` (identity live),
`lsof -p <WebKit.Networking pid>` (what NetworkProcess actually holds open),
`pgrep -f rigel.jar`. There is also `com.rigel.weather-host` legacy identity from a
reverted experiment.

## Headless verification: what works, what lies

- No PyObjC/Quartz in CLT python3 — write small **swift scripts** for AppKit checks
  (dlopen a dylib, call a C export, read `NSApp.applicationIconImage`, write PNG to
  /tmp and view it).
- **`NSRunningApplication.icon` / `NSWorkspace` dumps do NOT reliably reflect
  `setApplicationIconImage`** for JNA-hosted processes — they show the bundle icon
  (python rocket, java exec). In-process swift `dlopen` tests are the ground truth.
- Screenshots are TCC-blocked for normal runs — that's why `aitest.sh` exists.
- Verify released zips by unzipping to /tmp and booting from there before publishing.

## Releases

Tag `portable-YYYY.MM.DD[-rN]`, zip `Rigel-portable-<tag>.zip` (240MB with JDK) built with:

```zsh
ditto -c -k --norsrc --noextattr --noqtn --noacl --keepParent <folder> <zip>
./release-validate.sh <zip> <tag> <app-id>
```

`version.txt` in the zip = tag + date (two lines). Publish with `gh release create` to
`fromthebigm/Rigel` (gh CLI is authed). Commit messages here are long and narrative
("r7: account sessions actually persist now. …") — match that style. If a fix lands
after a zip was uploaded, rebuild the zip with the fixed dylibs and replace the asset
under the same tag.

## Ecosystem runtime-family contract (settled 2026-09-26 — do not re-litigate)

The owner's rule for what may sit under BigM: every playable thing must bottom out in the
two blessed identity-hardened runtime families — **hardened python** or **hardened Java** —
plus Rigel's carved exception (WKWebView Content/Networking daemons are system-spawned and
present no identity of their own). Compliant shapes: in-process hosting (dlopen+LC_MAIN,
the ports' loader pattern), and hardened runtime **children** (Prism→java, Winebottle→wine)
where the parent never exec-replaces its own image. NOT compliant: third-party identity
trees that merely get launched (raw Wine without the injected loader, raw Roblox/Steam
clients, anything Electron/Chromium-family like Heroic). There is no valid "shim or exec"
workaround — the host keeps its identity by construction.

Proven verdicts from the prototype session (final reading: the CONTENT process must BE the
blessed family — python or java — not merely be spawned by it):
- **Wine is OUT (shipped then withdrawn on the strict reading).** The build worked —
  hardened-python parent → wine64 child with the identity loader injected, proven from the
  release zip — but the child presents as its own binary ("wine64" in ps/Activity Monitor),
  which the owner ruled out: a recognizable wine process fails the contract, parentage
  notwithstanding. A python-dlopen'd wine is physically impossible: working macOS wine is
  x86_64, python is arm64, and an arm64 process cannot execute x86_64 code (Rosetta is
  per-process, not per-page). An arm64-native wine would only run Windows-ARM PEs — a
  game catalog that doesn't exist — and an in-process VM satisfies the letter of "inside
  python's address space" but Windows guests on Virtualization.framework have no 3D driver,
  so games software-render (unplayable). fromthebigm/Winebottle and its releases stay up
  as the proof endoskeleton, but the app was REMOVED from BigM's catalog (0.6.9 / tag
  portable-2026.09.26-r4) — do not re-add without owner sign-off on "child processes
  presenting injected identity count as compliant"; if that lands, the port un-shelves in
  one catalog line.
- **Roblox is out**: hardened runtime + library validation with no
  disable-library-validation entitlement; it silently drops all DYLD_* (DYLD_PRINT produces
  zero output), and ad-hoc re-signed copies still refused injection. Any deeper route is
  anti-tamper territory (Byfron) — permanent no.
- **Steam (CEF) and Heroic (Electron)**: banned by the family rule itself, no prototype.
- Self-updating titles (Roblox-style) belong to the catalog's `selfUpdating: true` mode
  (0.6.7+): BigM installs/launches, never version-polices.

## Cross-repo context (this session's ecosystem)

- **BigM Launcher** (`~/Downloads/BigMLauncher`) is the parent launcher: it sets the
  icon env contract above, captures-protects itself, gates behind a universal product
  key (PBKDF2 600k in the login keychain — one-time activation, never re-prompt).
  Its default icon is the **Voice Memos** pair; Rigel's is **weather**.
- Ecosystem header fixes were re-cut on 2026-09-24/25 into: BigM dylib (r4),
  `dock_identity.dylib` here (r7 in-place), and the ported launchers' loader dylibs
  (Chiaki, Prism*, Cemu, Azahar, Dolphin, Moonlight, Ryujinx, Code) — each repo got
  the fixed header commit + rebuilt dylib, and each release zip got the dylib swapped
  in place (digests verified). *Prism's header variant was never buggy. Ryujinx's
  loader source was lost; it's now committed to fromthebigm/Ryujinx.
- Append-only session history: `~/Downloads/BigMLauncher/SESSION-NOTES-2026-09-22.md`.
- Known user-side quirk: one Google account captcha-loops (account-side, not a code
  bug). "Logins don't save" on a machine means: check `cookies.store` exists and
  contains the site's cookies after a clean quit; use the harness above.

## PORT INCIDENT 2026-09-25 → .26: the MRC use-after-free + minos regression

**Symptom (user's second MacBook, macOS 26.2, BigM 0.6.4):** after updating all ports to
`portable-2026.09.25`, every port with a rebuilt loader (Chiaki, Azahar, Cemu, Dolphin,
Moonlight, Ryujinx) crashed at launch — the card read something like "code error 11".
Code-Zed worked (and icon picks worked). Prism ran but Change Icon did nothing. Rigel
unaffected.

**Root cause #1 (the killer) — MRC use-after-free in the icon-fix.** The 09.24/25 "icon
fix" added two lines to `macos_presentation_identity.h`: assigning the resolved env/preset
paths into the statics `presentationIconLightPath/DarkPath`. Correct under ARC. But
Cemu/Chiaki/Azahar/Dolphin/Moonlight/Ryujinx build **without `-fobjc-arc`** — under manual
retain/release those assignments stored *autoreleased* NSStrings into globals without
retaining. AppKit drains the pool at the end of the event pass; the next
`presentation_apply` (every activation / window-main) messaged a dangling pointer →
SIGSEGV. The old header never assigned those statics, which made the whole dynamic-icon
path unreachable — that's why the old builds "never worked but never crashed". Code is
built with `-fobjc-arc` (survived everywhere) and Prism was never rebuilt (ran fine).

**Proof (not theory):** a 60-line harness compiled the *actual shipped header* with the
icon env set (exit codes: old MRC **0**, shipped-09.25 MRC **139 = SIGSEGV**, shipped
under ARC **0**, fixed header under both MRC and ARC **0**), and the real Cemu flow from
the shipped 09.25 zip died in ~3 s with a macOS crash report reading
`objc_msgSend ← presentation_apply ← custom_finishLaunching ← -[NSApplication run] ←
Cemu main ← pelican_run`, `SIGNAL 11 Segmentation fault`. Same flow with the fixed dylib:
45 s soak, zero crashes, and `BIGM_PRESENTATION_DEBUG=1` shows the pick resolving.

**Root cause #2 — deployment target drift.** The same rebuilds inherited the dev
machine's default: `LC_BUILD_VERSION minos` went **13.0 → 26.0** on those five ports
(otool-verified; Moonlight pinned 11.0, Code 14.0). Harmless on macOS 26.x, fatal on
anything older. Also fixed in the same pass (pins restored).

**Prism's separate no-op:** `prism_loader.mm` only honored its own `PRISM_DOCK_ICON_PATH`
(hard-pinned to the bundled icns by `launch_prism.py`) — nothing ever read the
`BIGM_PRESENTATION_ICON_*` contract. Fixed: prism_loader resolves BigM pick → imovie
preset pair → legacy path (proven in the real flow: its native-console log prints
`Dock icon applied (BIGM_PRESENTATION_ICON_* > imovie preset > PRISM_DOCK_ICON_PATH)`).
Prism's variant has unconditional statics assignments that had the *same latent* MRC
dangle — hardened with the rest.

**The fix (all shipped in `portable-2026.09.26`):**
1. `macos_presentation_identity.h`: all statics writes route through
   `presentation_store_object` (copy-retain under MRC, strong under ARC — the bug class
   can't return regardless of build flags). New opt-in `BIGM_PRESENTATION_DEBUG=1`
   channel logs icon env/apply decisions on stderr. Propagated to: 7 port repos, Rigel
   (root), BigM (`Native/include/`), and Prism's own variant (which also gained
   `presentation_use_preset_pair()` for loaders that run their own swizzles).
2. Every port build script now passes `-fobjc-arc -fmodules` and pins
   `-mmacosx-version-min=13.0` (Moonlight 11.0, Code already 14.0+ARC). Cemu's one MRC
   `retain` is `#if __has_feature(objc_arc)`-guarded.
3. `find_main_via_lc_main` in all five dlopen+jump loaders compares **realpaths** — the
   old `strcmp` missed the payload whenever the launch path crossed a symlink
   (`/var/folders` → `/private/var/folders`), dying with "could not find entry point".
4. 09.26 zips rebuilt from the 09.25 assets + fixed dylibs, file-list-diffed (Cemu's
   stray `fish_loader.dylib` — a Dolphin build accidentally packed by the batch scripts —
   removed; only intended bytes differ).

**Testing trap that shipped this (never repeat):** the 09.25 port commits say
"verified compile-clean / export surface identical" — both true, neither is a launch.
`nm` parity and a clean build prove nothing about runtime behavior. Additional traps
encountered while fixing: (a) ad-hoc dylibs overwritten **in place** on a
quarantine/provenance-tainted path get SIGKILLed by Gatekeeper on some macOS versions —
always test from a fresh unzip, never re-swap-in-place; (b) a UI-script focus-churn
harness (System Events `set frontmost`) can wedge against modal first-run dialogs and
mimic app death; (c) Chiaki on macOS 27 showed an intermittent (~1/4) startup SIGSEGV in
`realizeClass` under `presentation_apply`'s menu walk — a Qt/NSApplication startup race
seen across builds, distinct from the incident (the incident UAF messages a freed
*NSString path*; this one realizes a broken *class*). Watch for it on user reports.

**The process rules (load-bearing):**
- Never ship a rebuilt loader dylib without launching the app's REAL flow
  (shell launcher → python host → ctypes load → payload main) from a **fresh download of
  the exact release zip**, old dylib as control, ≥15 s soak, at least once with a
  simulated BigM env (icon pick + ticket).
- After any rezip: file-list diff the new zip against the old; any unexpected delta fails
  the release (catches stray-file contamination like Cemu's fish_loader).
- Content changes require a NEW tag. Same-tag asset swaps are invisible to installed
  updaters (burned twice).
- The dlopen entry lookup is realpath-compared loaders-wide; keep it that way.
