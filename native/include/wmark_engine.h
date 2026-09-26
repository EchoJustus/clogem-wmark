/* SPDX-FileCopyrightText: 2026 The clogem-wmark authors */
/* SPDX-License-Identifier: EPL-2.0 */
/*
 * wmark_engine.h -- C ABI for native wmark render engines.
 *
 * One small, stable, language-neutral seam for every non-FFmpeg engine:
 *   - Apple: AVFoundation + Core Image / Metal (Swift, exported with @_cdecl)
 *   - Android: Media3 Transformer (Kotlin via a thin JNI/C shim)
 *   - a future cross-platform GPU core (Rust + wgpu, cbindgen)
 *
 * Hosts that load it:
 *   - JVM editions (desktop server, macOS "server" edition, serverless):
 *     watermark.engine.native through Java's Foreign Function & Memory API,
 *     which GraalVM Native Image 25 supports (register the descriptors in
 *     reachability-metadata.json; see desktop/resources/META-INF/...).
 *   - Flutter GUIs (ClojureDart): dart:ffi against the same symbols.
 *
 * Design rules
 *   - Control plane only. Frames never cross this ABI: the engine owns
 *     decode, effects and encode, so a Metal pipeline stays zero-copy.
 *   - JSON in, JSON out (UTF-8, NUL-terminated). The render request is the
 *     kernel's render spec (schema: native/render-spec.schema.json), with the
 *     exact reference semantics in kernel/src/watermark/render.cljc and
 *     pinned expectations in kernel/test/golden/. Keywords arrive as
 *     strings ("image", "flip-y").
 *   - Strings returned by the library are owned by the library and must be
 *     released with wmark_free. Strings passed in are borrowed for the call.
 *   - Every fallible call reports failure by returning NULL and, when
 *     error_json is non-NULL, storing an error object there:
 *       {"kind": "invalid|unsupported|unavailable|failed", "message": "..."}
 *   - Thread safety: an engine handle may be used from several threads;
 *     events may arrive on any thread, including an engine-owned one.
 *   - Versioning: additive changes only within an ABI version. Hosts check
 *     wmark_abi_version() before anything else.
 */
#ifndef WMARK_ENGINE_H
#define WMARK_ENGINE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define WMARK_ENGINE_ABI_VERSION 1u

typedef struct wmark_engine wmark_engine; /* opaque */
typedef struct wmark_render wmark_render; /* opaque */

/* Event sink. `event_json` is borrowed for the duration of the call.
 *   {"event": "progress", "fraction": 0.42, "frame": 1234}
 *   {"event": "finished", "status": "done" | "failed" | "cancelled",
 *    "error": {"kind": "...", "message": "..."}}      -- exactly once, last
 * The host must not call wmark_render_release from inside the callback. */
typedef void (*wmark_event_fn)(void *user, const char *event_json);

/* ABI version implemented by this library (WMARK_ENGINE_ABI_VERSION). */
uint32_t wmark_abi_version(void);

/* Create an engine. config_json: {} or engine-specific options
 * (e.g. {"prefer_hardware": true}). */
wmark_engine *wmark_engine_open(const char *config_json, char **error_json);
void wmark_engine_close(wmark_engine *engine);

/* Identity and capabilities, same shape as watermark.engine/info:
 * {"engine/id": "avfoundation", "engine/version": "1.0", "available?": true,
 *  "problems": [], "capabilities": {"layers": ["image", "text"],
 *  "animations": ["flip-y"], "timing": ["always", "windows", "periodic"],
 *  "placement": ["fixed", "burst-scatter", "per-window"],
 *  "codecs": ["h264", "hevc"], "containers": ["mp4", "mov"],
 *  "audio": ["copy", "aac", "none"], "sources": ["file"]}} */
char *wmark_engine_info(wmark_engine *engine);

/* Media facts for a local path or URL, same shape as watermark.engine/probe:
 * {"kind": "video", "width": 1920, "height": 1080, "fps-num": 30000,
 *  "fps-den": 1001, "frames": 14385, "duration-s": 479.9, "start-s": 0.0,
 *  "vfr?": false, "has-audio?": true, "rotation": 0}  (display size) */
char *wmark_engine_probe(wmark_engine *engine, const char *source_utf8, char **error_json);

/* Validate and compile a render request (spec, source, media, output,
 * encode) into an opaque, serialisable plan. Nothing is written. */
char *wmark_engine_prepare(wmark_engine *engine, const char *request_json, char **error_json);

/* Start rendering a plan; returns at once. Events go to `on_event(user, ...)`
 * until the single "finished" event. */
wmark_render *wmark_render_start(wmark_engine *engine, const char *plan_json,
                                 wmark_event_fn on_event, void *user,
                                 char **error_json);

/* Request cancellation; idempotent; a "finished" event with status
 * "cancelled" (or whatever the render reached first) still follows. */
void wmark_render_cancel(wmark_render *render);

/* Free a render after its "finished" event has been delivered. */
void wmark_render_release(wmark_render *render);

/* Free any string returned by this library. NULL is ignored. */
void wmark_free(char *s);

#ifdef __cplusplus
}
#endif

#endif /* WMARK_ENGINE_H */
