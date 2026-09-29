<!-- SPDX-FileCopyrightText: 2026 The clogem-wmark authors -->
<!-- SPDX-License-Identifier: EPL-2.0 -->
# 0013. The host's pure logic moves into the core library (M3c)

- **Status:** Accepted (2026-09-29), carried out in steps. It records how
  M3c of [ADR 0008](0008-desktop-architecture-and-binary-size.md)
  (section 1, "What belongs in it") is done; each step lands with golden
  vectors that both runtimes pass.
- **Date:** 2026-09-29
- **Builds on:** [ADR 0012](0012-the-kernel-on-the-dart-vm.md), which put the
  kernel and its golden vectors on the Dart VM.

## Context

ADR 0008 lists the pure logic still in the JVM host (`src/`) that the core
library should hold, so a Dart program can plan and run renders without the
JVM: the FFmpeg plan compiler and output parsers, the profile rules, the
planning part of the job pipeline, and the use cases, behind ports for what
only a host can do (processes, files, a clock). This record says where each
piece goes and what had to change to make it portable.

## Decision

### 1. FFmpeg: the plan compiler and the parsers

- **Where:** `watermark.ffmpeg.*`, a family of its own in the library:
  - `watermark.ffmpeg.graph`: filtergraphs from data (was
    `watermark.engine.ffmpeg.graph`);
  - `watermark.ffmpeg.plan`: a render request to argv, filtergraph and
    scratch files, the encoder choice and the filter sets the capability
    check uses (was `watermark.engine.ffmpeg.compile`, plus parts of
    `.process`);
  - `watermark.ffmpeg.parse`: the version line, `-filters`, `-encoders`,
    `-progress` blocks and ffprobe's facts (was in `.process` and `.probe`).

  `watermark.engine.ffmpeg.*` keeps the JVM's side only: finding the
  binaries, running them, writing the files, decoding ffprobe's JSON. The
  names say which side a namespace is on, and the architecture test keeps
  orchestration and the web UI away from both.
- **What the move replaced:**
  - `String/format` with `Locale/ROOT`: plain string building (every
    `%d` was an integer from a validated spec);
  - `BigDecimal` for numbers in graphs: `watermark.util.num/decimal-str`, a
    host primitive over each runtime's shortest round-trip text of the
    double, written out without an exponent. On 400,000 doubles it equals
    the old output. One limit, documented: a subnormal double that one digit
    identifies, where Java picks the closest decimal of one *or two* digits
    (`4.9E-324` against Dart's `5e-324`); no plan contains one;
  - `java.io.File` for scratch paths: `plan/join-path`, which keeps the
    separator the folder already uses (a Windows scratch folder gets
    backslashes, as `File` gave it);
  - `parse-long` and `parse-double`: `number/parse-int` and
    `number/parse-decimal`, which accept decimal text only. On the Dart VM
    (3.13.4, checked) `int.tryParse` reads `" 5"` and `"0x10"`, and
    `double.tryParse` reads `"NaN"` and `"Infinity"`;
  - `\s` and `\d` in the parsers' patterns: explicit ASCII classes, which
    both runtimes read alike.
- **ffprobe's JSON** is decoded by the host and handed over as data
  (`parse/probe-facts`): JSON decoding is each runtime's own.
- **Golden vectors:** `kernel/test/golden/ffmpeg.edn` pins nine plans (v1
  and v2; FFmpeg 4, 6, 7 and a nightly; x264, LGPL, NVENC and VideoToolbox
  encoders; variable frame rate, a segment offset, tags, a cover, previews,
  a Windows scratch folder), argument by argument and graph by graph, with
  the parsers' results and the numbers FFmpeg is given. Both runtimes
  reproduce it.

## Consequences

- A Dart program can now build the exact FFmpeg command line the JVM
  engine runs; running it is M3d's (`Process.start`).
- The tests that pinned these namespaces moved with them
  (`kernel/test/watermark/ffmpeg/`); they run unchanged on the JVM.

## Sources (checked 2026-09-29)

- `java.lang.Double.toString` (Java 19+): the closest of the shortest
  decimals that round to the double, from those of length 1 or 2 when one
  digit is enough:
  <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Double.html#toString(double)>
- Dart `double.toString`: the shortest string that correctly represents the
  number: <https://api.dart.dev/dart-core/double/toString.html>
- Dart `int.parse` (a `0x` prefix with no radix):
  <https://api.dart.dev/dart-core/int/parse.html>; the rest was run on Dart
  3.13.4.
