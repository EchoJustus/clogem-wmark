<!-- SPDX-FileCopyrightText: 2026 The clogem-wmark authors -->
<!-- SPDX-License-Identifier: EPL-2.0 -->
# 0014. The Dart host: adapters over `dart:io`, a Dart CLI, and the logic they share (M3d)

- **Status:** Accepted (2026-09-30), carried out in steps: sections 1 and 2
  on 2026-09-30. It records how M3d of
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

### 2. The Dart host: `dart/`, adapters over `dart:io`

- **Where:** `dart/`, a ClojureDart project beside `kernel/dart` (its own
  `deps.edn` and `pubspec.yaml`, ClojureDart and the Dart SDK pinned as
  there), namespaces `watermark.dartvm.*`. The architecture test holds it
  to the core library, its own namespaces and `clojure.string`, and keeps
  everything else from requiring it.
- **The adapters**, each the JVM's counterpart minus what moved in
  section 1:
  - `fs`: files through `dart:io`'s synchronous calls, and the files port.
    A write goes to a temp file in the same folder, flushed, then renamed
    over the target. Windows sharing violations (antivirus, sync clients)
    are retried, as on the JVM.
  - `store`: the profile store, one EDN file per profile. It reads and
    writes the JVM's files, byte for byte, and passes the store contract,
    which is now `.cljc` so any Dart store can run it.
  - `media`: MediaIO over local files. The fingerprint reads only the
    ranges `fingerprint-ranges` names; outputs are published from a
    `.part` file by rename.
  - `raster`: the Rasterizer, `watermark.raster/realize` plus files.
  - `ffmpeg`: the engine. It finds the binaries with the library's search
    over what `dart:io` reports (an execute bit on POSIX; the running
    executable's folder, or none under `dart run`) and runs them by
    absolute path, never through a shell.
    - Short commands (describe, probe, a still, the sample clip) run with
      `Process.runSync`. Output is decoded as UTF-8 that tolerates
      malformed bytes, since FFmpeg prints file names as the OS gives them.
    - Trial encodes run with `Process.start` and are killed after 30
      seconds. The Dart VM can only time a process out asynchronously, so
      discovery is a task: `ffmpeg-engine` returns a task of the engine,
      and the trials go through `watermark.ffmpeg.engine/trial-encoders`,
      which asks exactly what `discover` will (pinned in `adapters.edn`).
    - Renders run with `Process.start`: `-progress` lines on stdout feed
      the library's progress reader, and stderr streams into the render's
      log file. A cancel sends SIGTERM (TerminateProcess on Windows) and
      SIGKILL five seconds later.
  - `home`: the JVM's rules, so both hosts share a home: `--home`,
    `WMARK_HOME`, `./wmark-data`, then the platform's folder. The studio
    secret is the same file (base64 of 32 bytes from `Random.secure`),
    created exclusively. `dart:io` can't set permissions, so the host runs
    `/bin/chmod 600` by absolute path and removes the secret again if that
    fails. The font is `fonts/wmark.ttf` next to the program, else in a
    checkout's `resources/`, else a system font.
- **ClojureDart findings** (0.9.20260917, Dart 3.13.4):
  - named arguments are written `.name value`; `:name value` is
    deprecated;
  - `Utf8Codec`'s constructor argument `allowMalformed` can't be named:
    ClojureDart's analyzer sees the SDK's private field instead
    (`_allowMalformed`). Decoding with `utf8.decode(bytes, allowMalformed:
    true)`, a method argument, works;
  - a callback Dart types `void` (a `Timer`'s) must return nil;
  - a macro's body is compiled for Dart too, so it can't read a file on
    the JVM. The version is a compile-time constant instead
    (`String.fromEnvironment` with `^{:const :required}`).
- **A finding of the golden vectors:** the trials' order came from
  iterating a two-entry map literal, which the Dart VM iterates in another
  order than the JVM (HEVC first). The results were the same, but the order
  of the questions wasn't; codec families are now tried by name, on both.
- **Limits, stated:** `File.renameSync` replaces an existing file ("that
  entity is removed first", Dart's API). On POSIX that is `rename(2)`,
  which is atomic; Dart doesn't say whether it is atomic on Windows.
- **Tests** (`dart/test`, `bb dart`, CI's `dart` job with FFmpeg):
  - the store contract and profile files;
  - files, media publishing, conflicts and discards, and the secret;
  - fingerprints of files equal `adapters.edn`'s;
  - with the machine's FFmpeg: discovery, probing, a decoded still, the
    sample clip, a batch through the Core API with render spec 1 and with
    2 (`latest` recorded, no scratch left), and a cancelled render.

## Consequences

- The JVM's `wmark` runs the library's CLI; its tests (`app-test`, the
  progress tests, now in `kernel/test`) pass unchanged but for the printer
  taking a writer.
- A Dart program gets the same command line by supplying a host map.
- The core library now runs on the Dart VM on its own: plans, profiles,
  renders and previews, through adapters that pass the same contract and
  golden vectors as the JVM's.

## Sources (checked 2026-09-30)

- Dart `File.renameSync`: "If `newPath` identifies an existing file or
  link, that entity is removed first":
  <https://api.dart.dev/dart-io/File/renameSync.html>
- Dart `File` has no method that sets permissions:
  <https://api.dart.dev/dart-io/File-class.html>
- Dart `String.fromEnvironment` is only guaranteed as a `const`
  invocation: <https://api.dart.dev/dart-core/String/String.fromEnvironment.html>

- `clojure.tools.cli` 1.4.256, `parse-opts` and its summary layout:
  <https://github.com/clojure/tools.cli>
- ClojureDart 0.9.20260917: a `:dart` project's main namespace and
  `dart compile exe` (its `doc/quick-start.md`), static members and named
  arguments (`doc/differences.md`).
