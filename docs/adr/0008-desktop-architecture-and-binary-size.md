# 0008. Hexagonal architecture: a dual-target core library, and the hosts built on it

- **Status:** Accepted (owner, 2026-09-28).
  - The owner rejected the earlier proposal of this record, one webview
    window for every edition, for the GUI apps.
  - The owner decided the architecture below: a core library written once
    in `.cljc` and compiled for both GraalVM and the Dart VM, a GraalVM host
    for the open core, and ClojureDart for the GUI apps.
  - The Clojure-first principle (CLAUDE.md, decision 9) and the removal of
    `wmark-tui` (ADR 0010) stand.
- **Date:** 2026-09-28

## Context

**Two questions asked on 2026-09-28:**
- Does a GUI bloat the binaries once it joins GraalVM, Clojure and the video
  backend?
- Which architecture keeps wmark maintainable, in Clojure, across desktop,
  mobile and third-party use?

### What a download weighs today (measured 2026-09-28)

Uncompressed sizes of the `v0.1.0-rc.1` bundle, x86-64:

| Part | Linux | Windows | Share |
|---|---|---|---|
| `wmark` (engine, native image) | 63.0 MB | ≈63 MB | 18% |
| `wmark-tui` (native image), removed by ADR 0010 | 57.0 MB | ≈57 MB | 16% |
| `bin/ffmpeg` (BtbN LGPL, static) | 116.0 MB | 114.4 MB | 33% |
| `bin/ffprobe` (BtbN LGPL, static) | 115.8 MB | 114.2 MB | 33% |
| **Total** | **352 MB** | **≈349 MB** | |
| Zip, as downloaded | | 130 MB | |

- **FFmpeg is two thirds of the download.** BtbN's static builds link every
  library into both `ffmpeg` and `ffprobe`.
- **Every native image carries a whole Clojure runtime.** That is why a
  159-line terminal client weighed 57 MB.

### What a Flutter GUI weighs (measured 2026-09-28)

These are Flutter's engine artifacts on the stable channel (engine
`af7e796e`, built 2026-09-17), before any app code:

| Platform | Flutter engine | Plus |
|---|---|---|
| Windows | `flutter_windows.dll` 21.3 MB, `icudtl.dat` 0.9 MB | the app's AOT snapshot, MSVC runtime DLLs |
| Linux | `libflutter_linux_gtk.so` 17.2 MB | the same, plus GTK |
| macOS | `FlutterMacOS.framework`, 12.8 MB zipped | the same |

A Flutter app therefore weighs roughly **25–35 MB per desktop platform**
before it holds anything of ours.

## Decision

### 1. The core library: `.cljc`, compiled for GraalVM and for the Dart VM

**Everything that decides what a watermark is and where it goes lives in
one library.** It is written in portable `.cljc` and compiled for two
runtimes:

| Target | Compiled by | Delivered as |
|---|---|---|
| **GraalVM / JVM** | Clojure, then `native-image` | the open core's host (a CLI and a local server); a JVM library for other Clojure and Java programs (git deps, a jar) |
| **Dart VM** | ClojureDart | a Dart package for Flutter and Dart apps; a Dart command-line program (`dart compile exe`) |

The library is meant to be reused: third-party apps on either runtime can
embed wmark's planning without our hosts.

**What belongs in it:**
- **Today's kernel** (`kernel/`): settings schema and resolution, the render
  spec (v1 and v2) and its reference semantics, the rasterizer (TrueType,
  text, the warp), keyed seeds, the PRNG, the mode registry, the feature
  catalog and the engine protocol.
- **Pure logic now in the JVM host (`src/`), which moves in:**
  - **the profile rules** from `watermark.config`: precedence, provenance,
    slugs, the revision rules and `latest`. The file store stays a host
    adapter;
  - **planning:** settings and probed media in, the render spec and the
    layer orchestration out (the pure part of `watermark.core.jobs`);
  - **the FFmpeg plan compiler**, `engine/ffmpeg/graph` and `compile`: spec
    in, filtergraph and argv out. Their only Java is locale-independent
    number formatting, which moves to `watermark.util.num`, and paths;
  - **the FFmpeg output parsers:** `-progress` blocks, `ffprobe` JSON,
    `-filters` and `-encoders`, and the encoder preference;
  - **the use cases**, today `watermark.core.api`, written against ports
    only.
- **New pure logic, written there from the start:**
  - **the settings form model:** the schema, the effective settings and
    their provenance in, one row per setting out (path, title, control
    kind, enum options with display names, locks, source). Both the Datastar
    UI and a Flutter UI render this same model (ADR 0011);
  - **the preview's planning:** which frame, which layers and which seed
    (ADR 0011, section 5).
