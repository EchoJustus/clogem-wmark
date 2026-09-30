<!-- SPDX-FileCopyrightText: 2026 The clogem-wmark authors -->
<!-- SPDX-License-Identifier: EPL-2.0 -->
# 0014. The Dart host: adapters over `dart:io`, a Dart CLI, and the logic they share (M3d)

- **Status:** Accepted (2026-09-30), carried out in steps: section 1 on
  2026-09-30. It records how M3d of
  [ADR 0008](0008-desktop-architecture-and-binary-size.md) (section 1,
  "Each target ships with its basic adapters"; section 3, Phase 2) is done.
- **Date:** 2026-09-30
- **Builds on:** [ADR 0013](0013-host-logic-into-the-core-library.md), which
  left a Dart program everything but its adapters.

## Context

After M3c the core library plans renders, manages profiles, runs the use
cases and makes every FFmpeg decision on both runtimes. A Dart program still
lacks the adapters that touch the machine: files, media, a profile store, a
rasterizer's I/O and an engine that runs FFmpeg. ADR 0008 asks for those
over `dart:io`, a Dart CLI that makes them a real program, and a conformance
run on real frames through them.

Writing a second set of adapters would copy what the JVM's hold besides
I/O: the command line, the media fingerprint that keys the Pro schedules,
output names, the executable search order that protects against binary
planting, and how v2 bitmaps become a spec. Copies drift, and for the
fingerprint and the search order a drift is a correctness or security
fault. So that logic moves into the library first, with golden vectors, and
each host's adapter keeps only its I/O.

## Decision

### 1. What the adapters share moves into the library

- **The command line** (`watermark.cli`, `watermark.cli.opts`,
  `watermark.cli.progress`): the options, what they mean as settings, the
  commands that run through the Core API (`run`, `profiles`, `doctor`,
  `version`), what they print, and the progress display.
  - A host supplies a map: its system (or a task of one), output, reading
    a text file, whether its output is a terminal, and commands of its own.
    The JVM adds `ui`, `serve` and `license`; `watermark.app/run-cli` keeps
    its signature, so editions built on it don't change.
  - `watermark.cli.opts` parses the part of `clojure.tools.cli`'s option
    specs the CLI uses (flags, values as `--x V`, `--x=V` or `-x V`,
    defaults, parse functions, validation, `--`, in-order parsing), with
    the same messages and help layout. `tools.cli` is no longer a
    dependency.
  - Changes a user can see: `profiles show` prints the portable EDN
    printer's layout (keys sorted) rather than `pprint`'s; numbers in
    `profiles effective` are plain decimals on both runtimes (`1`, not
    `1.0`); compound values there are the library's JSON (keys sorted,
    non-ASCII text as is); number options are read strictly
    (`watermark.util.num`: no `NaN`, hexadecimal or spaces).
- **The media fingerprint** (`watermark.media/fingerprint`,
  `fingerprint-ranges`): SHA-256 over the size, the first MiB and the last
  MiB, from bytes the host reads. SHA-256 is a host primitive of its own
  now (`watermark.util.digest`), which `watermark.raster/bitmap-id` uses
  too.
- **Output names** (`watermark.media/output-name`, `part-path`).
- **The executable search** (`watermark.util.locate`, now in the library):
  the order, the trail `doctor` prints, and the working-folder warning,
  over what the host reports (folders, and whether a path holds a usable
  file). The JVM's facts moved to `watermark.util.os`.
- **Realizing a v2 spec** (`watermark.raster/realize`): drawing, naming,
  assembling and validating; the host supplies decoding, a font's bytes and
  storing a bitmap.
- **Golden vectors:** `cli.edn` runs every command through `main` on the
  pipeline's fake ports and pins what each writes and its exit code (help,
  errors, profiles, dry runs, runs with progress, a locked feature), then
  the progress display on a fake clock, parsing, quoting and JSON.
  `adapters.edn` pins SHA-256, fingerprints of files up to 3 MB, output
  names, eleven searches on a fake machine (Windows paths included), the
  warnings and a realized spec. Both runtimes pass both.

## Consequences

- The JVM's `wmark` runs the library's CLI; its tests (`app-test`, the
  progress tests, now in `kernel/test`) pass unchanged but for the printer
  taking a writer.
- A Dart program gets the same command line by supplying a host map.

## Sources (checked 2026-09-30)

- `clojure.tools.cli` 1.4.256, `parse-opts` and its summary layout:
  <https://github.com/clojure/tools.cli>
- ClojureDart 0.9.20260917: a `:dart` project's main namespace and
  `dart compile exe` (its `doc/quick-start.md`), static members and named
  arguments (`doc/differences.md`).
