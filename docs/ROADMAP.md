# Roadmap architecture

This document maps the five-stage roadmap onto the codebase. It contains
the two assessments requested: the GUI technology (ClojureDart) and a custom
GPU video core. Facts about third-party projects were checked in September
2026; sources are at the end.

## The one decision everything hangs on

Planning a render and performing it are now separate, joined by a render
spec: an engine-neutral description of one watermarked video.

```
 settings ─┐
 media ────┼─► kernel (portable .cljc) ──► render spec ──► VideoEngine ──► frames
 secret ───┘   resolve, layout, keyed                       FFmpeg       (desktop, server)
               schedules, reference                         C ABI        (AVFoundation, Media3,
               semantics                                                  Rust GPU core)
```

- **The kernel runs on any host.** It lives in `kernel/`: `.cljc` files only,
  with its own `deps.edn`. It resolves every setting into pixels and frame
  indices, so engines have nothing left to interpret. The same code plans
  renders in the JVM engine today and in-process in a Flutter app later.
- **The reference semantics define correctness.** They are
  `watermark.render/active?`, `logo-corners` and `text-origin`: what every
  engine must draw at frame n.
  - The conformance harness renders real frames and measures them against
    these functions. The FFmpeg engine is within 1.35 px at every frame, with
    frame-exact text timing.
  - Golden vectors (`kernel/test/golden/`) pin the kernel's outputs. They are
    what a Dart, Swift or Rust port must reproduce.
- **Engines are plugins behind one protocol** (`watermark.engine`). They
  negotiate capabilities before rendering. For example, an FFmpeg build
  without `drawtext` renders logo-only specs and refuses text layers up front.
- **The job pipeline can't tell engines apart.** Its tests run it on a fake
  engine, and a C mock library runs it through Java's foreign-function API. A
  fitness test fails the build if `watermark.core.*` ever requires an engine
  implementation.

## Decisions after Phase 2 (26 September 2026)

