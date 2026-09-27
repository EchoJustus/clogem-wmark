# 0006. Render spec v2: the host renders, engines only composite

- **Status:** Accepted. The owner approved the scope on 2026-09-27 and
  decided the three open questions the prototype raised the same day: a
  portable font rasterizer of our own, stills decoded by the engine, and
  Intel Macs kept on GraalVM 25.0.1 (ADR 0007).
- **Date:** 2026-09-27

## Context

The logo flip needs FFmpeg's `perspective` filter, which is GPL-only, so
today's bundles need GPL FFmpeg builds (ADR 0001). An LGPL build was not
even a usable engine: `wmark doctor` reported it unavailable, because
`perspective` was a required filter. Native engines (AVFoundation, Media3)
would each have had to re-implement the projection and text rendering, and
match each other.

The owner's M2 scope:
- The host pre-renders the warped logo for every flip phase and every text
  layer as bitmaps.
- Every engine, FFmpeg included, only composites them.
- Prototype one overlay per flip phase and measure it with the conformance
  harness before optimising. M2's exit: a pinned LGPL FFmpeg passes v2
  conformance on real frames, and so does the C mock.

The prototype drew text with Java2D and decoded the logo with ImageIO.
Neither works in every native binary: GraalVM can't use `java.awt`,
`javax.imageio` or `Graphics2D` on macOS (oracle/graal#13272, open for
25.0.2), and AWT fonts have had an open Linux issue since 2020
(oracle/graal#2729).

## Decision

### The kernel computes the geometry and draws every pixel

- **What to draw** (`watermark.render.v2`, graphics-free):
  - `raster-requests`: the static pose, one warped card per frame of a
    flip, and each text layer;
  - a flip card is the reference quad from `render/logo-corners`, placed in
    a whole-pixel box (`quad-box`) and given in the bitmap's own pixel
    frame, so sub-pixel positions are baked into the pixels;
  - `assemble` builds the v2 spec from the drawn bitmaps; `draw-at` is v2's
    reference semantics.
- **Drawing** (`watermark.raster.*`, portable `.cljc`, no dependencies;
  owner decision 1): pure arithmetic on byte arrays, rounding through
  `watermark.util.num`, so every host (the JVM, native images, ClojureDart
  later) produces the same pixels.
  - Images: premultiplied area-averaged scaling, then an exact projective
    warp (Heckbert's square-to-quad homography, inverted; bilinear
    sampling; transparent outside the card). A flip's frames share one
    scaled card (`draw-all`).
  - Text: a TrueType reader (`glyf` outlines, simple and composite glyphs;
    `cmap` formats 12 and 4; `hmtx`), outlines flattened to lines, exact
    area coverage by signed-area accumulation (the font-rs technique),
    unhinted. The border is the coverage dilated by a soft disc, drawn under
    the text. Colours are `#RRGGBB` or the CSS/SVG keywords FFmpeg knew.
  - A bitmap's name is `bitmap-id`: SHA-256 of `"<w>x<h>:"` and its
    straight RGBA8 pixels, so equal bitmaps get equal names on every host.
- **I/O stays outside** (`watermark.raster.local`, the local adapter of the
  kernel's `Rasterizer` port): it reads font files, writes each bitmap once
  as `<id>.rgba` in a scratch folder per render (`<home>/work/v2-<uuid>/`),
  validates the assembled spec against the published schema, and deletes
  the folder when the render ends, or when planning fails.
- **The engine decodes stills** (owner decision 2): `watermark.engine/StillDecoder`
  turns the logo into straight RGBA8. FFmpeg does it with one
  `-f rawvideo -pix_fmt rgba` call; the C ABI gains a function for it.
  Image formats (palettes, 16-bit, interlacing, colour profiles) stay the
  engine's business, and the kernel has no image dependency.
- **The font** is bundled: Fira Sans Bold (SIL OFL 1.1), pinned by SHA-256
  from the google/fonts repository, with its license in `licenses/`. It is
  now the default for v1 as well (FFmpeg's `drawtext`), where there was
  none before and wmark fell back to a system font.

### The v2 spec

It keeps v1's canvas, timebase, timings and placements, and adds:
- `:bitmaps {sha256 {:width :height :path}}`: straight RGBA8, row-major;
- a `:flipbook` layer: a `:rest` pose, plus a `:cycle` whose frame `p`
  shows while `mod(n - start, period) = p`;
- a `:bitmap` layer, placed like v1 text with the bitmap as the text box,
  floored to whole pixels.

Opacity is baked into alpha. The spec stays periodic (one bitmap per frame
of one flip), never per video frame. Its schema is
`native/render-spec-v2.schema.json`, exported from `watermark.render.schema`
and kept equal to it by a test. `kernel/test/golden/render-v2.edn` pins the
requests, every bitmap (by id), the assembled spec and `draw-at` samples for
ports, using a synthetic logo defined by formula and the bundled font.

### Engines only composite

- **FFmpeg** (`compile-request-v2`): one `overlay` per draw (the rest pose,
  one per flip frame, each `enable`d on exactly its frames, one per text
  layer), each reading a still `rawvideo` RGBA bitmap, in `yuv444` because
  `yuv420` rounds overlay positions to even pixels (checked on the pinned
  build: a square at x = 13 lands at column 12 in `yuv420`, 13 in
  `yuv444`). `required-filters-v2` is `overlay`, `fps` and `null`, and a
  test checks it covers everything the compiler emits.
- **The C ABI is version 2**, under a written compatibility rule
  (`native/include/wmark_engine.h`): a library implements one version; a
  host accepts every version from 1 to its own and calls only that
  version's functions; changes within a version are additive. ABI 2 adds
  `wmark_engine_decode_still` and the `spec-versions` capability; an ABI 1
  library is treated as taking spec 1 only. The C mock composites v2 for
  real.
- **Negotiation.** Engines list `:spec-versions`. FFmpeg reports `#{1 2}`
  with `perspective` and `#{2}` without; a v2 request requires
  `[:spec-versions 2]`, so engines that don't take it refuse up front. The
  job pipeline gives an engine v1 when it takes it, else v2, and
  `--render-spec N` forces a version.
- **The LGPL pin.** `deps.edn` `:ffmpeg` pins BtbN's LGPL-3.0-or-later
  build of the same FFmpeg commit as the GPL pin (archive, SHA-256,
  licenses, source notes). It began as a Linux-only variant; since ADR 0001
  was accepted it is the default on every platform, fetched with
  `bb ffmpeg`. CI sets `WMARK_REQUIRE_LGPL=1`, so the exit test cannot skip
  there.

## Measurements (2026-09-27, Linux x64, 4 vCPU)

Conformance: the v1 harness and tolerances (3 px, 3 px, 2 px), measured
against v1's reference geometry on 640×360 at 30 fps with a flip and
scheduled text. Worst deviation over all frames:

| Engine | Logo width | Logo height | Axis | Text frames |
|---|---|---|---|---|
| v1, FFmpeg 6.1.1 (GPL, `perspective`) | 1.24 px | 1.35 px | 1.35 px | exact |
| v2, FFmpeg 6.1.1 | 0.76 px | 1.11 px | 0.45 px | exact |
| **v2, pinned LGPL FFmpeg 9.0.1** | **0.76 px** | **1.11 px** | **0.45 px** | **exact** |
| **v2, the C mock through the C ABI** | **0.76 px** | **1.11 px** | **0.45 px** | **exact** |

FFmpeg passes on both clips (video starting at 0 s and at 0.5 s). v2 is
more accurate than v1, because the host warps with the exact homography
rather than `perspective`'s interpolation. The mock matches FFmpeg to the
pixel in these measures, as it should: both composite the same host-drawn
bitmaps.

Cost (1080p30, 20 s, a 1 s flip every 4 s: 30 flip frames and 31 distinct
bitmaps (2 MB), plus one text layer, `-crf 14`):

| | v1 | v2 |
|---|---|---|
| FFmpeg 6.1.1, libx264 | 13.8–14.0 s | 16.3–16.7 s (+18%) |
| Pinned GPL FFmpeg 9.0.1, libx264 | 11.8–13.4 s | 13.4–14.4 s (+7 to +14%) |
| Pinned LGPL FFmpeg 9.0.1, libopenh264 | not possible | 5.8–6.6 s |

Host work (decoding the logo, drawing and hashing 31 bitmaps, writing them)
takes 0.46–0.48 s with the kernel rasterizer, the same as the Java2D
prototype; area scaling dominated until a flip's frames shared one scaled
card. It is per input, not per frame. The LGPL build is faster only because
OpenH264 is a much faster (and weaker) encoder than x264 at `-crf 14`.

## Consequences

- An LGPL FFmpeg is a complete engine: `wmark run` works with it (v2 is
  chosen automatically), which opens bundling LGPL builds (ADR 0001, to
  revisit).
- Text in v2 is laid out by advance widths: no kerning, no shaping, no
  right-to-left ordering, no hinting. Latin, Greek and Cyrillic render
  well; Arabic and Indic scripts don't render correctly in v2 yet, and CFF
  (`OTTO`) fonts are refused with a clear error. v1's `drawtext` keeps its
  own behaviour where v1 is used.
- Text pixels differ from v1's (another rasterizer, another default font),
  and the default v1 font changes from a system font to Fira Sans Bold.
- Every engine now shares the host's pixels, so engine parity reduces to
  compositing: the trigger in decision 2 for a Rust/wgpu core ("engine
  parity costs") moves further away.

## Alternatives

- **Java2D for text, ImageIO for stills** (the prototype): no native
  images on macOS, and no path to ClojureDart. Rejected.
- **FFmpeg's `drawtext` to rasterize each text layer once:** ties text to
  FFmpeg, which the platform engines won't have. Rejected.
- **A small PNG decoder of our own:** more code and edge cases than asking
  the engine, which already probes the logo. Rejected by the owner.
- **One overlay per layer, a sprite sheet cropped per frame:** fewer
  filters, but more complex, and the measurements don't call for it. Kept
  for later if they do.

## Remaining work

1. LGPL pins for the other platforms, and ADR 0001 revisited (bundling LGPL
   builds).
2. Kerning (`kern`/`GPOS` pair adjustment) if users ask for it; shaping only
   with a shaping engine, which is a dependency decision of its own.
3. Only if measurements demand it: a sprite sheet per flip instead of one
   overlay per frame, and deduplicated inputs.

## Sources (checked 2026-09-27)

- AWT support missing on macOS in native images:
  https://github.com/oracle/graal/issues/13272
- AWT fonts in native images: https://github.com/oracle/graal/issues/2729
- FFmpeg `overlay` (options `x`, `y`, `eval`, `format`, `eof_action`,
  timeline `enable`): https://ffmpeg.org/ffmpeg-filters.html#overlay-1
- Heckbert, *Fundamentals of Texture Mapping and Image Warping* (1989),
  §2.2.3: the square-to-quadrilateral projective mapping used by
  `watermark.raster.image/homography`.
- Raph Levien, "Inside the fastest font renderer in the world" (2016): the
  signed-area accumulation `watermark.raster.text` uses.
- The OpenType specification, tables `glyf`, `loca`, `cmap`, `hmtx`,
  `hhea`, `head`, `maxp`: https://learn.microsoft.com/typography/opentype/spec/
- Fira Sans, SIL OFL 1.1: https://github.com/google/fonts/tree/main/ofl/firasans
