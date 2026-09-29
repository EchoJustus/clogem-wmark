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
| `(decode-still e source)` | `{:width :height :px}`: straight RGBA8, row-major | The `StillDecoder` protocol, for engines that take render spec v2: the host draws the logo's bitmaps from these pixels. |
| `(sample-video e opts path)` | `path` | The optional `SampleSource` protocol: a neutral clip (`{:width :height :fps :seconds}`) that previews draw on before a video is chosen. Engines with it declare `:preview #{:sample}`. |
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
 :strip-metadata? true
 :metadata {:title "Reel" :author "Studio A" :copyright "© 2026 Studio A" :comment "..."}  ; optional
 :cover    {:path "/abs/out.part.mp4.cover.png"}}                                          ; optional
```

**Extras: tags and a cover picture.** `:metadata` is written into the copy's
container as tags, even when the original's own metadata is removed.
`:cover` is a still the host rendered first (a preview of this same render,
at the time the person chose), which the engine embeds as the file's cover
picture: the thumbnail Windows Explorer and macOS Finder show. Both are
extras an engine declares (`:extras`, below); a request for one it lacks is
`:unsupported`, never dropped. A cover is per run, never a setting: it
belongs to the video, so profiles and `latest` never hold one.

**Output names and publishing are not the engine's job.** The job pipeline asks
`watermark.media` for a temporary path, and commits it after `:done` or
discards it otherwise.

**A preview is the same request cut to one frame**
([ADR 0011](adr/0011-web-ui-product-overhaul.md), section 5):
`:output {:path "/abs/preview.png" :frame 125}` asks for frame 125 of that
render, and nothing else, as a PNG. Same spec, same plan up to the output;
`requirements` then asks for `[:preview :frame]` instead of a codec and a
container. The test that frame n of a preview equals frame n of the full
render (`test/watermark/engine/ffmpeg_preview_test.clj`, through the
harness's `preview!`) is what an engine's preview must pass.

## The render spec

Schema: `watermark.render.schema` (malli), exported as
`native/render-spec.schema.json` (version 1) and
`native/render-spec-v2.schema.json` (version 2, below); a test keeps each
file equal to the code. The v1 export was checked with an independent
validator (Python `jsonschema`) against kernel-produced specs of every
layer, timing and placement type.

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

In v1, text rasterization (font, hinting) is the one thing that
legitimately differs between engines. Placement is defined relative to the
rendered box, so positions still agree to within glyph bearings. In v2 the
host draws the text, so every engine shows the same pixels.

## Capabilities

```clojure
{:layers #{:image :text} :animations #{:flip-y}
 :timing #{:always :windows :periodic} :placement #{:fixed :burst-scatter :per-window}
 :codecs #{:h264 :hevc} :containers #{"mp4" "mov" "mkv"}
 :audio #{:copy :aac :none} :sources #{:file :url}
 :preview #{:frame :sample}           ; optional: stills, and a sample clip
 :extras #{:metadata :cover}}         ; optional: tags, and a cover picture
```

The pipeline calls `engine/check!` before any work. A gap becomes an
`:unsupported` error naming exactly what's missing, for example "The ffmpeg
engine can't render this: layers text." The FFmpeg engine derives its
capabilities from the binary it found:
- `drawtext` present → `:text`
- encoders present → codec families
- always → `:extras #{:metadata}`; with the `mjpeg` encoder → `:cover` too
  (covers are MP4-only in FFmpeg: docs/FFMPEG_STRATEGY.md)

A native library may declare `"extras"` in its info JSON as well; the C ABI's
request JSON then carries `"metadata"` and `"cover"` for it. The C mock
declares none, so a render asking for either is refused there.

**Escape hatch for minimal engines.** An engine may omit `:text`; the host can
then rasterize text layers to image layers before calling it. This is how a
first GPU core can start with images only.

## Render spec v2: the host renders, engines composite

Spec v2 takes all drawing out of the engines. The host renders the logo
warped for every frame of a flip, the static pose and every text layer, as
bitmaps. An engine then only has to draw bitmap B with its top-left at whole
pixel (x, y) on frame n. FFmpeg's `overlay` does that, so an LGPL build (no
`perspective`, no `drawtext` needed) is a complete engine. Design, decisions
and measurements: [ADR 0006](adr/0006-render-spec-v2-host-rendered-overlays.md).

```clojure
{:spec/version 2
 :canvas {...} :timebase {...}                       ; as in v1
 :bitmaps {"<sha256>" {:width 176 :height 76 :path "/scratch/<sha256>.rgba"}}  ; straight RGBA8
 :layers [{:id "logo" :kind :flipbook :timing {:type :always}
           :rest  {:bitmap "<sha256>" :x 40 :y 142}
           :cycle {:start 15 :period 30 :frames [{:bitmap "<sha256>" :x 38 :y 139} ...]}}
          {:id "text-0" :kind :bitmap :bitmap "<sha256>"
           :placement {...v1...} :timing {...v1...}}]}
```

