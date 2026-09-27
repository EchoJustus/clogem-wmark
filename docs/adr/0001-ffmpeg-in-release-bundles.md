# 0001. LGPL FFmpeg builds in the release bundles

- **Status:** Accepted (owner, 2026-09-27): the downloads bundle **LGPL**
  builds of FFmpeg by default, to keep them free of GPL obligations. This
  replaces the interim decision to ship GPL builds, which render spec v2
  (ADR 0006) made unnecessary.
- **Date:** 2026-09-27

## Context

The `desktop-server` bundle ships `bin/ffmpeg` and `bin/ffprobe` next to
wmark, which runs them as separate programs (no linking). Phase 3 asked for
FFmpeg pinned per OS, never "latest", and for LGPL builds where the operating
system's encoders suffice.

- **Before render spec v2, LGPL builds couldn't draw the flip.** FFmpeg
  builds the `perspective` filter only with `--enable-gpl`, so the first
  bundles were planned with GPL builds.
- **Render spec v2 (ADR 0006) removed that need.** wmark now draws the flip
  and the text itself, and FFmpeg only composites with `overlay`. An LGPL
  build is a complete engine: the pinned LGPL build passes v2 conformance on
  real frames, and the job pipeline gives it v2 automatically.
- **Builds on offer (checked 2026-09-27):**
  - BtbN publishes LGPL builds for Linux and Windows of the same FFmpeg
    commit as its GPL builds.
  - No maintained macOS LGPL build exists. martin-riedl.de configures with
    `--enable-gpl`, x264 and x265 (its `versions.txt`); evermeet.cx is GPL
    too; BtbN builds no macOS.

## Decision

1. **Bundle LGPL builds (LGPL-3.0-or-later), pinned in `deps.edn`**
   (`:wmark/build-matrix` → `:ffmpeg`):

   | Platform | Build | How |
   |---|---|---|
   | Linux x64, Windows x64 | `n9.0.1-11-ge47273f4d9` (release/9.0), LGPL | BtbN/FFmpeg-Builds `autobuild-2026-08-31-13-27`, by URL and SHA-256 |
   | macOS arm64, macOS x64 | `9.0.2`, LGPL | Built from FFmpeg's release source by `clojure -T:build ffmpeg`: the tarball pinned by URL and SHA-256 (its signature checked against the FFmpeg release key `FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8`), and a configure recipe in `deps.edn` |

   - **The macOS recipe:** `--enable-version3 --disable-autodetect`, plus
     VideoToolbox, AudioToolbox, zlib and bzip2, all part of macOS. (No
     iconv: macOS keeps it in a separate libiconv that FFmpeg's configure
     doesn't link, and wmark needs no subtitle charset conversion.)
     Nothing outside FFmpeg and the OS is linked. H.264 and HEVC come from
     VideoToolbox, with Apple's software encoder allowed where there is no
     hardware one. It needs macOS 11 or later, and Intel Macs need `nasm`
     to build (a tool only; CI installs it).
   - **Checking each build:** after a fetch or build, `ffmpeg` checks every
     binary on its own platform against its pin:
     - it must state the pinned license (`ffmpeg -L`);
     - an LGPL pin must carry no `--enable-gpl` or `--enable-nonfree`;
     - on macOS, it must link only system libraries.
   - **The bundle's notices:** `bundle` puts the LGPLv3 and GPLv3 texts
     (LGPLv3 is a set of additional permissions on GPLv3) and a
     `SOURCE.txt` into `licenses/ffmpeg/`. `SOURCE.txt` names the exact
     archives, their checksums and the configure flags.
2. **The GPL builds stay pinned as a variant** (`bb ffmpeg :variant :gpl`).
   They add x264, x265 and `perspective`, for comparisons and render spec v1
   on a developer's machine. Releases don't ship them.
3. **FFmpeg stays a separate program.** wmark only executes it by absolute
   path. Nothing may link FFmpeg's libraries into wmark.
4. **Encoders a build lists but a machine can't run are found up front.**
   Examples are Media Foundation on Windows N and Server editions, vendor
   hardware that isn't there, and VideoToolbox's hardware encoder in a VM.
   The engine runs a trial encode down each codec's preference list and uses
   the first encoder that works. `doctor` names the ones that failed.

## Consequences

- **No GPL obligations in the downloads.** The remaining FFmpeg obligations
  are the LGPLv3's:
  - ship the license texts, which is done;
  - offer the Corresponding Source of the binaries.
  - For macOS the source is exactly the pinned tarball plus the recipe, so
    attaching that tarball to each release is simple. For the BtbN builds it
    is FFmpeg plus the statically linked libraries at the builder's versions,
    which `SOURCE.txt` points to upstream.
  - Whether pointing upstream suffices is still a question for counsel,
    smaller than under the GPL.
- **Encoding quality changes.** x264 is gone from the downloads, and H.264
  comes from:
  - Media Foundation, else OpenH264, on Windows;
  - VideoToolbox on macOS;
  - OpenH264 on Linux.

  These encoders are fine for delivery copies but weaker than x264 at equal
  bitrate. Their quality tiers map to bitrates (`compile/video-args`).
- **Patents:** H.264 and HEVC are patent-encumbered. The OS encoders are
  covered by the platform vendor; OpenH264 compiled from source is not
  (Cisco's patent license covers only Cisco's own binaries). This is a
  counsel question for commercial distribution, unchanged by this record.
- **Every render uses v2 on LGPL builds.** That costs about half a second
  of host drawing per input and 7–18% more encode time (ADR 0006), with
  results closer to the reference than v1.
- **macOS release jobs build FFmpeg**, a few minutes per run. The result is
  cached per recipe in `target/downloads`.
- **Upstream files can vanish.** If an upstream deletes a pinned file,
  releases stop until the pin moves.
  - Mirroring the pinned archives as assets of a release in this repository
    would remove that dependency, and LGPL binaries carry lighter obligations
    to mirror.
  - Whether to mirror is left to the owner.

## Alternatives

- **GPL builds (the interim decision):** complete, but GPL obligations
  for every download. Unnecessary since render spec v2.
- **A third-party macOS build:** none is LGPL (see Context).
- **Homebrew on macOS:** GPL (x264/x265), and dynamically linked against
  many dylibs, so not relocatable into a bundle.
- **Building every platform from source:** full control everywhere, but
  Windows cross-builds and Linux static builds are what BtbN already does
  well. Revisit if BtbN stops.

## Sources (checked 2026-09-27)

- BtbN/FFmpeg-Builds: variants (`lgpl` lacks GPL-only libraries) and the
  `autobuild-2026-08-31-13-27` release: https://github.com/BtbN/FFmpeg-Builds
- FFmpeg 9.0.2 source and signature: https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz
  (`.asc`), signing key https://ffmpeg.org/ffmpeg-devel.asc
- martin-riedl.de build configuration (`--enable-gpl`, x264, x265):
  https://ffmpeg.martin-riedl.de/download/macos/arm64/1789931890_9.0.2/versions.txt
- FFmpeg's `videotoolboxenc.c`: without `allow_sw` it requires the hardware
  encoder (9.0.2 source, lines 1694–1710).
- FFmpeg license and legal notes: https://ffmpeg.org/legal.html
- GNU LGPL v3: https://www.gnu.org/licenses/lgpl-3.0.html

## Owner actions

- Ask counsel: the source offer for the bundled LGPL binaries, and H.264/HEVC
  patent licensing for commercial distribution.
- Decide whether to mirror the pinned archives in a release of this repository.
