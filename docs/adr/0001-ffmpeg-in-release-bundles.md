# 0001. Pinned GPL FFmpeg builds in the release bundles, for now

- **Status:** Proposed (licensing: the owner decides, with counsel)
- **Date:** 2026-09-27

## Context

The `desktop-server` bundle ships `bin/ffmpeg` and `bin/ffprobe` next to
wmark, which runs them as separate programs (no linking). Phase 3 asked for
FFmpeg pinned per OS, never "latest", and for LGPL builds where the operating
system's encoders suffice.

What we measured (BtbN's `n9.0.1-11` builds, 2026-08-31, and wmark's
`doctor` against them):

- **LGPL builds can't draw the flip.** FFmpeg builds the `perspective` filter
  only with `--enable-gpl`. The LGPL build has every other filter wmark needs,
  `drawtext` included, and an H.264 encoder (`libopenh264`), but `doctor`
  reports *NOT READY: This FFmpeg build lacks required filters: perspective*.
- **The GPL build has everything:** `perspective`, `drawtext`, `libx264`,
  `libx265`. It is GPL-3.0-or-later (`--enable-version3`). With it first on
  PATH, the conformance harness passes (logo geometry within tolerance,
  frame-exact text windows).
- **FFmpeg 9 changed `ffmpeg -filters`** (two flag columns instead of three);
  wmark read no filters at all from a 9.0 build until the parser was fixed.
- No reputable source publishes LGPL macOS builds; BtbN builds Windows and
  Linux only.

## Decision

1. **Bundle GPL builds of FFmpeg 9.0 for now**, pinned by URL and SHA-256 in
   `deps.edn` (`:wmark/build-matrix` → `:ffmpeg`):

   | Platform | Build | Source |
   |---|---|---|
   | Linux x64, Windows x64 | `n9.0.1-11-ge47273f4d9` (release/9.0), GPL | BtbN/FFmpeg-Builds `autobuild-2026-08-31-13-27` (month-end builds are kept long-term) |
   | macOS arm64, macOS x64 | `9.0.2`, GPL | ffmpeg.martin-riedl.de release builds (signed and notarized by their builder; we re-sign) |

   `clojure -T:build ffmpeg` downloads, verifies and unpacks the pin for the
   current platform; a changed or vanished file fails the build, it never falls
   back. `bundle` puts FFmpeg's GPL text (`COPYING.GPLv3` from FFmpeg's own
   repository, also pinned by SHA-256) and a `SOURCE.txt` naming the exact
   archives, their checksums and the corresponding source into
   `licenses/ffmpeg/`.
2. **FFmpeg stays a separate program.** wmark only executes it by absolute
   path, so wmark's EPL-2.0 code and FFmpeg's GPL code remain an aggregate.
   Nothing may link FFmpeg's libraries into wmark.
3. **Move to LGPL builds with render spec v2 (M2).** Once the host pre-renders
   the flip (baked quads, host-rasterized bitmaps), the FFmpeg engine only
   overlays bitmaps and no longer needs `perspective`. Then LGPL builds
   suffice: Media Foundation (`h264_mf`) on Windows, VideoToolbox on macOS,
   `libopenh264` on Linux. This record is superseded then.

## Consequences

- Every release carries GPL-3.0 obligations for the FFmpeg binaries. Under
  GPLv3 §6 we must give recipients the Corresponding Source of those
  binaries: either with the download, by a written offer (§6(b)), or from a
  server we keep available (§6(d)). `SOURCE.txt` currently **points
  upstream** (the FFmpeg commit and the builder's scripts and library
  versions). Whether that suffices, or whether each release must also attach
  the full source (FFmpeg plus every statically linked library at the versions
  the builder used), is a question for counsel **before the first public
  release**. Attaching the source archives to each GitHub release is the
  conservative answer and is easy to automate.
- H.264 and HEVC are patent-encumbered. Encoding with `libx264`/`libx265`
  (and `libopenh264` built from source) carries no patent license; the OS
  encoders (Media Foundation, VideoToolbox) are covered by the platform
  vendor. This matters for commercial distribution: a counsel question too.
- Two builders, two patch versions (9.0.1-11 and 9.0.2). Both pass the same
  smoke test; moving to one version is a pin change.
- If an upstream deletes a pinned file, releases stop until the pin moves.
  Mirroring the pinned archives as assets of a dedicated release in this
  repository would remove that dependency; it redistributes GPL binaries,
  with the same obligations as the bundles, so it is left to the owner.

## Alternatives

- **LGPL builds now:** can't render the flip, wmark's defining feature.
- **Build FFmpeg from source in CI:** full control of the configuration and the
  source offer; a macOS build with freetype and harfbuzz takes real effort
  and CI time. Reconsider with the LGPL move in M2, where a minimal
  configuration needs no external libraries.
- **Homebrew on macOS:** dynamically linked against many dylibs; not
  relocatable into a bundle.

## Sources (checked 2026-09-27)

- BtbN/FFmpeg-Builds: variants (`lgpl` lacks GPL-only libraries) and the
  `autobuild-2026-08-31-13-27` release with `checksums.sha256`:
  https://github.com/BtbN/FFmpeg-Builds
- martin-riedl.de FFmpeg builds (release 9.0.2, per-file `.sha256`,
  `versions.txt`, build script): https://ffmpeg.martin-riedl.de/
- FFmpeg license and legal notes: https://ffmpeg.org/legal.html
- GNU GPL v3, section 6: https://www.gnu.org/licenses/gpl-3.0.html#section6

## Owner actions

- Accept (or change) the GPL builds for the first releases.
- Ask counsel: the GPLv3 §6 source obligation for bundled binaries, and H.264/HEVC
  patent licensing for commercial distribution.
- Decide whether to mirror the pinned archives in a release of this repository.
