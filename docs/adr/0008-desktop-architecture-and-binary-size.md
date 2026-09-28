# 0008. Desktop architecture and binary size control

- **Status:** Proposed. The owner decides the architecture below.
  - The **Clojure-first principle** it applies is already the owner's
    decision (2026-09-28) and is in force: see "The constraint".
  - The removal of `wmark-tui` (ADR 0010) is decided and done.
- **Date:** 2026-09-28

## Context

The owner asked two questions before committing to M3 (ClojureDart and
Flutter, [ROADMAP](../ROADMAP.md), Assessment 1):
- Is there a severe risk of binary bloat once a GUI joins GraalVM, Clojure
  and the video backend?
- If so, what is the better alternative, for example a light WebView shell
  around the web UI?

### The constraint: Clojure first (owner, 2026-09-28)

Our primary language stays Clojure, whatever hosts the desktop app. This
is an invariant, for long-term maintainability:
- **Product code is written in Clojure dialects:** Clojure on the JVM and in
  native images, ClojureScript or ClojureDart where a host needs them.
- **We never write TypeScript, JavaScript or Rust.** The web UI already
  forbids JavaScript of our own (decision 1).
- **What stays outside the rule:**
  - third-party code we build but don't write, such as FFmpeg and the
    webview library below;
  - the C ABI header and its test double (`native/`);
  - platform engines behind the C ABI where an OS offers no other route
    (decision 3), kept to compositing (ADR 0006).

### What a download weighs today (measured 2026-09-28)

Uncompressed sizes of the `v0.1.0-rc.1` bundle, x86-64:

| Part | Linux | Windows | Share |
|---|---|---|---|
| `wmark` (engine, native image) | 63.0 MB | ≈63 MB | 18% |
| `wmark-tui` (native image) | 57.0 MB | ≈57 MB | 16% |
| `bin/ffmpeg` (BtbN LGPL, static) | 116.0 MB | 114.4 MB | 33% |
| `bin/ffprobe` (BtbN LGPL, static) | 115.8 MB | 114.2 MB | 33% |
| **Total** | **352 MB** | **≈349 MB** | |
| Zip, as downloaded | | 130 MB | |

(Linux sizes from this machine's builds; Windows FFmpeg from the pinned
archive, whose SHA-256 matches its pin; the Windows zip from CI. The macOS
bundle is already lighter, 54 MB zipped, because macOS builds FFmpeg from
source.)

**Where the bytes go.**
- **FFmpeg is two thirds of every download.** BtbN's static builds link
  every library into both `ffmpeg` and `ffprobe`, so the same code ships
  twice.
- **Each native image carries a whole Clojure runtime.** A 159-line terminal
  client weighed 57 MB, nearly as much as the engine. That is why ADR 0010
  removes it rather than slimming it.
- **The engine itself is modest for what it holds:** the kernel, the TrueType
  rasterizer, the HTTP server, the web UI and its assets, and a font.
  - It is already stripped, direct-linked and built without metadata
    (`build.clj`).
  - The image heap (29 MB) outweighs the compiled code (13 MB).

### What a Flutter GUI adds (measured 2026-09-28)

These are Flutter's own engine artifacts on the stable channel (engine
`af7e796e`, built 2026-09-17), before any app code:

| Platform | Flutter engine | Plus |
|---|---|---|
| Windows | `flutter_windows.dll` 21.3 MB, `icudtl.dat` 0.9 MB | the app's AOT snapshot (`app.so`: the Flutter framework, the ClojureDart runtime and the app), MSVC runtime DLLs |
| Linux | `libflutter_linux_gtk.so` 17.2 MB | the same, plus GTK |
| macOS | `FlutterMacOS.framework`, 12.8 MB zipped | the same |

So a Flutter desktop GUI adds roughly **25–35 MB per platform**. Under the
sidecar design it doesn't replace anything: the engine and FFmpeg still
ship. It also brings a second UI to build and keep in step with the web UI,
in Dart widgets.

## The answer

**Is bloat a severe risk? Yes, but not mainly from the GUI.**
- **The bloat is already here.** It comes from FFmpeg shipped twice over
  and from a second native image. Both can be fixed without touching the UI
  (see the decision below).
- **Flutter would add less than FFmpeg does today** (about +10% on today's
  download). After the fixes below, though, it would be **+40–60% of a
  ≈70 MB download.** It is the largest remaining addition, and it buys a UI
  we already have.

