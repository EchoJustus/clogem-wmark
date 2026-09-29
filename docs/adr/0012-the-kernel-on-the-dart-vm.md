<!-- SPDX-FileCopyrightText: 2026 The clogem-wmark authors -->
<!-- SPDX-License-Identifier: EPL-2.0 -->
# 0012. The kernel on the Dart VM: a harness, host primitives, and one validator for both runtimes

- **Status:** Accepted (2026-09-29). It carries out M3a within
  [ADR 0008](0008-desktop-architecture-and-binary-size.md), section 1, which
  left the choice between malli and "a small validator of our own driven by
  the same schema data" open.
- **Date:** 2026-09-29

## Context

M3a asks that ClojureDart and the Dart SDK be pinned, that the kernel compile
for the Dart VM, and that `kernel/test/golden/*.edn` pass there, in CI. It is
the first of the Phase 1 spike's exit criteria.

Until now the kernel's `:cljd` branches had never been compiled, and a first
compile found what stood in the way:

- **malli doesn't compile under ClojureDart**: 0.20.1 has no `:cljd` code, and
  `watermark.core.schema` and `watermark.render.schema` used it to validate,
  explain and decode.
- **Host gaps:** HMAC-SHA256 (keyed seeds) and SHA-256 (bitmap ids) threw on
  hosts other than the JVM. ClojureDart has no `byte-array` and no
  `unchecked-add`, `unchecked-subtract` or `unchecked-multiply`, which the
  rasterizer and the PRNG used.
