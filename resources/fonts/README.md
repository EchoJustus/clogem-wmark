`wmark.ttf` is **Fira Sans Bold** (version 4.3, Copyright 2012-2015 The Mozilla
Foundation and Telefonica S.A.), under the SIL Open Font License 1.1:
`licenses/FiraSans-OFL.txt` at the repository root, which every download ships.

It is the default font for text layers, for both render spec versions:
- render spec v1: FFmpeg's drawtext reads it from the cache folder, where it is
  extracted on first use, since FFmpeg can't read from inside our executable;
- render spec v2: wmark's own TrueType rasterizer (kernel `watermark.raster.*`)
  draws with it, so text is pixel-identical on every OS.

Source, pinned by SHA-256:
- https://raw.githubusercontent.com/google/fonts/23e54b51ddffbc7713c583748e3bd86f62b1fa4a/ofl/firasans/FiraSans-Bold.ttf
  sha256 a4d8e149ecdd4874a0726eb0af894488b3b31c423d6b0017c8f415ed1b795b45
- https://raw.githubusercontent.com/google/fonts/23e54b51ddffbc7713c583748e3bd86f62b1fa4a/ofl/firasans/OFL.txt
  sha256 8f24842e9174beda18a556c2ae7d54f5dc444340c19a3a9ef77e23bca366adbd

It covers Latin, Greek and Cyrillic. For other scripts, set a layer's
`font-path` to a TrueType-outline (`glyf`) font that covers them. The v2
rasterizer lays text out by advance widths, so scripts that need shaping
(Arabic, Indic) or right-to-left ordering aren't rendered correctly by v2 yet
(docs/adr/0006).
