# Native engines: the C ABI

`include/wmark_engine.h` is the seam for every render engine that isn't
FFmpeg: AVFoundation on Apple, Media3 on Android, and a possible Rust GPU core
later. One library serves every host:

- **the JVM editions** (desktop server, the macOS server edition, the hosted
  backend) load it through `watermark.engine.native` (`NativeFFIProcessor`),
  using Java's Foreign Function & Memory API;
- **the Flutter apps** call the same symbols through `dart:ffi`.

What an engine must do, independently of this ABI, is in
[docs/ENGINE.md](../docs/ENGINE.md): the render spec, the reference semantics,
capabilities, and the conformance harness.

## Contents

| File | Purpose |
|---|---|
| `include/wmark_engine.h` | The ABI, version 2 |
| `mock/mock_engine.c` | A small engine the test suite compiles and drives: a reference for ownership, threading, events and reading requests (it carries a small JSON parser). It composites render spec v2 for real and passes the conformance harness; v1 it only pretends to render. |
| `render-spec.schema.json` | JSON Schema of render spec version 1, exported from `watermark.render.schema` |
| `render-spec-v2.schema.json` | JSON Schema of render spec version 2 (host-drawn bitmaps), from the same namespace |
| `settings.schema.json` | JSON Schema of user settings, exported from `watermark.core.schema` |

The schemas let hosts and engines without malli validate what they send and
receive. Tests keep each file equal to the code that exports it. The v1
schema was checked with an independent validator (Python `jsonschema`)
against specs produced by the kernel.

## The call sequence

```
wmark_abi_version() in 1..2                   check first; refuse anything else
engine = wmark_engine_open("{}", &err)
info   = wmark_engine_info(engine)            capabilities; free with wmark_free
media  = wmark_engine_probe(engine, path, &err)
size   = wmark_engine_decode_still(engine,    ABI 2, render spec v2 only: the host
           {"source": logo, "output": scratch}, &err)   draws bitmaps from the pixels
plan   = wmark_engine_prepare(engine, request_json, &err)
render = wmark_render_start(engine, plan, on_event, user, &err)
         on_event(user, {"event":"progress","fraction":0.42,"frame":1234})   any thread
         on_event(user, {"event":"finished","status":"done"})               exactly once, last
wmark_render_release(render)                  after "finished", never inside the callback
wmark_engine_close(engine)
```

- **Control plane only.** Frames never cross the ABI: the engine owns decode,
  effects and encode, so a Metal or GPU pipeline stays zero-copy.
- **JSON in and out**, UTF-8 and NUL-terminated. Keywords arrive as strings
  (`"image"`, `"flip-y"`), and namespaced keys keep their namespace
  (`"engine/id"`).
- **Ownership.** Strings the library returns are released with `wmark_free`.
  Strings passed in are borrowed for the duration of the call.
- **Errors.** A fallible call returns NULL and, when `error_json` isn't NULL,
  stores `{"kind": "invalid|unsupported|unavailable|failed", "message": "..."}`.
- **Threads.** An engine handle may be used from several threads. Events may
  arrive on any thread, including one the engine owns.
- **Cancellation** is a request: a `finished` event still follows, with
  `cancelled` or whatever status the render reached first.
- **Versioning** follows the rule in the header:
  - a library implements exactly one ABI version, reports it from
    `wmark_abi_version()`, and exports every function of that version;
  - a host accepts every version from 1 up to its own and calls only the
    functions of the version the library reports (wmark takes 1 and 2;
    hosts built for ABI 1 take only 1);
  - within a version, changes are additive only: new optional JSON fields
    that readers ignore when unknown. A new or changed function, or a field
    that changes meaning, is a new version.
- **Render spec versions** are negotiated apart from the ABI, through the
  `spec-versions` capability (`[1]` when absent). Version 2 needs ABI 2: the
  host asks the engine to decode the logo (`wmark_engine_decode_still`,
  straight RGBA8 to a scratch file), draws every bitmap itself, and the
  engine only composites them at the frames and positions the spec gives.
  An ABI 1 library is treated as `[1]`, whatever it lists.

### ABI history

| Version | Adds |
|---|---|
| 1 | The first ABI: open, info, probe, prepare, render with events, cancel. |
| 2 | Render spec v2: `wmark_engine_decode_still`, the `spec-versions` capability, `flipbook` and `bitmap` layers. |

## Building an engine

- **Swift (Apple).** Export each function with `@_cdecl("wmark_engine_open")`
  and so on, from a dynamic library or framework. Render with an
  `AVMutableVideoComposition` whose custom `AVVideoCompositing` compositor
  draws the spec's layers for each frame index, using Core Image or Metal;
  encode with `AVAssetWriter`. On iPad, map progress and cancel onto
  `BGContinuedProcessingTask` for exports that continue in the background.
- **Rust.** Use `#[no_mangle] pub extern "C" fn`, and generate a header with
  `cbindgen` to diff against this one. Catch panics at the boundary
  (`std::panic::catch_unwind`) so they never unwind into the host.
- **Kotlin (Android).** A thin JNI shim in C exports the symbols and forwards
  to a Media3 Transformer engine. Per-frame overlays (`BitmapOverlay`) or a
  custom `GlEffect` draw the layers.

## Trying and testing an engine

1. **Build the mock** to see a working library (`-DWMARK_MOCK_ABI=1` builds
   it as an ABI 1 library, as the tests do):
   ```
   bb mock-engine        # cc -shared -fPIC -pthread -> target/libwmark_engine.so
   ```
   On macOS use `-dynamiclib` and name it `libwmark_engine.dylib`; on Windows
   build `wmark_engine.dll` with MSVC or MinGW.
2. **Load it** into wmark:
   ```
   wmark --engine native --native-lib target/libwmark_engine.so doctor
   ```
   ```
   engine: mock 2.0  [ready]
   library: /…/target/libwmark_engine.so  (from explicit)
   ```
   Without `--native-lib` (or `WMARK_ENGINE_LIB`), wmark searches like it does
   for FFmpeg, with `lib/` as the subfolder: the working directory, its `lib/`,
   wmark's own folder, its `lib/`, then PATH.
3. **Run the test suite.** `test/watermark/engine/native_test.clj` compiles
   the mock and drives it through the binding, then through the unchanged job
   pipeline. It covers the handshake and the compatibility rule (the mock
   built as ABI 1 and 3), probe errors, progress events from the library's
   own thread, cancellation, still decoding, a v2 render checked frame by
   frame, and a capability refusal made before the library is called.
4. **Run the conformance harness** (`testkit/src/watermark/engine/conformance.clj`)
   against your engine. It uses the engine protocol only: it renders test
   clips, decodes the frames, and measures them against the reference
   semantics. `test/watermark/engine/native_v2_conformance_test.clj` runs it
   on the mock.
5. **Reproduce the golden vectors** (`kernel/test/golden/`) if your engine
   re-implements kernel logic such as the PRNG or keyed seeds;
   `render-v2.edn` pins render spec v2 down to every bitmap's pixels (by
   hash).

## Native Image

GraalVM Native Image 25 supports the FFM API when every call shape is
registered ahead of time. The six downcall shapes (ABI 2's
`wmark_engine_decode_still` shares `probe`'s), the one upcall shape and
the reflective `IFn.invoke` the upcall targets are listed in
`desktop/resources/META-INF/native-image/clogem/wmark/reachability-metadata.json`,
and the build passes `--enable-native-access=ALL-UNNAMED`. A new function in
the ABI needs its shape added there. This hasn't been verified in a native
build yet: build one and run `doctor` with the mock first.