- **ClojureDart's own rules**, each found by a failing compile or test:
  - its macro pass reads `.cljc` files with both the `:cljd` and the `:clj`
    features, and the first matching branch wins, so an ns form's `:clj`
    requires (malli, `java.*` imports) were loaded there;
  - an alias spelt like a Dart type is read as a static member of that type:
    `num/floor-int` called `floor-int` on Dart's `num`, so the kernel's `num`
    alias for `watermark.util.num` couldn't compile anywhere;
  - Dart runs no top-level forms when a library loads, so the `register!`
    calls for the continuous and scheduled modes never ran, and every text
    layer was "part of wmark Pro";
  - it evaluates a map literal's values in another order than the JVM, so a
    golden input that drew from one generator inside a map literal drew in
    another order;
  - on the Dart VM `(= 1 1.0)` is true (Dart's `==`), where the JVM tells an
    integer from a float.

## Decision

### 1. A harness in `kernel/dart`

- A ClojureDart project of kind `:dart`: ClojureDart 0.9.20260917, pinned by
  tag and full commit SHA (9f9cef5e0735026b667c28372e6967dcf43dc13e); the
  Dart SDK 3.13.4 (the pubspec's lower bound, and the version CI installs
  through `dart-lang/setup-dart`, pinned by commit SHA); `package:crypto`
  3.0.7, exactly (BSD-3-Clause, the Dart team), with `pubspec.lock`
  committed. Dependabot watches the pub package; ClojureDart moves by hand.
- It depends on the kernel and on its tests (`kernel/test/deps.edn`), and
  runs two test namespaces:
  - `golden-test`: every golden file, computed from the same inputs as the
    JVM's tests (`watermark.golden-inputs`, a `.cljc` file both runtimes
    load), compared strictly: a map, a vector and a number of the same kind,
    or the test names the first place they differ;
  - `kernel-test`: every kernel namespace loaded (the architecture test keeps
    the list complete), and what differs by host (64-bit wrapping, unsigned
    bytes, the built-in modes, JSON Schema being the JVM's).
- `bb kernel-dart` runs it; CI's `kernel-dart` job runs it on every pull
  request.

### 2. Host primitives for the Dart VM

One `#?(:clj … :cljd …)` branch per runtime, in the primitives the kernel
already had, and one more:

| Primitive | JVM | Dart VM |
|---|---|---|
| `watermark.core.seeds/keyed-seed` | `javax.crypto.Mac` | `package:crypto`'s `Hmac(sha256, key)`, first 8 bytes big-endian (`ByteData.getInt64`) |
| `watermark.raster/bitmap-id` | `MessageDigest` | `package:crypto`'s `sha256`, lowercase hex |
| `watermark.raster.image/u8-array` (new) | `byte-array`, read unsigned | `Uint8List` |
| `watermark.util.num/add-wrap`, `sub-wrap`, `mul-wrap` (new) | `unchecked-*` | `+`, `-`, `*`: native Dart integers are 64-bit two's complement and wrap on overflow |

The PRNG uses the wrapping operations, and the key is a byte array (a
`Uint8List` on the Dart VM). The `:cljd` branch comes first in ns forms, and
a JVM-only require is written `#?@(:cljd [] :clj [...])`.

### 3. One validator for both runtimes: `watermark.util.schema`

- A small interpreter of malli's vector syntax, for the part the kernel's
  schemas use (`:map`, `:multi`, `:vector`, `:tuple`, `:map-of`, `:maybe`,
  `:enum`, `:=`, `:re`, `:int`, `:double`, `:string`, `:boolean`, `:keyword`,
  `pos-int?`). It refuses anything else, so a new kind of schema can't pass
  unchecked.
- `errors` gives what `malli.error/humanize` gives: the same messages in the
  same nested shape. `decoder` does what malli's JSON transformer does for
  those types.
- **Both runtimes use it**: `schema/validate!`, `schema/decode-json` and
  `render.schema/validate!` run the same code on the JVM and on the Dart VM,
  so they give the same answers by construction. The schemas stay malli's
  data, and malli still emits JSON Schema on the JVM (`json-schema`, the
  files in `native/`); on the Dart VM `json-schema` is `:unavailable`.
- **malli is the oracle.** `kernel/test/golden/schema.edn` pins verdicts,
  messages and decoded values for a corpus of 32 settings and 11 render
  specs; both runtimes reproduce it, and on the JVM malli must agree with it
  except where this validator differs on purpose:
  - **a pattern must match the whole string.** malli searches for it
    (`re-find`), and Java's `$` also matches before a final line
    terminator, so a colour of `"red\n"` was valid on the JVM, but not in
    Dart or under the exported JSON Schema (ECMAScript's `$`). It is now
    refused everywhere;
  - numbers compare by kind as well as value in `:=` and `:enum`, as on the
    JVM.

### 4. More golden vectors, and the rules for kernel code

- `schema.edn` (above) and `form.edn`, the settings form: the model of a
  profile with every kind of row (all entitled, then with the community
  edition's locks), where each value comes from after a last run, and what
  18 edits make of the profile, refused ones included. Both ship in the
  engine SDK with the others.
- The built-in text modes are the registry's first value, not `register!`
  calls; code that registers modes on the Dart VM calls `register!` when its
  host starts.
- The `num` alias became `number` throughout, and the architecture test
  refuses an alias spelt like a Dart type (`num`, `int`, `double`, `bool`,
  `dynamic`, `void`).
- Side effects are never sequenced by a map literal (`let` does it).

## Consequences

- The kernel compiles for the Dart VM without a warning, and its six golden
  files pass there: the PRNG, the keyed seeds, render spec v1, every v2
  bitmap pixel for pixel, the schema corpus and the settings form. That is
  the spike's first exit criterion.
- On the JVM nothing changes but the stricter patterns: the golden files
  that existed are unchanged, and malli agrees with the new validator on the
  whole corpus otherwise.
- **Size:** nothing ships. The harness is a test; the downloads carry the
  same code, less malli's validators (unused now; JSON Schema still uses
  malli).
- **Speed on the Dart VM:** the rasterizer's arrays are hinted as `List`, so
  no call dispatches dynamically, but their elements are untyped;
  `List<double>` hints are later work, measured when the apps draw
  in-process (M3d).
- malli could later leave the runtime altogether if JSON Schema came from
  our own code (the exported files pin its output). Not needed now.

## Alternatives

- **malli on the Dart VM:** a port of malli to ClojureDart, which is far
  larger than the kernel's schemas need, and not ours to maintain.
- **Validate against the exported JSON Schema on the Dart VM:** a JSON
  Schema validator package, messages unlike malli's (which the API and the
  UIs show), and no decoding.
- **malli on the JVM, our validator only on the Dart VM:** two
  implementations kept in step by a corpus. The `$` difference shows they
  can disagree without anyone noticing; one implementation can't.

## Sources (checked 2026-09-29)

- ClojureDart 0.9.20260917 (commit 9f9cef5e0735026b667c28372e6967dcf43dc13e):
  <https://github.com/tensegritics/ClojureDart>; its compiler reads `.cljc`
  with `#{:cljd :cljd/clj-host :clj}` in the macro pass (`host-load-input`)
  and resolves `alias/member` as a static member when the alias names a type
  (`resolve-static-member`).
- Dart number representation (native integers are 64-bit two's complement;
  2^63 overflows to -2^63):
  <https://dart.dev/resources/language/number-representation>
- Dart `RegExp` (ECMAScript semantics): <https://api.dart.dev/dart-core/RegExp-class.html>
- `java.util.regex.Pattern`, "Line terminators" (`$` also matches just
  before the last line terminator):
  <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html>
- `package:crypto`: <https://pub.dev/packages/crypto>
- `dart-lang/setup-dart` v1.8.1 (commit 6afc89df92d6eb3834022f73cd65adc8cdfcb92d):
  <https://github.com/dart-lang/setup-dart>
