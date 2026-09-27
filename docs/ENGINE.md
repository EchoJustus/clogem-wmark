# The engine contract

How rendering is decoupled from everything else, and what a new engine
(AVFoundation, Media3, a Rust GPU core) has to implement.

## Layers

```
watermark.core.jobs ── plan-input ─► watermark.render/build ─► render spec
        │                                                        │
        │  engine/probe · engine/prepare · engine/execute!       ▼
        └─────────────────────────────────────────────►  VideoEngine
                                                          ├─ FFmpegProcessor   (watermark.engine.ffmpeg)
                                                          ├─ NativeFFIProcessor(watermark.engine.native) ─ C ABI ─► Swift / Kotlin / Rust
                                                          └─ test doubles      (jobs_test, native mock)
```

## The protocol (`kernel/src/watermark/engine.cljc`)

| Call | Returns | Notes |
|---|---|---|
| `(info e)` | `{:engine/id :engine/version :available? :problems :warnings :capabilities :binaries}` | Never throws. Cheap after the first call. `wmark doctor` and `/api/v1/health` show it. |
| `(probe e source)` | media facts: `:kind :width :height :fps-num :fps-den :frames :duration-s :start-s :vfr? :has-audio? :rotation` | Must handle still images (the logo) as well as video. Width and height are *display* dimensions, with rotation applied. |
| `(prepare e request)` | an engine plan: plain, serializable data | Capability check first (`engine/check!`), then compile. Nothing is written. Dry runs print it. |
| `(execute! e plan listener)` | a `RenderHandle`, returned immediately | `listener` receives `{:event :progress :fraction :frame}` on any thread. |
| `(cancel! handle)` / `(outcome handle)` | outcome deferred: `{:status :done/:failed/:cancelled :error}` | On the JVM, deref blocks until the render ends. |

### The render request

```clojure
{:spec   <render spec>                  ; watermark.render/build
 :source "/abs/in.mov"                  ; local path or URL
 :media  <probe of :source>
 :output {:path "/abs/out.part.mp4" :container "mp4"}   ; the engine writes here; MediaIO publishes
 :encode {:codec :h264 :quality :high :audio :copy :ffmpeg {...}}
 :strip-metadata? true}
```

**Output names and publishing are not the engine's job.** The job pipeline asks
`watermark.media` for a temporary path, and commits it after `:done` or
discards it otherwise.

## The render spec

Schema: `watermark.render.schema` (malli), exported as
`native/render-spec.schema.json`. The exported schema was checked with an
independent validator (Python `jsonschema`) against kernel-produced specs of
every layer, timing and placement type.

```clojure
{:spec/version 1
 :canvas   {:width 1920 :height 1080}                        ; display size
 :timebase {:fps-num 30000 :fps-den 1001 :frames 14385       ; whole programme
            :first-frame 0}                                  ; global index of this render's frame 0
 :layers   [{:kind :image :box {...} :opacity 0.85 :timing {:type :always}
             :animation {:type :flip-y :start 1800 :period 1800 :duration 30
                         :distance 575.0 :min-cos 0.02 :easing :cosine}}
            {:kind :text :text "(c) Studio" :style {:font :size :color :opacity :border ...}
             :placement {...} :timing {...}}]}
```

- **Timings** are in frames, with inclusive windows:
  - `{:type :always}`
  - `{:type :windows :windows [{:start :end}]}`
  - `{:type :periodic :offset :period :length}`
- **Placements** give the text's top-left x as `fx(n) * (W - tw) + px`, and
  likewise y. `fx` is one of:
  - constant (`:fixed`),
  - keyed per burst (`:burst-scatter`),
  - per window (`:per-window`).
- **Scheduled times are converted to frames with half-open intervals.**
  `[at, at + duration)` covers exactly `duration × fps` frames: 2 s at 30 fps
  is frames 30–89. A tiny epsilon keeps exact boundaries exact, so at NTSC
  rates frame 30 starts at 1.001 s and is still frame 30.

## Reference semantics

These are the functions in `watermark.render`, and they are normative:

- `(active? timing n)`: is the layer visible at global frame n?
- `(logo-corners layer n)`: the flipping card's four corners. The card rotates
  about its vertical center line and is seen through a pinhole camera
  `distance` px away. A point X px right of the axis and Y px below the center
  lands at:
  - `x = cx + X·cosθ·w`
  - `y = cy + Y·w`
  - where `w = D/(D − X·sinθ)`.

  The easing is θ(p) = π(1 − cos(πp/duration)). |cos θ| is clamped to
  `min-cos` so an edge-on card never degenerates.