- **Ports: protocols the library defines and hosts implement.**
  - Existing: `ProfileStore`, `MediaIO`, `JobQueue`, `VideoEngine`,
    `Entitlements`, `Rasterizer`.
  - New, for what the library needs from a host: a process runner (spawn,
    read lines, exit code, kill), files (bytes, atomic replace, list), a
    clock, and HMAC-SHA256.

**What stays in the hosts (adapters):** I/O, processes, threads and
executors, the HTTP server and the Datastar views, native-image metadata,
FFM (native engines, the webview window), and each platform's file dialogs.

**Each target ships with its basic adapters,** so it works on its own:
- the JVM target has today's file store, local media, local executor and
  FFmpeg process runner;
- the Dart VM target gets the same set in ClojureDart (`dart:io` files and
  `Process.start` for FFmpeg), which is what makes its Dart CLI a real
  program and what GUI apps reuse in Phase 2 (section 3).

**Rules for the library** (CLAUDE.md, invariants):
- **Runtime-specific behaviour stays in small host primitives,** each with
  a `#?(:clj … :cljd …)` branch per runtime: numbers and number formatting
  (`watermark.util.num`), HMAC (`watermark.core.seeds`), SHA-256
  (`watermark.raster`), code points (`watermark.raster.text`), and the new
  primitives the moves need (bytes, strings). Elsewhere, reader conditionals
  carry only type hints and the reflection flag.
- **No JVM-only calls outside those primitives:** no `format`, no `java.*`,
  no ratios, and only regular expressions both runtimes read the same way.
- **The golden vectors are the contract on both runtimes.** They pin the
  kernel's outputs today. They will also pin the plan compiler's filtergraph
  and argv, and the form model, so the JVM and the Dart VM are checked to
  produce identical plans and identical FFmpeg command lines.
- **The multimethod-free, registry-based design stays** (ClojureDart has no
  multimethods).

### 2. The open core's host: GraalVM, a local server, the Datastar UI in a native window

- **The open-core app is the GraalVM target:** one native binary that is the
  CLI, the local server and the web UI. It consumes the library directly.
- **The UI stays server-rendered Datastar,** modernized as ADR 0011 lays out:
  a card-based design system, a settings form in the style of VS Code, flat
  settings, and live preview.
- **A thin native window wraps that UI:**
  - the [webview](https://github.com/webview/webview) library (MIT; WebView2
    on Windows, WKWebView on macOS, WebKitGTK on Linux), called through
    Java's FFM API;
  - FFM already works in our native binary on all four platforms,
    downcalls and upcalls (CI's native smoke test);
  - it adds well under 1 MB, and falls back to a browser's app mode, then
    the default browser;
  - we write no C.
- **This host is the one package managers distribute** (ADR 0009), so its
  size is budgeted (section 4).

### 3. The GUI apps: ClojureDart and Flutter, first as sidecar clients, then on the Dart VM

The GUI apps built on the core (ROADMAP stages 2–4) are written in
ClojureDart with Flutter, in two phases.

**Phase 1: a sidecar client.**
- The app starts the GraalVM engine as a hidden child process:
  `wmark serve --announce json --parent-pid <pid>`. Both halves exist and are
  tested today.
- It drives the engine over the REST API and server-sent events.
- Its UI shows the same form model and the same live preview as the
  Datastar UI. The preview is a Core API function with a REST route
  (ADR 0011, section 5), so both UIs get identical frames.
- **The cost, accepted:** the Flutter runtime plus the engine plus FFmpeg.
  After the size diet (section 4) that is about 100 MB (an estimate: ≈30 MB
  Flutter and app, ≈53 MB engine, ≈15–20 MB FFmpeg).

**Phase 2: in process on the Dart VM.**
- The app consumes the library's Dart VM target directly.
- The adapters are the Dart VM target's own (section 1): files through
  `dart:io`, and FFmpeg spawned with `Process.start` using the argv the
  shared compiler produces. Where a platform can't spawn processes (iPadOS),
  a platform engine behind the C ABI is used through `dart:ffi`
  (decision 3).
- The app then no longer needs the GraalVM engine at all. It becomes one
  ClojureDart program: about 30 MB of Flutter and app plus FFmpeg, and the
  same code base reaches mobile.

**What makes Phase 2 safe:** the golden vectors on both runtimes, and a
conformance run of the Dart adapters against real frames, the same harness
the JVM engines pass.

### 4. Binary size

For the GraalVM host, each step measured before and after:

| Step | Saves (measured) | Status |
|---|---|---|
| Remove `wmark-tui` (ADR 0010) | 57 MB | **Done** |
| Build the engine with `-Os` ("optimize for size", GraalVM 25) | 9.6 MB of 63.0 (−15%); gzip 16.5 → 13.8 MB | Planned, after a check that host-side drawing isn't slower in a way users notice |
| FFmpeg's shared-library build on Windows and Linux (`ffmpeg` and `ffprobe` share the DLLs) | Windows 229 → 134 MB, measured on BtbN's `lgpl-shared` build of the pinned commit | Planned: a pin change (ADR 0001) |
| A trimmed LGPL FFmpeg we build from the signed source, with only the components the engine uses | 232 → **13 MB** for `ffmpeg`, `ffprobe` and all libraries (measured on Linux) | Planned, after the shared build. The component list comes from the engine and is proven by the conformance and smoke tests. |
| A size budget in CI | keeps the result | Planned: the native job fails when a bundle outgrows it |

**Expected result for the open-core download:** about 70 MB unpacked
instead of 349 MB.

The GUI apps get a budget of their own, and Phase 2 is their main size
step: it drops the engine.

### 5. Clojure first, throughout

- **The library:** `.cljc` compiled by Clojure and by ClojureDart.
- **The open-core host:** Clojure.
- **The GUI apps:** ClojureDart.
- **Not ours:** Flutter, Dart packages, FFmpeg and the webview library are
  third-party code we build or depend on, but don't write.
- No TypeScript, JavaScript or Rust anywhere.

## Consequences

- **M3 restarts, now as the core library's Dart target** (ROADMAP,
  Track B), running beside the open core's UI work (Track A):
  1. **M3a:** pin ClojureDart and the Dart SDK, compile today's kernel for
     the Dart VM, and pass the golden vectors there, in CI;
  2. **M3b:** the GUI apps' sidecar shell (Phase 1), which needs nothing
     new from the core beyond the form model and preview routes of
     ADR 0011;
  3. **M3c:** move the pure host logic listed above into the library behind
     ports, each move covered by golden vectors on both runtimes;
  4. **M3d:** the Dart adapters and the Dart CLI, then the in-process GUI
     apps (Phase 2).