1. **Web UI: Datastar, adopted.** It serves the local web UI now and the hosted
   dashboard later.
   - Server-rendered HTML and signals over SSE, with no Node.js, npm or
     JavaScript build.
   - It is implemented as the `web/` component and replaces the placeholder
     JavaScript UI (see [ARCHITECTURE.md](ARCHITECTURE.md#the-built-in-web-ui-web)).
   - The JSON REST API stays the contract for the TUI, the GUI and scripts.
   - The assessment's prototype is kept in `spikes/datastar-2026-09/`.
2. **jank: postponed.**
   - Its state in September 2026: alpha since January 2026; AOT builds
     produce executables and object files, but no shared or static libraries
     yet; binary packages exist only for macOS arm64 and Linux; there are no
     iOS or Android targets; it uses a Boehm GC with no API for attaching
     foreign threads.
   - Revisit when it ships library output with an embedding API, mobile
     targets, and a beta.
3. **Keep native engines thin: render spec v2 bakes what engines would
   compute.**
   - The kernel will add a per-frame flip table, and rasterized text layers
     produced by the host (JVM or Flutter).
   - A platform engine (AVFoundation, Media3) then only composites bitmaps
     into quads at given frames, in its platform's own language.
   - Capability negotiation already allows image-only engines, so v2 is
     additive: the FFmpeg engine keeps its current path.
   - Rust/wgpu stays a later option, tied to the triggers in Assessment 2.
4. **Repository layout: decided.** This repository (clogem-wmark, EPL-2.0)
   is the open core. The commercial editions live in a private repository
   that depends on this one at a pinned commit, never the reverse. The kernel
   stays here as an independent subproject, and the GUI is one Flutter
   codebase for every platform, kept with the commercial editions.

## Decisions after the M0 and M1 review (27 September 2026)

1. **Render spec v2: the host renders, engines only composite.** This
   refines decision 3 above.
   - The host pre-renders the logo warped for each flip phase and every text
     layer as bitmaps. Every engine, FFmpeg included, only composites them.
   - FFmpeg then needs `overlay` alone. `perspective` is GPL-only (ADR 0001),
     so today's flip ties the bundles to GPL builds. After v2 an LGPL build is
     fully capable.
   - M2 starts with one overlay per flip phase, measured with the
     conformance harness before anything cleverer. Its exit is a pinned LGPL
     FFmpeg passing v2 conformance.
2. **Code signing: deferred indefinitely.**
   - ADRs 0002 and 0003 are rejected for now. Windows and macOS bundles ship
     unsigned, and users open them with the OS override (RUNBOOK.md,
     "Unsigned downloads").
   - The signing jobs stay in `release.yml`, frozen and skipped.
   - Smart App Control on Windows blocks unsigned programs with no per-app
     exception, which is this decision's main cost.
3. **Lanterna: dropped.** The TUI never used it. `wmark-tui` stays a
   line-mode REST client with no terminal library, and its third-party
   notices now list no LGPL code.

## Stages → bundles → code

The same mapping lives as data in `deps.edn` (`:wmark/build-matrix`).
`clojure -T:build matrix` prints it, and `clojure -T:build lint` checks it
against the repository.

| Stage | Bundle | New code | Reused unchanged |
|---|---|---|---|
| 1 WebServer + TUI, Windows then macOS | `desktop-server`: `wmark`, `wmark-tui`, `bin/ffmpeg`, `bin/ffprobe` | none: builds natively (signing deferred) | everything |
| 2 + GUI for Windows | `windows-combo` | Flutter app as a sidecar client. It starts `wmark serve --announce json --parent-pid <pid>`, reads the endpoint line, and talks REST + SSE. | engine, API, TUI |
| 3 GUI for Android | `android-app` | Kernel compiled by ClojureDart, in-process; Android engine plugin; Play Billing entitlements | kernel, render spec, golden vectors |
| 4 + iPad/macOS | `apple-app` | AVFoundation/Metal engine (Swift, behind the C ABI); StoreKit entitlements | Stage 3 app, kernel |
| 5 Serverless | `serverless` | Object-storage `MediaIO`, durable `JobQueue`, identity provider, a dashboard tier (the Datastar UI per tenant, on long-running containers) | Core API, routes, jobs, FFmpeg engine, web UI, **Postgres store (done, tested)** |

macOS gets both versions you described:
- **Version A (server + TUI)** is the `desktop-server` bundle built on a Mac.
  It can also load the Apple engine through `NativeFFIProcessor` once that
  exists, so the server edition gets the battery-friendly pipeline too.
- **The App Store version** is `apple-app`.

## Assessment 1: ClojureDart + Flutter for the GUI

**Verdict: viable. Go, but gate the commitment on a 2–3 week spike with
explicit exit criteria.** Two changes made in this phase make the bet cheap to
unwind.

### Why it fits this roadmap

**One UI codebase.** Flutter covers all four GUI targets (Windows, macOS,
iPadOS, Android), and Flutter apps ship in the App Store and Google Play.

**The in-process kernel is the deciding factor.** The iPad can't run the JVM
engine or spawn child processes. Stages 3 and 4 therefore need the planning
logic in-process in the app's own language. ClojureDart compiles `.cljc`
to Dart, so the kernel you already have *is* that logic — nothing is
rewritten.

**The C ABI works from Dart.** `native/include/wmark_engine.h` is callable
through `dart:ffi` exactly as the JVM calls it through FFM. The Apple and
Android engines are therefore written once and shared by the JVM editions
and the GUI.

**The build stays in `deps.edn`.** ClojureDart is configured in `deps.edn`
(`:cljd/opts`), so the GUI project consumes the kernel from git with
`:deps/root "kernel"` (or `:local/root` against a checkout). One honest caveat: Flutter's platform folders
(`pubspec.yaml`, Xcode and Gradle projects) still exist. `clj -M:cljd init`
generates them, and code signing lives there.

### Evidence on maturity

- **Active releases:** release 0.9.20260917 on 17 September 2026, still pre-1.0.
- **In production:** an experience report (December 2024) calls it
  production-ready, with "a few dozen other apps" in production on iOS and
  Android.
- **Development loop:** a watcher recompiles and hot-reloads.
- **No REPL.** The report calls this a real cost, partly offset by hot reload.
- **No multimethods.** Both ClojureDart's differences doc and the experience
  report say so.
- **Flutter error messages** are described as "pages upon pages of arcane
  framework minutiae".
- **Small maintainer team** (Tensegritics): a bus-factor risk.

### What this phase changed to prepare for it

| Hazard | Change |
|---|---|
| Multimethods don't exist in ClojureDart | Text modes are a registry (`watermark.core.modes/register!`); Pro registers into it |
| malli under ClojureDart is unproven (no evidence found either way) | malli is confined to two schema namespaces; the render path doesn't touch it; `native/settings.schema.json` and `native/render-spec.schema.json` let hosts without malli validate |
| Host RNGs differ | `watermark.util.prng`: SplitMix64 exactly as `java.util.SplittableRandom`. Verified draw-for-draw over 104,312 draws; the existing Pro schedules are unchanged across 3,000 comparisons |
| Rounding differs (Java rounds −2.5 to −2, Dart to −3) | `watermark.util.num` defines it once; golden vectors pin the results |
| HMAC is a host API | `watermark.core.seeds` has a JVM branch; the Dart branch (package:crypto) is specified and pinned by `golden/seeds.edn` |
| Kernel leaks into host code over time | `architecture_test`: the kernel is `.cljc` only and requires only the kernel (a mutation test confirmed the rule fires) |

### Architecture per stage

- **Stage 2 (Windows): the GUI is a sidecar client, like Clash Verge over the
  Clash core.** No kernel port is needed. The engine prints one JSON line with
  its URL and token, and exits when the GUI's process dies (`--parent-pid`).
  Both are implemented.
- **Stages 3–4: the kernel runs in-process, and the engine is a platform
  plugin.** Settings resolution, render specs and keyed schedules run in Dart.
  Rendering goes through the C ABI (`dart:ffi`) or a platform channel.
  Profiles need a local store in the app sandbox. The store contract
  (`test/watermark/store_contract.clj`) is the behavior to port.

### Spike exit criteria

Each criterion is one week at most.

1. **The kernel compiles under ClojureDart and passes the golden vectors.**
   This includes implementing the `:cljd` branches in `util/num` and `seeds`.
2. **A Windows Flutter build drives the engine sidecar.** It must cover
   profile CRUD, a job with SSE progress, and cancel.
3. **An iPad build calls a Swift stub through `dart:ffi` and uploads to
   TestFlight.**
4. **The team finds the dev loop acceptable.** Consider hot reload, error
   reading and debugging.

**If criterion 1 fails on library compatibility:** keep ClojureDart for the UI
and adapt the schema layer.

**If the toolchain itself is the problem:** write the UI in plain Dart/Flutter
and port the kernel to Dart (about 1,000 lines of pure logic). Verify the port
with the same golden vectors, which is why they exist.

### Alternatives considered

| Option | For | Against |
|---|---|---|
| **ClojureDart + Flutter** (recommended) | One language end to end; native-feel UI on 4 platforms; in-process kernel | Young toolchain, small team, no REPL |
| ClojureScript + Tauri 2 | Reuses the web UI and CLJS skills; Tauri 2 (stable since October 2024) targets iOS and Android | Webview UI; the mobile side is newer; Rust glue; the engine is still a native plugin |
| Flutter + plain Dart | Mature, hireable | Kernel port to Dart (mitigated by the golden vectors) |
| Native per platform (SwiftUI, Compose, WinUI) | Best platform fit | Three UIs to build and maintain |

## Platform facts the roadmap must absorb

### Android: I'd challenge "defaults to FFmpeg"

- **The turnkey FFmpeg library is gone.** FFmpegKit was retired on 6 January
  2025 and its binaries were removed. Its author's July 2026 successor,
  FFmpegKitNext, is source-only, so you would build FFmpeg for Android
  yourself.
- **Google Play constrains native libraries.** Since 1 November 2025, apps
  targeting Android 15+ must support 16 KB page sizes, and every native
  library must be built for it.
- **FFmpeg's filters run on the CPU.** `perspective`, `drawtext` and `overlay`
  have no GPU path, which on a phone means battery drain and thermal
  throttling: the same reason you chose AVFoundation on Apple.
- **Media3 Transformer is the platform-native equivalent.** It uses GPU
  effects and hardware encoders. Its overlays can change per frame
  (`BitmapOverlay.getBitmap(presentationTimeUs)`,
  `getOverlaySettings(presentationTimeUs)`), which covers the flip (a
  per-frame bitmap or a custom `GlEffect`) and the timed text layers.
- **Recommendation:** make the Android engine plugin Media3-first, with
  self-built FFmpeg as a fallback. The protocol supports either; this is a
  plugin choice, not an architecture change.

### Apple

- **Engine:** the AVFoundation composition plus Core Image/Metal, exported
  from Swift with `@_cdecl` behind `wmark_engine.h`.
- **FFmpeg licensing:** GPL is incompatible with App Store terms, and static
  LGPL linking on iOS is awkward. That is one more reason not to ship FFmpeg
  in the App Store build.
- **iPad exports that continue in the background** need
  `BGContinuedProcessingTask` (iPadOS 26+). The task must be user-initiated
  and must report progress; background GPU work needs its own entitlement.
  The engine protocol's progress events and cancel map directly onto it.
- **Billing:** App Review Guideline 3.1.1 forbids unlocking features with
  license keys inside App Store apps; in-app purchase is required. The United
  States storefront allows external purchase links. So the Apple and Android
  bundles take entitlements from StoreKit and Play Billing. That means one
  more implementation of the existing `Entitlements` protocol, with the
  license-key path left out of store builds (the matrix records this as
  `:entitlements :storekit` and `:play-billing`).

### Serverless

- **The API and the render workers are separate units.** Function time limits
  (AWS Lambda: 15 minutes) are shorter than long encodes. Long masters
  render as parallel segments.
- **Segments keep evidence frame-exact.** The spec's `:first-frame` keeps
  keyed schedules global, so each segment's marks land where the whole-file
  render would put them (a compile test covers this).
- **Profiles live in PostgreSQL** with row-level security. `latest` is per
  user; named profiles are shared by the tenant. The tests cover this.
- **The dashboard needs a long-running tier.** Every open tab holds an SSE
  stream.
  - API Gateway's REST response streaming caps a response at 15 minutes and
    closes idle streams after 5 minutes (regional), and Lambda bills for the
    whole connection.
  - Cloud Run allows requests up to 60 minutes, with many streams per instance.
  - So the dashboard runs on containers, and render workers stay separate.
- **Worker progress must reach the right dashboard instance.** It fans out
  through PostgreSQL LISTEN/NOTIFY, on a direct connection: transaction-pooled
  connections don't keep LISTEN. The jobs table stays the source of truth
  that reconnecting tabs re-read.
- **Master uploads go straight to object storage** through presigned URLs.
  A small hand-written script shows upload progress; still no build step.

## Assessment 2: a custom cross-platform GPU video core

**Verdict: a full video engine is not viable for a small team. A narrow
*effects* core is viable later, if one of the triggers below fires. The
abstraction is ready for either.**

### What "a video engine" really contains

| Layer | Difficulty | Ours to build? |
|---|---|---|
| Demux/mux: MP4/MOV edit lists, VFR, rotation metadata, audio passthrough | High, with a long tail of broken files | No |
| Hardware decode/encode: VideoToolbox, MediaCodec, Media Foundation / NVENC | High; per-device quirks | No |
| Color: YUV↔RGB matrices, ranges, BT.709/2020, HDR PQ/HLG tone mapping | High; subtle | No |
| **Compositing our layers: quad warp, text, alpha, frame-exact schedules** | **Moderate** | **Yes, the differentiator** |
| A/V sync, device test matrix | High, ongoing | No |

Only the compositing row is ours. FFmpeg, AVFoundation and Media3 each spent
years on the other rows.

**The hard 80% of a "GPU core" is the plumbing, not the shaders.** It is
zero-copy interop between each platform's decoder surfaces and your GPU API:
- IOSurface/CVPixelBuffer to Metal,
- AHardwareBuffer to Vulkan/GL,
- DXGI/D3D11 to D3D12/Vulkan.

wgpu does not decode or encode video. Importing native textures into it goes
through backend-specific, unsafe `wgpu-hal` paths.

### What would be viable

A Rust *effects core*: wgpu plus a text shaper/rasterizer
(`cosmic-text`/`swash`). It composites spec layers onto frames the platform
pipeline hands it:
- an `AVVideoCompositing` custom compositor on Apple,
- a Media3 `GlEffect` on Android,
- FFmpeg hardware frames or the CPU on desktop.

For two engineers with GPU experience, expect about 2–4 months for a solid v1.
Plan most of the risk around per-platform interop.

### Triggers worth building it for

- **Pixel-identical output across platforms.** Each OS rasterizes text
  differently, which matters if evidence must look identical everywhere.
- **Stronger anti-removal effects.** Examples: warping marks onto tracked,
  moving content. The Phase 1 analysis identified this as what actually
  defeats inpainting. FFmpeg's filtergraph handles it poorly.
- **Parity cost.** Three or more native engines with parity bugs costing more
  than the core would.

### How the abstraction accommodates it

- **As a full engine:** a Rust library implementing `wmark_engine.h` loads
  through `NativeFFIProcessor` today. The mock proves the path, including
  upcall events from a native thread and cancellation.
- **As an effects library inside platform engines:** those engines already
  consume the render spec. The reference semantics, golden vectors and the
  conformance harness define correct output for all of them.
- **Starting small:** capability negotiation lets a minimal core declare only
  `:layers #{:image}`. Hosts can then lower text layers to bitmaps before
  handing over the spec.

**Recommended order:**
1. FFmpeg on desktop and servers (now).
2. Platform-native engines (AVFoundation, Media3). These are mostly
   configuration of mature GPU pipelines.
3. Revisit the Rust effects core when a trigger fires.

## Risk register

| Risk | Likelihood | Impact | Mitigation in place |
|---|---|---|---|
| ClojureDart stalls or can't compile the kernel | Medium | High for Stages 3–4 | Spike exit criteria; golden vectors make a Dart port verifiable |
| Engine parity drifts (Apple vs FFmpeg) | High without tests | Evidence disputes | Reference semantics + conformance harness + golden vectors |
| FFmpeg builds vary (no drawtext, missing encoders) | High | Broken renders | Capability negotiation; `wmark doctor`; LGPL encoder fallbacks |
| Binary planting via working-folder lookup | Low | High | Source reported, warning when cwd ≠ install folder, hardened order flag, absolute paths only |
| Store billing rules change | Medium | Pricing/unlock flow | Entitlements protocol; store builds exclude license keys |
| GraalVM Native Image changes course | Low/medium | Distribution | Same uberjar runs on a jlink/jpackage runtime |

## Sources

- [ClojureDart release 0.9.20260917](https://github.com/Tensegritics/ClojureDart/releases/tag/0.9.20260917)
- [ClojureDart Flutter quick start (deps.edn, hot reload)](https://github.com/Tensegritics/ClojureDart/blob/main/doc/flutter-quick-start.md)
- [ClojureDart differences (protocols, no multimethods)](https://github.com/Tensegritics/ClojureDart/blob/main/doc/differences.md)
- [ClojureDart: an experience report (Dec 2024)](https://www.daveliepmann.com/articles/cljd-talk.html)
- [Saying goodbye to FFmpegKit (retirement, July 2026 update)](https://tanersener.medium.com/saying-goodbye-to-ffmpegkit-33ae939767e1)
- [Google Play 16 KB page size requirement](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)
- [Media3 BitmapOverlay (per-frame overlays)](https://androidx.de/androidx/media3/effect/BitmapOverlay.html)
- [GraalVM 25: FFM API in Native Image](https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/)
- [BGContinuedProcessingTask (iPadOS 26 background exports)](https://www.theswift.dev/posts/bgcontinuedprocessingtask-background-urlsession/)
- [App Review Guidelines 3.1.1](https://developer.apple.com/app-store/review/guidelines/)
- [Tauri 2.0 stable release](https://v2.tauri.app/blog/tauri-20/)
- [Go 1.19 release notes: no PATH lookups relative to the current directory](https://go.dev/doc/go1.19)
- [pgJDBC downloads (42.7.13)](https://jdbc.postgresql.org/download/)
- [Datastar releases](https://github.com/starfederation/datastar/releases) and [Datastar Pro license](https://data-star.dev/pro)
- [API Gateway response streaming limits](https://docs.aws.amazon.com/apigateway/latest/developerguide/response-transfer-mode.html)
- [Cloud Run request timeout](https://docs.cloud.google.com/run/docs/configuring/request-timeout)
- [The jank book (alpha status, AOT)](https://book.jank-lang.org/) and [jank repository](https://github.com/jank-lang/jank)
- [jank: Tracing rays (library output as future work)](https://jank-lang.org/blog/2026-06-01-optimization/)