- `(text-origin layer n [W H] [tw th])`: the top-left pixel of the text box,
  given the rasterized text size.

Text rasterization (font, hinting) is the one thing that legitimately differs
between engines. Placement is defined relative to the rendered box, so
positions still agree to within glyph bearings.

## Capabilities

```clojure
{:layers #{:image :text} :animations #{:flip-y}
 :timing #{:always :windows :periodic} :placement #{:fixed :burst-scatter :per-window}
 :codecs #{:h264 :hevc} :containers #{"mp4" "mov" "mkv"}
 :audio #{:copy :aac :none} :sources #{:file :url}}
```

The pipeline calls `engine/check!` before any work. A gap becomes an
`:unsupported` error naming exactly what's missing, for example "The ffmpeg
engine can't render this: layers text." The FFmpeg engine derives its
capabilities from the binary it found:
- `drawtext` present → `:text`
- encoders present → codec families

**Escape hatch for minimal engines.** An engine may omit `:text`; the host can
then rasterize text layers to image layers before calling it. This is how a
first GPU core can start with images only.

## Planned: render spec v2 (thinner native engines)

The native-engine plan is to shrink what each engine has to compute, not to
change its language. Spec v2 adds two optional forms, both announced through
capabilities so v1 engines keep working:

- **A baked flip table.** For an animated image layer, the kernel emits the
  quad corners for each frame of one flip, derived from `logo-corners`. The
  engine looks up entry `p = mod(n - start, period)` while `p < duration`,
  instead of evaluating the projection itself.
- **Host-rasterized text.** The host renders text layers to bitmaps with their
  final size and opacity (Java2D on the JVM, Flutter's text painter in the
  apps). Engines then see only image layers with per-frame positions. That is
  the escape hatch described above, made standard.

A v2 engine therefore only has to draw bitmap B into quad Q at opacity a on
frame n, in its platform's native API. Golden vectors for the baked tables
will pin them for ports.

## The C ABI (`native/include/wmark_engine.h`)

- **Control plane only; frames never cross it.** JSON in and out, with
  library-owned strings freed through `wmark_free`.
- **Events arrive through a callback,** possibly on an engine thread.
- **Versioning:** `wmark_abi_version()` must be checked first. Changes are
  additive within a version.

**On the JVM:** `watermark.engine.native` binds it with the Foreign Function &
Memory API: downcalls for every function, and an upcall stub for the event
callback. The stub lives in an automatic arena that stays reachable until
`wmark_render_release` returns, after the final event; the GC frees it
later. It isn't a shared arena because Native Image 25 supports
`Arena.ofShared` only behind an expert option (`-H:+SharedArenaSupport`); the
first native build failed on exactly that. GraalVM supports FFM downcalls and
upcalls on Linux x64 and AArch64, Windows x64 and macOS AArch64
([GraalVM 25: FFM API](https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/)),
so an Intel Mac build of `wmark` can't load native engines. The six downcall
shapes and one upcall
shape are registered in
`desktop/resources/META-INF/native-image/.../reachability-metadata.json`, and
the build passes `--enable-native-access=ALL-UNNAMED`.

**In Flutter:** `dart:ffi` against the same symbols.

The mock (`native/mock/mock_engine.c`) is compiled by the test suite, then
driven through the binding and through the unchanged job pipeline. That covers
the handshake, info, probe errors, a render with upcall events from its own
pthread, cancellation, and a capability refusal.

## Adding an engine: checklist

1. **Implement `wmark_engine.h`.**
   - Swift: `@_cdecl` exports around an AVFoundation composition.
   - Rust: `#[no_mangle] extern "C"` plus `cbindgen`.
   - Kotlin: a thin JNI/C shim over Media3.
2. **Report honest capabilities.** It is better to decline `:burst-scatter`
   than to render it approximately.
3. **Map encode intent:** `:codec`, and `:quality` from `:archival` to
   `:compact`. Ignore the `:ffmpeg` block.
4. **Keep frames exact.** Never duplicate or drop frames. VFR input is
   rendered on the spec's constant-rate timebase.
5. **Run the conformance harness** (`test/watermark/engine/conformance.clj`).
   Point it at your engine: it renders test clips through the protocol and
   compares measured frames with the reference semantics.
6. **Reproduce the golden vectors** if your platform re-implements any kernel
   logic, such as the PRNG or seeds.
