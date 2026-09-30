<!-- SPDX-FileCopyrightText: 2026 The clogem-wmark authors -->
<!-- SPDX-License-Identifier: EPL-2.0 -->
# 0013. The host's pure logic moves into the core library (M3c)

- **Status:** Accepted (2026-09-29), carried out in steps: section 1 on
  2026-09-29, section 2 on 2026-09-30. It records how
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

### 2. Text, the store port and the profile rules

- **Where:** `watermark.config` (the profile rules: names and slugs,
  `latest`, conflicts and revisions, resolution with provenance),
  `watermark.store` (the `ProfileStore` port and the document codec) and
  `watermark.store.memory` are in the library now, under their old names,
  so their callers didn't change. The JVM keeps what only it does:
  `watermark.home` finds the home folder (`--home`, `WMARK_HOME`, portable
  mode, the OS default) and opens the file store in it
  (`watermark.store.file`).
- **Text had to become the library's own.** The rules normalize names (NFC,
  then NFKC for slugs), lowercase them, trim and collapse whitespace, and
  keep letters, marks and numbers. On the Dart VM (3.13.4, checked):
  - there is no Unicode normalizer;
  - `String.toLowerCase` differs from Java 25's `toLowerCase(Locale.ROOT)`
    on 439 code points (U+0130 and U+0524 among them);
  - `\s` in regular expressions also matches 19 code points Java's doesn't
    (no-break and other Unicode spaces, the line and paragraph separators,
    the byte order mark);
  - ClojureDart's `clojure.string/trim` and `blank?` call `String.trim`,
    which also removes no-break spaces and the byte order mark.

  Both runtimes agree today on the general categories behind `\p{L}`,
  `\p{M}` and `\p{N}`, but a runtime moving to a newer Unicode version
  would then move slugs, and a slug is a file name.
