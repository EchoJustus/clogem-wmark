# clogem-wmark: master project prompt

This file is the standing context for every Claude session on this
repository. Claude Code loads it automatically. Read it fully before changing
anything, then read the doc for the area you are touching (map at the end).

## 1. What wmark is

wmark is a batch video watermarking tool built to make AI watermark removal
harder. It is part of an AI filmmaking studio's publishing pipeline.

Per video, it adds:
- **a logo that periodically flips in 3D** (a per-frame homography), so fixed-box
  inpainting can't simply erase it;
- **warning text**: continuous or scheduled here; the commercial editions add
  flash-frame canaries and randomised placement;
- **keyed schedules.** Timing and placement come from
  HMAC-SHA256(studio secret, video fingerprint), so they differ for every
  video and only the owner can predict or re-derive them. That makes the marks
  evidence, not only decoration.

Be honest about the threat model in code, docs and UI copy:
- visible marks raise the cost of removal; they don't make it impossible;
- canary frames prove ownership; they don't prevent copying;
- never market anything as "subliminal". The canary mode's wire id stays
  `subliminal`, because the keyed seed hashes it; everything users see or
  type says "canary" (ADR 0005, `features/mode-display-names`).

**This repository is the open core** (EPL-2.0): the logo, continuous and
scheduled text, the UIs, the CLI, profiles, the engines, the kernel and the C
ABI. The commercial editions (Pro modes, offline licenses, the hosted service,
the apps) live in a separate private repository that depends on this one at a
pinned commit. This repository never names, requires or contains their code.

## 2. Architecture: the Clash model

It is one headless engine with a stable API, and interchangeable clients.
Clients: the built-in web UI (Datastar), the CLI, scripts, GUI
shells (sidecar mode) and hosted APIs built on the same core.

```
 clients        web UI (Datastar) · CLI · GUI shells · hosted APIs
 transports     desktop: http-kit + token/Host/Origin guards   hosted: an identity middleware
 contract       watermark.server.routes (JSON REST /api/v1) + watermark.web.handler (/ and /ui/*)
                    └─► watermark.core.api   (every fn takes (sys ctx ...))
 orchestration  watermark.config (profile rules)      watermark.core.jobs (pipeline)
 kernel         resolve · render/build → RENDER SPEC · seeds · modes registry · features · engine protocol
 ports          ProfileStore   MediaIO   JobQueue   VideoEngine   Entitlements
 adapters       file/memory stores · local files · local executor · FFmpeg / native C ABI · community entitlements
```

| Directory | Role | Language / runs on |
|---|---|---|
| `kernel/` | The core library's start (decision 11): settings schema and resolution, render spec (v1, v2) and reference semantics, the v2 rasterizer (TrueType, text, warp), keyed seeds, SplitMix64 PRNG, text-mode registry, `VideoEngine` protocol, feature catalog | `.cljc` only: GraalVM/JVM now, the Dart VM through ClojureDart (Track B) |
| `src/` | Host core: profile rules, store/media/queue ports and local adapters, job pipeline, Core API, FFmpeg and native engines, REST routes | JVM |
| `web/` | Built-in web UI: server-rendered HTML plus Datastar over SSE (vendored `datastar.js`, no npm) | JVM |
| `desktop/` | CLI, http-kit server, loopback security, sidecar mode, native-image metadata | JVM / GraalVM |
| `native/` | C ABI `wmark_engine.h`, mock engine, exported JSON Schemas | C |
| `testkit/` | Harnesses for code that plugs in from elsewhere: conformance, store contract, golden vectors, architecture checks | JVM (tests) |
| `build/` | `wmark.build`, the interpreter of the build matrix | JVM (tool) |

`kernel/`, `web/`, `desktop/`, `testkit/` and `build/` each have a
`deps.edn` so other repositories can consume them from git with `:deps/root`.
`bb lint` fails if one drifts from its alias in the root `deps.edn`.

**Rendering:**
- The kernel resolves every setting into pixels and frame indices: the
  engine-neutral **render spec**.
- `watermark.render/active?`, `logo-corners` and `text-origin` are the
  **normative reference semantics**.
- **Engines** implement `watermark.engine/VideoEngine`:
  - `FFmpegProcessor`: the default for Windows, Linux and servers; compiles
    the spec into a filtergraph plus argv.
  - `NativeFFIProcessor`: loads any library implementing
    `native/include/wmark_engine.h` through Java's FFM API, or `dart:ffi` in
    apps. It is for AVFoundation/Metal on Apple and Media3 on Android.