- **Bitmaps** are raw files: width × height straight (not premultiplied)
  RGBA8 pixels, row-major, top row first, no header. A bitmap's id is the
  SHA-256 (hex) of `"<width>x<height>:"` followed by its pixels
  (`watermark.raster/bitmap-id`). Opacity is baked into alpha.
- **Reference semantics:** `(watermark.render.v2/draw-at spec layer n)`
  gives the bitmap and position at frame n, or nil.
  - A flipbook shows `cycle` frame `mod(n - start, period)` while that is
    below its frame count, and `rest` otherwise.
  - A bitmap layer sits where v1 would put a text box of the bitmap's size
    (`text-origin`), floored to whole pixels.
  - Layers composite in order, source over, straight alpha.
- **What the host draws** (`watermark.render.v2`, graphics-free):
  `raster-requests` lists the static pose, one card per flip frame (its
  quad from the reference `logo-corners`, in a whole-pixel box) and each
  text layer; `assemble` builds the spec from the drawn bitmaps.
- **How the host draws** (`watermark.raster.*`, portable `.cljc`): pure
  arithmetic, so every host produces the same pixels.
  - Images: premultiplied area scaling, then an exact projective warp with
    bilinear sampling.
  - Text: the kernel's TrueType reader and rasterizer (exact area coverage,
    unhinted, advance-width layout: no kerning or shaping), a soft border
    under the text, and the bundled Fira Sans Bold unless a layer names a
    font file.
  - The engine decodes the logo (`StillDecoder`), so image formats stay its
    business.
  - The local adapter (`watermark.raster.local`) writes the bitmaps to
    `<home>/work/v2-<uuid>/`, validates the spec against the schema, and
    deletes the folder after the render.
- **Negotiation:** engines list `:spec-versions` (absent means `#{1}`), and
  a v2 request requires 2. The job pipeline gives an engine v1 when it
  takes it, else v2; `--render-spec N` forces a version. The FFmpeg engine
  reports `#{1 2}`, or `#{2}` for a build without `perspective`; an ABI 2
  native library lists its own.
- **Pinned outputs:** `kernel/test/golden/render-v2.edn` pins the requests,
  every bitmap (by id), the assembled spec and `draw-at` samples.

## The C ABI (`native/include/wmark_engine.h`)

- **Control plane only; frames never cross it.** JSON in and out, with
  library-owned strings freed through `wmark_free`.
- **Events arrive through a callback,** possibly on an engine thread.
- **Versioning:** `wmark_abi_version()` is checked first. The header writes
  down the compatibility rule: a library implements one ABI version; a host
  accepts every version from 1 to its own (wmark: 1 and 2) and calls only
  that version's functions; changes within a version are additive.
- **ABI 2** adds render spec v2: `wmark_engine_decode_still` (the logo as
  raw RGBA8 in a scratch file) and the `spec-versions` capability. An ABI 1
  library takes spec 1 only.

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
shapes (ABI 2's new function shares `probe`'s) and one upcall
shape are registered in
`desktop/resources/META-INF/native-image/.../reachability-metadata.json`, and
the build passes `--enable-native-access=ALL-UNNAMED`.

**In Flutter:** `dart:ffi` against the same symbols.

The mock (`native/mock/mock_engine.c`) is compiled by the test suite, then
driven through the binding and through the unchanged job pipeline. That covers
the handshake and the compatibility rule (the mock built as ABI 1 and 3),
info, probe errors, a render with upcall events from its own pthread,
cancellation, still decoding and a capability refusal. It composites
render spec v2 for real (grey Y4M over a white canvas) and passes the
conformance harness with FFmpeg's tolerances.

## Adding an engine: checklist

1. **Implement `wmark_engine.h`**, ABI 2. Taking render spec v2 is the
   short road: composite the host's bitmaps, decode stills, and list
   `"spec-versions": [2]`.
   - Swift: `@_cdecl` exports around an AVFoundation composition.
   - Rust: `#[no_mangle] extern "C"` plus `cbindgen`.
   - Kotlin: a thin JNI/C shim over Media3.
2. **Report honest capabilities.** It is better to decline `:burst-scatter`
   than to render it approximately.
3. **Map encode intent:** `:codec`, and `:quality` from `:archival` to
   `:compact`. Ignore the `:ffmpeg` block.
4. **Keep frames exact.** Never duplicate or drop frames. VFR input is
   rendered on the spec's constant-rate timebase.
5. **Run the conformance harness** (`testkit/src/watermark/engine/conformance.clj`).
   Point it at your engine: it renders test clips through the protocol and
   compares measured frames with the reference semantics
   (`:spec-version 2` for v2).
6. **Reproduce the golden vectors** if your platform re-implements any kernel
   logic, such as the PRNG, seeds, or v2's drawing (`render-v2.edn`).
