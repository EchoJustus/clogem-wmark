# 0006. Render spec v2: the host renders, engines only composite

- **Status:** Proposed. The owner approved the scope on 2026-09-27; this
  records the prototype and its measurements, and the decisions it still
  needs.
- **Date:** 2026-09-27

## Context

The logo flip needs FFmpeg's `perspective` filter, which is GPL-only, so
today's bundles need GPL FFmpeg builds (ADR 0001). An LGPL build is not even
a usable engine: `wmark doctor` reports it unavailable, because
`perspective` is a required filter. Native engines (AVFoundation, Media3)
would each have to re-implement the projection and text rendering.

The owner's corrected M2 scope has three parts:
- The host pre-renders the warped logo for every flip phase and every text
  layer as bitmaps.
- Every engine, FFmpeg included, only composites them.
- Prototype one overlay per flip phase and measure it with the conformance
  harness before optimising. M2's exit is a pinned LGPL FFmpeg passing v2
  conformance.

## Decision (prototype, on `claude/wmark-phase-3-kickoff-1zvpjz`)

- **Kernel (`watermark.render.v2`, `.cljc`, graphics-free).**
  - `raster-requests` lists what the host must draw: the static pose, one
    warped card per frame of a flip, and each text layer.
  - A flip card is the reference quad from `render/logo-corners`, placed in
    a whole-pixel box (`quad-box`) and given in that bitmap's own pixel
    frame, so the host bakes sub-pixel positions into the pixels.
  - `assemble` builds the v2 spec from the host's bitmaps.
  - `draw-at` is v2's reference semantics.
- **The v2 spec** keeps v1's canvas, timebase, timings and placements, and
  adds:
  - `:bitmaps {sha256 {:width :height :path}}`: straight-alpha RGBA8,
    content-addressed;
  - a `:flipbook` layer: a `:rest` pose, plus a `:cycle` whose frame `p`
    shows while `mod(n - start, period) = p`;
  - a `:bitmap` layer, placed like v1 text with the bitmap as the text box,
    floored to whole pixels.

  Opacity is baked into alpha.
- **Host (`watermark.raster`, JVM).**
  - The geometry is plain arithmetic on byte arrays: area-averaged scaling,
    then an exact projective warp (inverse homography, bilinear sampling,
    premultiplied alpha, transparent outside the card).
  - Decoding the logo (ImageIO) and typesetting text (Java2D) use
    `java.desktop`, which is marked PROTOTYPE; see "Open decisions".
- **FFmpeg (`compile-request-v2`).**
  - One `overlay` per draw: the rest pose, one per flip frame (each
    `enable`d on exactly its frames) and one per text layer. Each overlay
    reads a still `rawvideo` RGBA bitmap.
  - It uses `format=yuv444`, because `yuv420` rounds overlay positions to
    even pixels. Checked on the pinned build: a square at x = 13 lands at
    column 12 with `yuv420` and at 13 with `yuv444`.
  - `required-filters-v2` is `overlay`, `fps` and `null`, and a test checks
    it covers everything the compiler emits.
- **Negotiation.**
  - The FFmpeg engine reports `:spec-versions`: `#{1 2}` with
    `perspective`, `#{2}` without. Its other capabilities follow.
  - A v2 request requires `[:spec-versions 2]`, so v1 engines, the native
    C ABI engine included, refuse it up front.
  - v1 stays the default.
- **The LGPL pin.** `deps.edn` `:ffmpeg :variants :lgpl` pins BtbN's
  LGPL-3.0-or-later build of the same FFmpeg commit as the GPL pin, for
  Linux x64 only:
  - archive and SHA-256;
  - COPYING.LGPLv3 and COPYING.GPLv3;
  - source notes.

  It is fetched with `bb ffmpeg :variant :lgpl`, and lint checks it. CI
  fetches it and sets `WMARK_REQUIRE_LGPL=1`, so the exit test cannot skip
  there.

## Measurements (2026-09-27, Linux x64, 4 vCPU)

Conformance: the v1 harness and tolerances (3 px, 3 px, 2 px), measured
against v1's reference geometry, on 640×360 at 30 fps with a flip and
scheduled text. Worst deviation over all frames:

| Engine | Logo width | Logo height | Axis | Text frames |
|---|---|---|---|---|
| v1, FFmpeg 6.1.1 (GPL, `perspective`) | 1.24 px | 1.35 px | 1.35 px | exact (30/30) |
| v2, FFmpeg 6.1.1 | 0.76 px | 1.11 px | 0.45 px | exact |
| **v2, pinned LGPL FFmpeg 9.0.1** | **0.76 px** | **1.11 px** | **0.45 px** | **exact** |