**Is there a better alternative? Yes: no second UI stack at all.** The
engine opens a native window onto the Datastar UI it already serves. On
desktop that needs neither Flutter nor Tauri, nor a second process.

### Options compared

| Option | Adds | Language | Verdict |
|---|---|---|---|
| A. The default browser (today) | 0 | Clojure | Keep as the last fallback. It works everywhere, but it feels like a website: a tab and a token in the URL. |
| B. A browser's app mode (`msedge --app=…`, `chrome --app=…`) | 0 | Clojure | A chromeless window with no new code, since Edge ships with Windows 10 and 11. It is a fallback: on macOS and Linux it needs Chrome or Edge installed. |
| **C. The engine hosts a native webview window** (the [webview](https://github.com/webview/webview) library, MIT, through Java's FFM API) | **well under 1 MB** | **Clojure** (we write no C) | **Recommended.** One binary and one UI. The OS supplies the web engine (WebView2, WKWebView, WebKitGTK). |
| D. Tauri 2 around a ClojureScript front end | a few MB | **Rust** host crate, npm tooling | **Rejected** under Clojure first: a Tauri app requires a Rust crate (`src-tauri`), and its CLI and JavaScript API come through cargo or npm (decision 1 forbids npm). It would wrap the same web UI that C wraps without Rust. |
| E. ClojureDart and Flutter (the M3 plan) | 25–35 MB | ClojureDart | **Not for desktop.** It is the largest addition and a second UI. Keep it as the candidate for mobile, where the in-process kernel is what matters. |
| F. Electron | about 100 MB | JavaScript | Rejected. |
| G. A JVM UI toolkit (JavaFX WebView) | tens of MB of native WebKit | Clojure | Rejected on size and native-image support. |

### Why C fits this codebase

- **FFM already works in our native binary on all four platforms,
  downcalls and upcalls.** CI's native smoke test loads the C mock engine
  through FFM and renders with progress upcalls on Windows, macOS
  (arm64 and x64) and Linux, on every run. A webview window is the same
  kind of call: a handful of functions (`webview_create`, `navigate`,
  `run`, `destroy`) and one upcall for the close event.
- **The UI doesn't change.** The window shows the engine's own server-rendered
  Datastar UI on loopback. Security stays as it is: a per-launch token, set
  as an HttpOnly cookie on first load, plus the Host and Origin checks. No
  JavaScript of ours is added.
- **The web engines come with the OS.**
  - **Windows:** WebView2 is preinstalled on Windows 11, and Microsoft
    installed it on all eligible Windows 10 devices.
  - **macOS:** WKWebView is part of the system.
  - **Linux:** WebKitGTK comes from the distribution. The engine loads it
    only if it is present, and otherwise falls back to B, then A.
- **One process.** Closing the window stops the engine. A GUI shell that
  wants to run the engine as a sidecar still can
  (`serve --announce json --parent-pid`).

## Decision (proposed)

1. **The desktop app is the engine.**
   - `wmark` (and `wmark ui`) opens a native window onto its own UI (option
     C), falling back to a browser's app mode (B), then the default browser
     (A).
   - No Flutter, Tauri or Electron on desktop, and no second UI.
   - It starts with a spike, and the spike's exit is:
     - the window opens from the native image on Windows x64, macOS arm64
       and x64, and Linux x64, with the UI loaded and signed in;
     - closing the window stops the engine, and every fallback is exercised;
     - the bundle grows by under 1 MB;
     - the browser suite also runs in WebKit, the engine behind WKWebView and
       WebKitGTK.
2. **A size diet, in this order, each step measured before and after:**

   | Step | Saves (measured) | Status |
   |---|---|---|
   | Remove `wmark-tui` (ADR 0010) | 57 MB | **Done in this change** |
   | Build the engine with `-Os` ("optimize for size", GraalVM 25) | 9.6 MB of 63.0 (−15%); gzip 16.5 → 13.8 MB | Proposed. First check that host-side drawing isn't slower in a way users notice. |
   | Ship FFmpeg's shared-library build on Windows and Linux (`ffmpeg` and `ffprobe` share the DLLs) | Windows 229 → 134 MB, measured on BtbN's `lgpl-shared` build of the pinned commit | Proposed: a pin change (ADR 0001) |
   | Build a trimmed LGPL FFmpeg ourselves on every platform, from the signed source, with only the components the engine uses | 232 → **13 MB** for `ffmpeg`, `ffprobe` and all libraries (measured on Linux). Platform encoders (Media Foundation, VideoToolbox) are part of the OS and add almost nothing. | Proposed, after the shared build. The component list comes from the engine (its filters, the encoder trial's `lavfi` input, the still decoders) and is proven by the conformance and smoke tests. |
   | A size budget in CI | keeps the result | Proposed: the native job fails when the bundle outgrows the budget |

   **Expected result:** about 70 MB unpacked instead of 349 MB, and roughly
   25–30 MB zipped instead of 130 MB. That comes from the ≈53 MB engine plus
   ≈15–20 MB of FFmpeg with platform encoders and AV1 decoding.
3. **Clojure first is an invariant** (CLAUDE.md, decision 9).
   - The Rust/wgpu "effects core" option in ROADMAP Assessment 2 is
     withdrawn. If one of its triggers fires, the answer has to be found
     within the rule.
4. **Mobile is out of scope here.**
   - The kernel keeps its portability invariants: `.cljc` only, registries,
     golden vectors. So ClojureDart stays available for iPad and Android at
     no cost.
   - M3 (the kernel under ClojureDart) waits until mobile is scheduled.

## Consequences

- **One binary and one UI.** Every screen built for the productization
  phase (ADR 0011) ships in the browser, in the window and in the hosted
  dashboard alike.
- **Two web engines to test.** WebView2 is Chromium, WKWebView and
  WebKitGTK are WebKit. The browser suite adds WebKit (Playwright ships
  it). CSS stays standard and has no vendor hacks.
- **A native library to build and pin per platform:** the webview library,
  from a pinned source release with a SHA-256, built in CI like FFmpeg. It
  is C/C++ we don't write.
- **Linux windows need WebKitGTK.** Without it, users get the browser
  fallback, which is what they get today.
- **The in-process kernel for mobile keeps working without Flutter on
  desktop.** Nothing in `kernel/` changes.
- **Store-distributed builds** have their own packaging constraints: sandboxing,
  and the store's signing. They are decided with them. This record covers
  the engine and the desktop window every edition shares.

## Alternatives

See "Options compared". Also considered:
- **UPX-compressing the binaries.** Rejected: the download is already
  compressed, and packed executables trip antivirus heuristics. Unsigned
  binaries (decision 6) can't afford that.
- **Dropping `ffprobe`** by parsing `ffmpeg -i` output. Rejected: with
  shared libraries `ffprobe` costs 0.2 MB, and its JSON output is the
  stable contract `watermark.engine.ffmpeg.probe` relies on.

## Sources (checked 2026-09-28)

- Measurements on this machine: GraalVM CE 25.0.2 native images of
  `v0.1.0-rc.1` with and without `-Os`; BtbN
  `autobuild-2026-08-31-13-27` `win64-lgpl-9.0` and `win64-lgpl-shared-9.0`
  archives; FFmpeg 9.0.2 built from the signed release source, both with
  every built-in component and trimmed to wmark's components.
- Flutter engine artifacts for the stable channel's engine
  `af7e796e161ae0bb1ff0758c71a7105418bd9ded`:
  `storage.googleapis.com/flutter_infra_release/flutter/<engine>/{windows-x64-release,linux-x64-release,darwin-x64-release}`
- Flutter: building and distributing Windows apps:
  https://docs.flutter.dev/platform-integration/windows/building
- GraalVM: optimize a native executable for file size (`-Os`):
  https://www.graalvm.org/jdk25/reference-manual/native-image/guides/optimize-for-file-size/
- GraalVM: the FFM API in Native Image (downcalls and upcalls):
  https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/
- webview (MIT; WebView2, WKWebView, WebKitGTK): https://github.com/webview/webview
- WebView2 distribution (preinstalled on Windows 11; installed on eligible
  Windows 10 devices):
  https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/evergreen-vs-fixed-version
- Tauri 2 (a Rust core; system webviews):
  https://v2.tauri.app/reference/webview-versions/

## Owner actions

- Decide the desktop architecture: C, with B and A as fallbacks, or another
  option.
- Approve the size diet's pin changes to FFmpeg: the shared build first,
  then the trimmed build. Each changes ADR 0001's pins.