- **New pure logic is written in the library from now on.** The form model
  (ADR 0011, section 3) and the preview's planning (section 5) start there,
  so they never have to move.
- **Two runtimes in CI.** The Dart SDK and ClojureDart join the toolchain,
  pinned like GraalVM and FFmpeg.
- **Portable code costs discipline:**
  - no JVM conveniences in the library;
  - a malli fallback may be needed for the Dart VM, a small validator of
    our own driven by the same schema data, if malli doesn't compile under
    ClojureDart;
  - HMAC comes from `package:crypto` on the Dart side.
- **Two UIs share one model.** The Datastar UI and the Flutter UI differ in
  widgets only: the form model, the preview and every rule come from the
  library.
- **Phase 1 GUI apps are larger than the open-core download,** an accepted
  and temporary cost that ends with Phase 2.
- **The webview window is the open core's alone.** It still needs WebKit in
  the browser suite, because WKWebView and WebKitGTK are WebKit.

## Alternatives

- **One webview window for every edition** (this record's earlier proposal).
  Rejected by the owner for the GUI apps: they need a native UI toolkit, a
  path to mobile, and in time no JVM. The open core keeps the window.
- **Tauri 2 around a ClojureScript front end:** rejected by Clojure first,
  because Tauri needs a Rust crate and npm tooling.
- **Electron:** about 100 MB of runtime, and JavaScript. Rejected.
- **Flutter for the open core too:** rejected. The open core's host stays
  small for package managers, and its server-rendered UI also serves the
  hosted dashboard.
- **Porting the kernel to plain Dart** (Assessment 1's fallback): two
  codebases. Kept only as the fallback if ClojureDart fails its exit
  criteria.

## Sources (checked 2026-09-28)

- Measurements on this machine: GraalVM CE 25.0.2 native images of
  `v0.1.0-rc.1` with and without `-Os`; BtbN
  `autobuild-2026-08-31-13-27` `win64-lgpl-9.0` and `win64-lgpl-shared-9.0`
  archives; FFmpeg 9.0.2 built from the signed release source, both with
  every built-in component and trimmed to wmark's components.
- Flutter engine artifacts for the stable channel's engine
  `af7e796e161ae0bb1ff0758c71a7105418bd9ded`:
  `storage.googleapis.com/flutter_infra_release/flutter/<engine>/{windows-x64-release,linux-x64-release,darwin-x64-release}`
- GraalVM: optimize for file size (`-Os`):
  https://www.graalvm.org/jdk25/reference-manual/native-image/guides/optimize-for-file-size/
- GraalVM: the FFM API in Native Image:
  https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/
- webview (MIT; WebView2, WKWebView, WebKitGTK): https://github.com/webview/webview
- WebView2 distribution (preinstalled on Windows 11; installed on eligible
  Windows 10 devices):
  https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/evergreen-vs-fixed-version
- ClojureDart (Flutter and plain Dart, no multimethods):
  https://github.com/Tensegritics/ClojureDart and its `doc/differences.md`
- Tauri 2 (a Rust core): https://v2.tauri.app/reference/webview-versions/