- **Decision: the library carries its own Unicode data,** as it draws its
  own pixels (decision 2).
  - `watermark.util.unicode-data` is generated by `clojure -T:build unicode`
    from the Unicode Character Database 16.0.0 (the version Java 25
    implements), `UnicodeData.txt` and `DerivedNormalizationProps.txt`,
    pinned by SHA-256: general categories, canonical combining classes,
    decompositions, composition exclusions and simple lowercase mappings,
    as sorted strings (120 KB of source). The data is under the Unicode
    License v3 (`licenses/Unicode-3.0.txt`, `NOTICE`).
  - `watermark.util.unicode` computes categories, the four normal forms
    (UAX #15, Hangul by arithmetic) and Java's lowercase: simple mappings,
    capital I with dot to i + U+0307, and final sigma by Java's
    Final_Cased test over the boundaries of Java's legacy word
    `BreakIterator`, whose rules (`sun.text.resources.BreakIteratorRules`)
    it follows.
  - `watermark.util.text` is what portable code calls: `nfc`, `nfkc`,
    `lower`, Java's `Character.isWhitespace` for `trim` and `blank?`, ASCII
    whitespace collapsing, `replace-runs`, code-point counts.
  - Tests hold it to Java 25 on the JVM: every code point for the category
    and the five mappings; 20,000 random texts built from the code points
    where the rules meet; word boundaries on 20,000 random texts and on
    every code point in four contexts; and the slug the old Java-based code
    gave, on 20,000 random names. `kernel/test/golden/text.edn` pins digests
    of all of it, and the Dart VM reproduces them.
- **What reading Java's word iterator turned up,** modelled because it
  decides where a word ends:
  - the soft hyphen, a format character, isn't ignored like the others: the
    rules name it as punctuation inside a word;
  - the JDK's generator packs the ignore marker, -1, into the supplementary
    table's range entries, which borrows one from the range's end, so each
    run of format characters above U+FFFF loses its last code point
    (U+E0001 and U+E007F among them);
  - its reading of `UnicodeData.txt` counts seven unassigned gaps after CJK
    ideograph blocks (U+2A6E0 to U+2A6FF and six more) as letters.
- **One difference kept:** Java's final-sigma test asks
  `BreakIterator.isBoundary`, which starts one code unit back. Right after
  a supplementary character that is the middle of a surrogate pair, and
  Java then reports a boundary its forward iteration doesn't, so it may
  keep a word-final capital sigma as σ (`Α𐐨Σ` gives `α𐐨σ`). The library
  follows the forward boundaries (`α𐐨ς`). A slug with that sequence would
  move; no realistic profile name has one.
- **The document codec writes its own EDN** (`watermark.util.edn`): keys
  and set elements sorted by their text, one line when it fits in 80
  columns, else an entry per line, aligned; doubles keep their type
  (`24.0`) and never take an exponent. `clojure.pprint` isn't on the Dart
  VM, and its map order is hash order, which differs by runtime. A profile
  now comes out byte for byte alike from either host. Existing files read
  as before and take the new layout on their next save. Reading stays each
  runtime's own EDN reader.
- **Host primitives added** (each runtime's branch in one place):
  - `watermark.util.chars`: strings as code points and back
    (`watermark.raster.text/codepoints` now calls it);
  - `watermark.util.host`: catching the library's ex-info errors (the class
    differs, and ClojureDart's isn't a Dart `Exception`, so a catch-all is
    `Object` there), serializing a unit of work (ClojureDart has no
    `locking`; an isolate runs on one thread), the clock `*clock*`
    (rebindable, milliseconds since the epoch), and reading EDN
    (`clojure.edn` on the JVM, the kernel's only require outside itself
    besides `clojure.string` and malli, tested; `cljd.edn` on the Dart VM).
- **Timestamps** are written by `watermark.util.time/iso-instant` with
  millisecond precision (the JVM's `Instant.now` gave microseconds), equal
  to `java.time.Instant`'s text on 20,000 instants from year 0 to 9999.
- **Golden vectors:** `kernel/test/golden/profiles.edn` runs the rules on
  the memory store with a fixed clock: the slugs and display names of 24
  names people type, a scripted sequence (create, a conflict by alias and
  one by a stale revision, save by slug, rename, the reserved `latest`,
  auto-save, copy, invalid names, list, resolution with provenance, delete),
  the stored documents' text, decoding them back, and the errors of bad
  ones. Both runtimes reproduce it.
- **Bytes:** the new namespaces compile to 412 KB of JVM classes (the
  Unicode tables 128 KB, the algorithms 168 KB). The `wmark` native image
  for linux-x64 (GraalVM CE 25.0.2) grows by 1,441,792 bytes, 2.3%: from
  62,523,656 to 63,965,448 (measured 2026-09-30, the same build before and
  after this step). The size diet (P6) measures it again with `-Os`.

### 3. The pipeline and the use cases, on one async core

- **Where:** `watermark.core.jobs` (planning, rendering each input,
  running a job, the `JobQueue` port), `watermark.core.api` (every use case
  the clients call), `watermark.media` (the media port) and a new
  `watermark.files` port are in the library. The JVM keeps the adapters:
  local media, local files, the in-process queue, the engines.
- **The problem:** a render finishes later. The pipeline waited for it
  (`deref` of the engine's outcome), and the Dart VM can't wait: a `Future`
  has no blocking get, and `dart:cli`'s `waitFor` is gone (Dart 3.13.4,
  checked).
- **Decision (the owner, 2026-09-30): one asynchronous core,** not a
  synchronous one with the pipeline written twice.
  - `watermark.util.task`, a host primitive: a task is a
    `CompletableFuture` on the JVM and a `Future` on the Dart VM, with
    `then`, `recover`, `always`, `attempt`, `deferred` and a sequential
    `reduce`. On the JVM, `reduce` runs steps that are already done in a
    loop, so a batch of inputs that fail at once can't overflow the stack;
    `await` (JVM only) waits.
  - Only rendering is asynchronous. The engine's outcome is a task:
    `FFmpegProcessor` and `NativeFFIProcessor` settle a `CompletableFuture`,
    which `deref` still reads, and a promise from another engine is still
    accepted. `render-input!`, `run-job!`, `api/run-batch!` and
    `api/preview-frame` return tasks; everything else, planning included,
    returns its value.
  - The other ports stay synchronous (probing, media, files, stores). The
    library runs off a UI's thread on every host: the JVM on the server's or
    queue's threads, the apps in a background isolate (M3d). Dart's
    `dart:io` has synchronous calls for files and for short processes.
  - JVM hosts that block wait at their edge: the CLI, the local server's
    routes, the web UI (through `api/await`, since the web UI talks to the
    Core API only) and the in-process queue.
- **The files port** covers what previews need beyond media: whether a
  file is there, making a folder, listing one with modification times,
  deleting. `preview-file` now returns the PNG's path; the route opens it.
  Preview ids come from `random-uuid`, which both runtimes have.
- **Golden vectors:** `kernel/test/golden/pipeline.edn` runs the use cases
  on fake ports: an engine that checks capabilities and records what it's
  asked, media with a missing input and a taken output, files in an atom.
  It pins planning, the refusals (no inputs, a bad cover time, an unknown
  profile, a locked feature), a batch with a failed cover, a conflict and a
  missing input, `latest` after it, a cancelled batch, and previews on a
  video and on the sample clip, down to every call the ports received. On
  the Dart VM it runs on Futures, and the test ends when they do.
- **Checked on the native image** (linux-x64, GraalVM CE 25.0.2): a batch
  with a missing input, and a render with an embedded cover, through the
  real FFmpeg. The image grows by 196,608 bytes, to 64,162,056.
- **Dart tests and Futures:** a ClojureDart `deftest` whose body returns a
  `Future` is awaited, and a failed check inside a callback is reported
  (checked with a deliberately failing test). Such a failure also times out
  after package:test's 30 seconds; a passing test ends at once.

### 4. The FFmpeg engine's decisions, without a process-runner port

- **Where:** `watermark.ffmpeg.engine` holds what the FFmpeg engine decides
  apart from running FFmpeg:
  - the command lines besides a render (describe, trial encode, probe,
    sample clip, still decoding);
  - capabilities from what a build prints: filters, encoders, trial
    results, and the warnings and problems that follow;
  - the checks before planning, and the plan with its scratch folder;
  - reading `-progress` output, and a run's outcome;
  - checking a decoded still.

  `watermark.engine.ffmpeg` on the JVM now only finds the binaries (with
  `watermark.util.locate`), runs them and writes the scratch files.
- **No process-runner port, although ADR 0008 names one.** The shared part
  of running FFmpeg is the decisions above, and they are now the
  library's. What's left is process I/O, and it has no common shape worth
  a port:
  - capability discovery needs "run this trial and kill it after 30
    seconds", which the Dart VM can't do synchronously and the engine's
    `info` needs synchronously;
  - a render needs streamed output and cancellation on each host's own
    terms (a thread per render on the JVM, `Process.start` and streams in
    Dart).

  Each host's engine adapter runs processes and hands their output to the
  library. M3d writes the Dart one: `Process.run` for the short commands,
  `Process.start` for renders, and the host's own way to time out a trial.
- **Golden vectors:** `ffmpeg.edn` gains an `:engine` section: the command
  lines; `describe` of recorded output; discovery for a full build, an
  LGPL build, a build without `drawtext` and one without the required
  filters, with a failing preferred encoder; FFmpeg not found; a split
  build; the checks (unavailable, a cover in MOV, a forced encoder the
  build lacks, a codec it can't encode); progress read from `-progress`
  lines; outcomes; scratch paths; and a decoded still and a failed one.
  Both runtimes reproduce it.
- **Checked:** the FFmpeg conformance tests (render spec v1 and v2 on real
  frames), previews and covers pass through the thinned JVM adapter, and
  the native image (linux-x64) runs a batch with a cover through it. The
  image grows by 65,536 bytes, to 64,227,592.

## Consequences

- A Dart program can now build the exact FFmpeg command line the JVM
  engine runs; running it is M3d's (`Process.start`).
- The tests that pinned these namespaces moved with them
  (`kernel/test/watermark/ffmpeg/`); they run unchanged on the JVM.
- A Dart program can manage profiles exactly as the JVM does, over any
  `ProfileStore`; a Dart file store is M3d's.
- Profile files take the sorted layout on their next save, and new
  timestamps have millisecond precision.
- Moving to a newer Unicode version is a deliberate step: pin the new UCD
  files, regenerate, run the tests on a JDK of that version, and review the
  golden diffs (slugs may move for newly assigned characters).
- The Core API is the same on both runtimes. Its callers on the JVM wait
  for the two functions that render (`api/await`); the commercial
  repository's hosted worker does the same with `run-job!` when it next
  pins the core.
- What a Dart program still lacks is adapters, and M3d writes them over
  `dart:io`: an FFmpeg engine that runs processes and hands their output to
  `watermark.ffmpeg.engine`, media, files and a store.

## Sources (checked 2026-09-29 and 2026-09-30)

- `java.lang.Double.toString` (Java 19+): the closest of the shortest
  decimals that round to the double, from those of length 1 or 2 when one
  digit is enough:
  <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Double.html#toString(double)>
- Dart `double.toString`: the shortest string that correctly represents the
  number: <https://api.dart.dev/dart-core/double/toString.html>
- Dart `int.parse` (a `0x` prefix with no radix):
  <https://api.dart.dev/dart-core/int/parse.html>; the rest was run on Dart
  3.13.4.
- Unicode Standard Annex #15, Unicode Normalization Forms:
  <https://www.unicode.org/reports/tr15/>
- The Unicode Character Database 16.0.0:
  <https://www.unicode.org/Public/16.0.0/ucd/>; the Unicode License v3:
  <https://www.unicode.org/license.txt>
- Java 25's `Character` implements Unicode 16.0:
  <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Character.html>
- JDK 25 sources (tag `jdk-25+36`): `java/lang/ConditionalSpecialCasing.java`
  (Final_Cased), `sun/text/resources/BreakIteratorRules.java` and
  `sun/text/RuleBasedBreakIterator.java` (the word rules, `isBoundary`),
  and `make/jdk/src/classes/build/tools/generatebreakiteratordata/`
  (`SupplementaryCharacterData.java`, `CharacterCategory.java`):
  <https://github.com/openjdk/jdk/tree/jdk-25%2B36>
- Dart `String.trim` removes Unicode White_Space and the BOM:
  <https://api.dart.dev/dart-core/String/trim.html>; the lowercase and
  regular-expression differences were measured on Dart 3.13.4.
