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
- never market anything as "subliminal".

**This repository is the open core** (EPL-2.0): the logo, continuous and
scheduled text, the UIs, the CLI, profiles, the engines, the kernel and the C
ABI. The commercial editions (Pro modes, offline licenses, the hosted service,
the apps) live in a separate private repository that depends on this one at a
pinned commit. This repository never names, requires or contains their code.

## 2. Architecture: the Clash model

It is one headless engine with a stable API, and interchangeable clients.
Clients: the built-in web UI (Datastar), the CLI, `wmark-tui`, scripts, GUI
shells (sidecar mode) and hosted APIs built on the same core.

```
 clients        web UI (Datastar) · wmark-tui · CLI · GUI shells · hosted APIs
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
| `kernel/` | Portable domain kernel: settings schema and resolution, render spec and reference semantics, keyed seeds, SplitMix64 PRNG, text-mode registry, `VideoEngine` protocol, feature catalog | `.cljc` only: JVM now, ClojureDart later |
| `src/` | Host core: profile rules, store/media/queue ports and local adapters, job pipeline, Core API, FFmpeg and native engines, REST routes | JVM |
| `web/` | Built-in web UI: server-rendered HTML plus Datastar over SSE (vendored `datastar.js`, no npm) | JVM |
| `desktop/` | CLI, http-kit server, loopback security, sidecar mode, native-image metadata | JVM / GraalVM |
| `tui/` | Terminal client over REST | JVM / GraalVM |
| `native/` | C ABI `wmark_engine.h`, mock engine, exported JSON Schemas | C |
| `testkit/` | Harnesses for code that plugs in from elsewhere: conformance, store contract, golden vectors, architecture checks | JVM (tests) |
| `build/` | `wmark.build`, the interpreter of the build matrix | JVM (tool) |

`kernel/`, `web/`, `desktop/`, `tui/`, `testkit/` and `build/` each have a
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
   - JSON REST `/api/v1` stays the contract for the TUI, GUIs, scripts and
     external UIs (`--ui-dir`).
2. **No jank for now.**
   - Keep the native surface small instead: the kernel will pre-compute
     per-frame geometry (a baked flip table) and host-rasterized text
     (render spec v2).
   - Platform engines then only composite bitmaps into quads, in their native
     APIs (Swift/AVFoundation, Kotlin/Media3).
   - A Rust/wgpu effects core only if a trigger fires: pixel-identical output
     across platforms, marks warped onto tracked content, or engine-parity
     costs exceeding the core's cost.
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

**Rendering**
- **The render spec is engine-neutral.** Engines never re-read settings or
  re-derive schedules. What an engine draws at frame n is defined by
  `watermark.render/active?`, `logo-corners` and `text-origin`.
- Engines report honest capabilities (`engine/check!` runs before any work). A
  gap is a clear `:unsupported` error, never an approximation.
- Frame-exact output:
  - FFmpeg runs with `-fps_mode:v passthrough`;
  - the flip card is built from the video's own frames (no timestamp pairing);
  - `perspective` counts frames from 1, hence `(in-1)`;
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
    (tested).

**Orchestration and ports (tested)**
- `watermark.core.*` requires no engine, media or store implementation, no OS
  utilities, and no server code.
- `src/` requires nothing from desktop, web or the TUI.
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
- **Never add Datastar Pro, Node or npm,** or code that sends scripts to the
  browser.

## 6. Commands

```bash
bb test          # all tests (C compiler and FFmpeg groups skip themselves if absent)
bb lint          # build matrix and component deps.edn files vs repository
bb e2e           # Chromium smoke test of the web UI (ffmpeg + Python Playwright)
bb dev           # engine from source, opens the web UI     bb dev doctor | bb dev run ...
bb tui           # terminal client
bb native        # GraalVM binary for this OS (GRAALVM_HOME) bb bundle :bundle :desktop-server :ffmpeg-dir DIR
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
| Stages, decisions, assessments | `docs/ROADMAP.md` |
| Contributing, DCO, license headers | `CONTRIBUTING.md` |

## 8. Next work, in order

1. **CI/CD.** The first real native-image run on Windows, macOS and Linux;
   signing (Authenticode, Developer ID and notarization); the release bundle
   with FFmpeg and its license; `kernel-v*` and `abi-v*` tags with an engine
   SDK archive.
2. **Render spec v2.**
   - A baked per-frame flip table and host-rasterized text layers, as
     capabilities.
   - The FFmpeg engine unchanged or simplified; new golden vectors.
3. **ClojureDart readiness of the kernel:** the `:cljd` branches compile and
   the golden vectors pass under ClojureDart.
4. **Core ports for hosting:** object-storage `MediaIO` with presigned
   uploads, a durable `JobQueue`, an identity middleware seam.