- Engines negotiate capabilities before rendering. A conformance harness
  (`testkit/`) measures real frames against the reference semantics.

**Build matrix:** targets, editions, bundles and stages are data in
`deps.edn` under `:wmark/build-matrix`. `wmark.build` (`build/`) is a generic
interpreter of it; the commercial repository runs the same interpreter over
its own matrix.

## 3. Decisions in force (don't relitigate without new facts)

1. **Web UI = Datastar hypermedia.**
   - Server-rendered HTML fragments and signals over SSE; no Node.js, npm or
     JS build, ever.
   - `datastar.js` 1.0.4 is vendored and pinned by SHA-256; free MIT core
     only. Datastar Pro's license forbids use in open-source projects.
   - JSON REST `/api/v1` stays the contract for GUIs, scripts and
     external UIs (`--ui-dir`).
2. **No jank for now.**
   - Keep the native surface small instead, with render spec v2 (ADR 0006):
     the kernel computes the geometry and draws every pixel: the logo warped
     for each flip phase and every text layer, as bitmaps.
   - The kernel draws with portable arithmetic of its own (a TrueType
     reader and rasterizer, the warp), so every host, ClojureDart included,
     produces the same pixels. No AWT, Java2D or image library (owner,
     2026-09-27).
   - The engine decodes stills (the logo) for the host
     (`engine/StillDecoder`; `wmark_engine_decode_still` in ABI 2), so image
     formats stay the engine's business (owner, 2026-09-27).
   - Engines, FFmpeg included, then only composite bitmaps at given
     positions and frames, in their native APIs (Swift/AVFoundation,
     Kotlin/Media3; FFmpeg's `overlay`). No engine warps or typesets, so an
     LGPL FFmpeg is fully capable (owner, 2026-09-27).
   - No Rust/wgpu effects core: Clojure first (decision 9) withdrew it
     (owner, 2026-09-28). If one of its triggers fires (pixel-identical output
     across platforms, marks warped onto tracked content, or engine-parity
     costs exceeding a core's cost), the answer has to be found within
     decision 9.
3. **Engines per platform:** FFmpeg on Windows, Linux and servers;
   AVFoundation/Metal on Apple; Media3 first on Android. All behind the same
   protocol and C ABI.
4. **License: EPL-2.0**, file-level copyleft, no Secondary License. Every
   source file starts with
   `;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors` and
   `;; SPDX-License-Identifier: EPL-2.0` (or the same in the file's comment
   syntax). Contributions carry a DCO sign-off (`git commit -s`).
5. **Repositories:** this one is public and upstream. The commercial one
   depends on it through git (`:deps/root`), never the reverse. The kernel
   stays here as an independent subproject for now.
6. **Code signing is deferred** (owner, 2026-09-27; ADRs 0002 and 0003,
   rejected for now).
   - Windows and macOS bundles ship unsigned, and users open them with the
     OS override (RUNBOOK.md, "Unsigned downloads").
   - The signing jobs in `release.yml` stay frozen: skipped, neither enabled,
     removed nor extended without the owner.
   - Checksums, Sigstore and attestations (ADR 0004) continue.
7. **Intel Macs stay supported, on GraalVM 25.0.1** (owner, 2026-09-27; ADR
   0007). GraalVM 25.0.2 dropped macOS x64. `deps.edn` `:graalvm` pins it,
   `bb native` enforces it, and the workflows' macos-x64 entries name the
   same release (tested). The other platforms take the latest 25.x.
8. **The downloads bundle LGPL FFmpeg builds** (owner, 2026-09-27; ADR 0001).
   - BtbN's LGPL builds on Linux and Windows.
   - On macOS, FFmpeg's signed source release built by `bb ffmpeg` with
     the recipe in `deps.edn`; no maintained macOS LGPL build exists.
   - `bb ffmpeg` checks every binary against its pin (the license it
     states, no GPL or nonfree parts, no library outside macOS), and a test
     keeps every release platform on an LGPL pin.
   - GPL builds are only a development variant (`:variant :gpl`).
9. **Clojure first** (owner, 2026-09-28; ADR 0008). Our primary language stays
   Clojure, whatever hosts the app. This is an invariant for long-term
   maintainability.
   - Product code is written in Clojure dialects: Clojure on the JVM and in
     native images, and ClojureScript or ClojureDart where a host needs them.
   - We never write TypeScript, JavaScript or Rust.
   - Outside the rule:
     - third-party code we build but don't write (FFmpeg, a webview library);
     - the C ABI header and its test double;
     - platform engines behind the C ABI where an OS offers no other route
       (decision 3), kept to compositing (ADR 0006).
   - A desktop shell or GUI proposal that needs Rust or TypeScript is out,
     however small the glue (Tauri needs a Rust crate).
10. **`wmark-tui` is removed** (owner, 2026-09-28; ADR 0010). It was a
    57 MB native image for a line-mode REST client. `wmark` took over what
    it did well (`profiles effective`, display names in `profiles show`) and
    gained a progress bar for `run`. The code is at `v0.1.0-rc.1`.
11. **Hexagonal architecture around a dual-target core library** (owner,
    2026-09-28; ADR 0008, accepted).
    - **The core library** is `.cljc`, compiled for GraalVM (CLI, server, a
      JVM library) and for the Dart VM by ClojureDart (a Dart package and a
      Dart CLI), for us and for third parties. It starts as `kernel/`. The
      pure logic in `src/` (profile rules, planning, the FFmpeg plan
      compiler and parsers, the use cases) moves in behind ports, and new
      pure logic (the settings form model, the preview's planning) starts
      there.
    - **The open core's host** stays GraalVM: the CLI, the local server and
      the Datastar UI (modernized, ADR 0011), in a thin webview window
      through FFM, with browser fallbacks.
    - **The GUI apps** (stages 2–4) are ClojureDart and Flutter: first a
      sidecar client of the hidden engine over REST and SSE, then the core
      library in-process on the Dart VM with Dart adapters and no GraalVM.
      No single webview window for every edition: the owner rejected it.

## 4. Invariants (enforced by tests where marked; never weaken one to make a test pass)

**Code quality**
- Every namespace starts with `(set! *warn-on-reflection* true)`. Application
  code has **zero reflection warnings**; type-hint instead. Run the full suite
  and check that the output has no `Reflection warning`.
- **Native-image safe.** Clojure namespaces are initialised at image build
  time. No top-level def may read the environment, the clock, an RNG, or start
  a thread or executor. Build those in functions at run time (`defonce` is no
  exception). Use no `eval` and no runtime `require` of namespaces that weren't
  loaded at build time.
- **Pure over clever.** Add no dependency without a written reason. Prefer a
  small function we own (we write the Datastar SSE format ourselves, about 60
  lines).
- **Clojure first** (decision 9). Code we write is Clojure, ClojureScript or
  ClojureDart. We never write TypeScript, JavaScript or Rust.
- **Bytes count.** Every download carries what we add. A new native image,
  bundled library or asset needs its size measured and stated in the change
  (ADR 0008).

**Kernel (tested by `architecture_test`)**
- `.cljc` only. It requires nothing outside the kernel except
  `clojure.string`, plus malli in exactly `watermark.core.schema` and
  `watermark.render.schema`.
- No multimethods: ClojureDart has none. Extension points are registries
  (`watermark.core.modes/register!`).
- Deterministic numerics:
  - randomness only from `watermark.util.prng` (SplitMix64, identical to
    `java.util.SplittableRandom`);
  - rounding only through `watermark.util.num`.
- Golden vectors (`kernel/test/golden`) pin outputs for Dart, Swift and Rust
  ports. Regenerate them (`WMARK_UPDATE_GOLDEN=1`) only for an intended
  change, and review the diff.

**Core library portability** (decision 11; reviewed now, tested on the Dart
VM once M3a runs it in CI)
- Runtime-specific behaviour lives in small host primitives with one
  `#?(:clj … :cljd …)` branch per runtime: `watermark.util.num` (numbers and
  their formatting), `watermark.core.seeds` (HMAC), `watermark.raster`
  (SHA-256), `watermark.raster.text` (code points). Elsewhere, reader
  conditionals carry only type hints and the reflection flag.
- No `format`, `java.*` or ratios outside those primitives, and only regular
  expressions both runtimes read the same way.
- Logic moving into the library brings golden vectors with it (FFmpeg argv
  and filtergraphs, the form model), and both runtimes must match them.

**Rendering**
- **The render spec is engine-neutral.** Engines never re-read settings or
  re-derive schedules. What an engine draws at frame n is defined by
  `watermark.render/active?`, `logo-corners` and `text-origin`, and for v2
  by `watermark.render.v2/draw-at`.
- **v2 pixels are the kernel's.** `watermark.raster.*` rounds only through
  `watermark.util.num` and uses no platform graphics; `render-v2.edn` pins
  every bitmap by SHA-256. v1 stays until every engine migrates; the job
  pipeline gives an engine v1 where it can draw the whole spec, else v2.
- Engines report honest capabilities (`engine/check!` runs before any work). A
  gap is a clear `:unsupported` error, never an approximation.
- Frame-exact output:
  - FFmpeg runs with `-fps_mode:v passthrough`;
  - the flip card is built from the video's own frames (no timestamp pairing);
  - `perspective` counts frames from 1, hence `(in-1)`; so does `overlay`'s
    per-frame x and y (its `enable` counts from 0), hence `(n-1)` there;
  - scheduled times become half-open frame windows.

  Any rendering change needs the conformance harness
  (`testkit/src/watermark/engine/conformance.clj`).
- FFmpeg:
  - graphs are built from data (`engine/ffmpeg/graph.clj`), never by string
    splicing;
  - text reaches FFmpeg only through `textfile=` with `expansion=none`;
  - numbers are locale-independent;
  - executables are always invoked by absolute path;
  - `process/required-filters` must cover every filter the compiler emits
    (tested); a preview adds only `process/preview-filters`, which gate the
    `:preview` capability (tested).

**Orchestration and ports (tested)**
- `watermark.core.*` requires no engine, media or store implementation, no OS
  utilities, and no server code.
- `src/` requires nothing from desktop or web.
- `web/` talks to `watermark.core.api` only.
- Every Core API function takes `(sys ctx ...)`, and `ctx` carries the tenant
  and user. Jobs are tenant-scoped (list, cancel and subscribe filter on
  `(:tenant ctx)`). Profiles come from `((:profiles-for sys) ctx)`.

**Configuration: fallback and provenance** (`watermark.config`; the store contract in `testkit/`)
- **Precedence:** built-in defaults < base profile < explicit overrides. The
  base is the named profile, or `latest` when none is named, or nothing with
  `--clean`. `nil` falls through to the layer below. Maps merge deeply;
  vectors (text layers) replace wholesale.
- **Provenance.** Every effective field records the layer that won it. UIs
  show it ("from your last run", "set here", "built-in default").
- **Unknown names fail.** An unknown explicit profile is an error, never a
  silent fallback to `latest`.
- **`latest`** (`<home>/profiles/latest.edn`) is reserved.
  - It is auto-saved by every real run before encoding starts; dry runs never
    touch it.
  - Inputs and seeds are never persisted into it.
  - A damaged `latest` warns and is skipped.
- **Names and slugs.** Display names map to slugs ("16:9 Video Profile" becomes
  `16-9-video-profile`). Two names mapping to one slug are a conflict.
  Addressing a profile by slug never renames it.
- **Revisions.** Writes compare-and-set on `:profile/rev`: create requires
  absence, replace requires the revision that was read, `latest` is
  last-writer-wins. A stale write is a 409 with `reason: "stale"`.

**Open-core boundary (tested)**
- Nothing here is named `watermark.pro.*` or `watermark.saas.*`, and nothing
  requires such a namespace. Commercial code plugs in from its own repository
  through registries (`modes/register!`) and ports (`Entitlements`).
- Entitlements are checked in the core (`features/check!` at planning). Never
  check them only in a UI.
- Locked features stay visible, as upgrade prompts (`x-tier: pro` in the JSON
  Schema).
- Every source file carries `SPDX-License-Identifier: EPL-2.0`, and none may
  carry the commercial repository's license marker (the architecture test and
  CI's open-core guard check both). Never copy code in from the commercial
  repository; if something should become open, it is re-contributed here
  deliberately.

**Web UI (tested)**
- HTML only through `watermark.web.html`, which escapes by default. Never pass
  `raw` anything derived from a request or a store.
- **User text never goes inside a `data-*` expression.** Datastar evaluates
  those as JavaScript, and it runs `<script>` tags in patched fragments with the
  page nonce. User text reaches the page as escaped text or element values, and
  signals travel through `sse/patch-signals` (the browser reads them with
  `JSON.parse`). URLs inside expressions use `views/path-segment`.
- Every page gets a fresh CSP nonce (Datastar's CSP mode): no `unsafe-eval`,
  no `unsafe-inline`.
- `/` and `/ui/*` need the token. `/ui/*` also needs the `Datastar-Request`
  header.
- No JavaScript of our own. Streams send full state on every (re)connect,
  then coalesced deltas.

**Security**
- The local server:
  - accepts a per-launch 256-bit token (bearer, or an HttpOnly SameSite=Strict
    cookie);
  - checks the Host header against DNS rebinding;
  - checks Origin on writes;
  - sends no CORS headers;
  - sets `frame-ancestors 'none'`.
- Secrets never go into git (`.gitignore` blocks keys, `.env` files and
  `wmark-data/`). The studio secret lives in `<home>/secret.key`.
- FFmpeg lookup order: `--ffmpeg`, `./`, `./bin/`, the app folder, its `bin/`,
  then PATH. It reports where each binary came from, and warns when the binary
  came from the working folder. `--ffmpeg-search app,app-bin,path` is the
  hardened order.

## 5. Working agreements for Claude sessions

- **Before editing an area,** read its doc and the tests that pin it. Keep
  changes small, and add or adjust tests in the same change.
- **Done means:**
  - `bb test` green;
  - no reflection warnings;
  - `bb lint` green;
  - for UI changes, `bb e2e` green;
  - for rendering changes, the conformance tests green;
  - the relevant doc updated (ARCHITECTURE, ENGINE, FFMPEG_STRATEGY, RUNBOOK,
    ROADMAP).
- **A test that disagrees with an invariant is a finding.** Report it; don't
  loosen the invariant.
- **Platform facts change.** For FFmpeg or GraalVM behaviour, cloud limits and
  library versions, check current docs before relying on memory, and cite
  them in the doc you touch.
- **Commits:** imperative subject; the body says why; `git commit -s` (DCO).
  Branch for anything non-trivial. Don't force-push shared branches.
- **Decisions:** record each non-trivial one as a short ADR in
  `docs/adr/NNNN-title.md`. Escalate to the owner instead of deciding alone:
  purchases and credentials, anything that changes what is public or how it
  is licensed, relaxing an invariant, history-rewriting git operations, and
  security trade-offs.
- **Never add Datastar Pro, Node or npm,** or code that sends scripts to the
  browser.

## 6. Commands

```bash
bb test          # all tests (C compiler and FFmpeg groups skip themselves if absent)
bb lint          # build matrix and component deps.edn files vs repository
bb e2e           # Chromium smoke test of the web UI (ffmpeg + Python Playwright)
bb dev           # engine from source, opens the web UI     bb dev doctor | bb dev run ...
bb native        # GraalVM binary for this OS (GRAALVM_HOME)
bb ffmpeg        # the pinned FFmpeg for this OS -> target/ffmpeg/<platform> (SHA-256 verified)
bb bundle :bundle :desktop-server :ffmpeg-dir target/ffmpeg/<platform>
bb smoke --bin target/bin --ffmpeg target/ffmpeg/<platform>/bin [--mock LIB] [--bundled true]
bb sdk :name abi-v2   # engine SDK archive (header, mock, schemas, golden vectors)
clojure -M:dev:test -n watermark.web.sse-test               # one namespace
```

**In a Claude Code cloud session:**
- The environment should run `scripts/cloud-setup.sh` as its setup script: JDK
  25, FFmpeg, Babashka and the Clojure CLI.
- It needs **Custom** network access: the Trusted defaults plus `clojars.org`
  and `repo.clojars.org`.
- If `java -version` isn't 25, run the setup script by hand (as root).

## 7. Where things are documented

| Topic | Doc |
|---|---|
| Setup, commands, builds, troubleshooting | `docs/RUNBOOK.md` |
| Components, ports, dependency rules, security, tests | `docs/ARCHITECTURE.md` |
| Engine contract, render spec, C ABI, adding an engine | `docs/ENGINE.md`, `native/README.md` |
| FFmpeg lookup, filtergraph, flip, encoding, verification | `docs/FFMPEG_STRATEGY.md` |
| Stages, decisions, assessments | `docs/ROADMAP.md`, `docs/adr/` |
| Contributing, DCO, license headers | `CONTRIBUTING.md` |

## 8. Next work, in order

**Now: productization and UX** (owner, 2026-09-28; ROADMAP, "Decisions for
productization"). The MVP engine is validated by `v0.1.0-rc.1`.
- **P1 first:** the records and the cleanup, in one pull request for the
  owner's review. No UI code until the owner approves it.
- **Then two tracks in parallel:** Track A, the open core's product; Track
  B, M3 restarted as the core library on the Dart VM.
- Each milestone ends with its tests green, the docs updated and a short
  status report.

**P1 · Records and cleanup** (this phase's first pull request):
- ADRs 0008 (hexagonal architecture, accepted), 0009 (package managers),
  0010 (TUI removed) and 0011 (the web UI as a product);
- the TUI removed, and the CLI's `profiles effective` and progress bar.

**Track A: the open core's product** (the GraalVM host). P2–P4 have a
first cut (ADR 0011, "Implementation"); what remains of them is listed there.
1. **P2 · Design system and app shell** (ADR 0011, sections 1–2): CSS
   tokens, light and dark themes (both required, owner 2026-09-28), the
   rail, workbench and queue layout. Screenshots go with the pull request.
   *First cut built.*
2. **P3 · The settings form** (ADR 0011, section 3):
   - the catalog and the form model are pure `.cljc` in the core library
     (`watermark.core.form`), checked against the schema, and the API
     serves them as JSON too, for the GUI apps;
   - click to edit, enums as `<select>`, text layers as cards, provenance
     badges and reset;
   - saved one path at a time with `if-rev`.

   *First cut built;* still to do: saving on blur, a colour picker.
3. **P4 · Files and live preview** (ADR 0011, sections 4–5):
   - the logo picker and upload (content-addressed assets);
   - the server-side video picker;
   - `api/preview-frame` with a REST route and the engine's preview
     capability, with a conformance test that frame n of a preview equals
     frame n of a render. *Built and passing.*
4. **P5 · The desktop window** (ADR 0008, section 2): a spike on all four
   platforms, then the build, with the fallbacks (browser app mode, then
   the default browser). The browser suite also runs in WebKit. The window
   opens only on an interactive launch: `serve` stays headless, and a build
   can leave the window out (a GUI app's engine never opens it).
5. **P6 · The size diet** (ADR 0008, section 4), each step measured:
   - `-Os`, after a check that host-side drawing isn't slower in a way users
     notice;
   - shared-library FFmpeg, then a trimmed LGPL build from the signed
     source;
   - a size budget in CI.
6. **P7 · Package managers, Tier 1** (ADR 0009): Scoop, winget, a Homebrew
   tap, AppImage, .deb and .rpm, generated from `SHA256SUMS` by the release
   workflow. The owner provides the repositories and credentials.

**Track B: M3 restarted, the core library on the Dart VM** (ADR 0008)
1. **M3a · The kernel on the Dart VM:** ClojureDart and the Dart SDK pinned
   (by full commit SHA and version), the kernel compiled for the Dart VM, and
   `kernel/test/golden/*.edn` passing there in CI: the `util/num` `:cljd`
   branches, HMAC-SHA256 through `package:crypto`, and malli or a small
   validator of our own driven by the same schema data.
2. **M3b · The GUI apps' sidecar shell** (Phase 1), built with the commercial
   editions. The core's part: `serve --announce json --parent-pid` stays
   stable, and the form model and preview routes of P3–P4 serve it.
3. **M3c · Pure host logic into the library,** behind new ports (process
   runner, files, clock, HMAC): the profile rules, planning, the FFmpeg plan
   compiler and parsers, the use cases. Golden vectors grow to cover argv,
   filtergraphs and the form model, on both runtimes.
4. **M3d · The Dart adapters and the Dart CLI** (`dart:io` files,
   `Process.start` for FFmpeg), with a conformance run on real frames. GUI
   apps then embed the library in-process (Phase 2).

**Done before:**
- **M1 · CI/CD** (PR #1). Its exit was met by `v0.1.0-rc.1` (2026-09-27): a
  draft release with unsigned, checksummed, Sigstore-signed and attested
  bundles for every platform, each smoke-tested on a clean runner.
  - The owner starts a release with a pushed tag, or with **Run workflow**
    on `release` in the web UI (RUNBOOK, "Releases").
  - Code signing stays deferred (decision 6).
- **M2 · Render spec v2** (PR #5; ADR 0006; decision 2).
  - The kernel draws every bitmap, and engines only composite.
  - Exit met: the pinned LGPL FFmpeg and the C mock pass v2 conformance on
    real frames.
  - LGPL pins for every platform were added with the release (decision 8).

**Later:**
- **M4 · Port contracts for hosting** (`testkit/`, modelled on
  `store-contract`); the local adapters must pass them:
  - `JobQueue`: at-least-once delivery with leases, idempotent completion,
    cancel, per-job ordered progress, tenant isolation, a dead-letter path;
  - `MediaIO`: put, get, stat, delete, presigned URLs, size and type limits,
    tenant-scoped keys.

  Hosted adapters are built against these suites. FFmpeg never receives
  arbitrary URLs: media is downloaded to scratch first. M4 follows Track A.