v2 passes on both clips (video starting at 0 s and at 0.5 s), on both
builds. It is more accurate than v1, because the host warps with the exact
homography rather than `perspective`'s interpolation.

Cost (1080p30, 20 s, a 1 s flip every 4 s, so 30 flip frames and 31
distinct bitmaps (2 MB), plus one text layer, `-crf 14`):

| | v1 | v2 (32 overlays) |
|---|---|---|
| FFmpeg 6.1.1, libx264 | 13.8–14.0 s | 16.3–16.7 s (+18%) |
| Pinned GPL FFmpeg 9.0.1, libx264 | 11.8–13.4 s | 13.4–14.4 s (+7 to +14%) |
| Pinned LGPL FFmpeg 9.0.1, libopenh264 | not possible | 5.8–6.3 s |

Host rasterization takes 0.41–0.51 s of that. The LGPL build is faster
only because OpenH264 is a much faster (and weaker) encoder than x264 at
`-crf 14`.

## Findings on the way

- **Encoder choice on LGPL builds (fixed).** BtbN's LGPL build lists
  `h264_nvenc`, `h264_qsv` and `h264_amf`, which fail at run time without the
  hardware. They ranked above the software `libopenh264`, so every LGPL
  render failed. Software LGPL encoders (`libopenh264`, and `libkvazaar` for
  HEVC) now rank above vendor hardware. x264, x265 and the OS encoders keep
  first place.
- **AWT in native images.** GraalVM native images can't use `java.awt`,
  `javax.imageio` or `Graphics2D` on macOS (oracle/graal#13272, still open
  for GraalVM 25.0.2). AWT fonts have had an open Linux issue since 2020
  (oracle/graal#2729). The rasterizer therefore runs on the JVM only, and
  nothing reachable from the engine's main uses it yet: the native build and
  its 17-check smoke test are unchanged.
- **macOS x64.** GraalVM 25.0.2 removed macOS x64 support ("Version 25.0.1
  was the last release"). CI's Intel job silently resolved GraalVM CE
  25.0.1 (run 36324745992). Intel Mac binaries are frozen on a JDK that gets
  no more updates.

## Open decisions (owner)

1. **Text without AWT.** Pick one:
   - (a) a small pure-Java TrueType rasterizer (quadratic outlines,
     coverage anti-aliasing, no hinting). It is deterministic on every host
     and portable to the ClojureDart kernel later;
   - (b) Java2D on the JVM plus a native-image workaround per OS (none
     exists for macOS today);
   - (c) FFmpeg's own `drawtext` (LGPL; the pinned LGPL build has
     FreeType) to rasterize each text layer once into a bitmap.

   Recommendation: (a). It is the only option that works in every native
   binary and gives the same pixels everywhere. (c) is a stopgap that ties
   text to FFmpeg, which the native engines won't have.
2. **Logo decoding without AWT.** Either a small pure-Java PNG decoder, or
   letting the engine decode stills (FFmpeg and the platform engines can).
   Recommendation: the engine decodes, because the engine already probes the
   logo, and it keeps PNG edge cases (palettes, 16-bit, interlacing) out of
   our code.
3. **macOS x64.** Keep shipping Intel builds on GraalVM 25.0.1, or drop
   Intel Macs.

## Remaining M2 work, in order

1. The decisions above, then an AWT-free rasterizer.
2. The job pipeline produces v2 when the engine needs it: a host rasterizer
   port in `env`, scratch cleanup. `wmark run` then works with an LGPL
   FFmpeg. Until then, `doctor` says so.
3. `render.schema` gains the v2 spec. `render-spec.schema.json` is exported,
   and v2 golden vectors cover the requests, quads, boxes and `draw-at`.
4. The C ABI version is bumped under a written compatibility rule, and the
   C mock consumes v2. The native engine advertises `:spec-versions`.
5. LGPL pins for the other platforms, and ADR 0001 revisited (bundling LGPL
   builds).
6. Only if measurements demand it: one overlay per layer instead of per flip
   frame (a sprite sheet cropped per frame), and deduplicated inputs.

## Sources (checked 2026-09-27)

- AWT support missing on macOS in native images:
  https://github.com/oracle/graal/issues/13272
- AWT fonts in native images: https://github.com/oracle/graal/issues/2729
- GraalVM CE 25.0.2 release notes (macOS x64 removed):
  https://www.graalvm.org/release-notes/JDK_25/
- FFmpeg `overlay` (options `x`, `y`, `eval`, `format`, `eof_action`, timeline
  `enable`): https://ffmpeg.org/ffmpeg-filters.html#overlay-1
- Heckbert, *Fundamentals of Texture Mapping and Image Warping* (1989),
  §2.2.3: the square-to-quadrilateral projective mapping used by
  `watermark.raster/homography`.
