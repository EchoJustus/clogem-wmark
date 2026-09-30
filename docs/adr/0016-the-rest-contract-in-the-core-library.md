<!-- SPDX-FileCopyrightText: 2026 The clogem-wmark authors -->
<!-- SPDX-License-Identifier: EPL-2.0 -->
# 0016. The REST contract in the core library, for HTTP and in-process callers

- **Status:** Accepted (2026-09-30).
- **Date:** 2026-09-30
- **Builds on:** [ADR 0008](0008-desktop-architecture-and-binary-size.md),
  section 3 (GUI apps: a sidecar client first, then the core library
  in-process), [ADR 0013](0013-host-logic-into-the-core-library.md) and
  [ADR 0015](0015-a-job-queue-on-every-host.md) (the job queue on every host).

## Context

- `/api/v1` is the contract for GUIs, scripts and external UIs (decision 1).
  A GUI app in its sidecar phase speaks it over HTTP to the engine.
- In its in-process phase the same app runs the core library itself. It
  should get the same answers without an engine: every path, every JSON
  key, every error and status. An app that swaps transports then changes
  nothing else.
- The contract lived in `watermark.server.routes`, JVM code: it mapped each
  path to a Core API call, turned keywords into JSON strings, decoded path
  segments with `java.net.URLDecoder`, and mapped each kind of error to a
  status. A Dart program couldn't use it, and a copy would drift.

## Decision

### 1. `watermark.core.rest`: the contract as a function of the Core API

- **`(respond sys ctx method path read-body)`** is a task of the answer:
  - `{:status n :body data}`, where the data is JSON-ready: every key and
    keyword a string with its namespace, provenance paths as
    `"logo.anchor"`, sets and lists as vectors;
  - or `{:status 200 :file path :content-type "image/png"}` for a preview.
- **`read-body`** is called only when a route matches, and returns the
  request's JSON with keyword keys. Errors become answers (`error-answer`,
  `status-of`), so the task never fails.
- **The route table** (`routes`) and every handler moved in unchanged. The
  preview no longer waits for its frame: its handler chains the task, since
  nothing can wait on the Dart VM.
- **Path segments** are decoded by portable code (`decode-segment`): `%XX`
  escapes as UTF-8, `+` literal. A malformed escape, or bytes that aren't
  UTF-8, is now `422 invalid`. The JDK's decoder threw there, which the
  server reported as a 500, or it replaced the bytes with U+FFFD.
- **Sets** are written as sorted vectors when their items can be compared.
  Before, they came out in hash order.
- **`event-data`** is what one job event sends. `GET /api/v1/events` stays
  the transport's: an HTTP server streams job events; an in-process caller
  subscribes to them (`api/subscribe-jobs!`).

### 2. The JVM's HTTP adapter only reads and writes

`watermark.server.routes` reads the request's JSON (`clojure.data.json`),
waits for `rest/respond`, and writes the answer as JSON, or sends the
preview file with its caching headers. `routes/jsonable` and `routes/routes`
stay as names for the contract's own, so `watermark.web` and the server's
event stream are unchanged.

### 3. Both runtimes are held to the same answers

- **`kernel/test/golden/rest.edn`** records a session of 38 requests through
  `respond` on the pipeline's fake ports:
  - profiles: create, a taken name, invalid settings, a stale save, rename,
    lookup by slug and by an encoded display name, copy, delete;
  - the form, a draft, edits;
  - a preview and its file, the sample clip;
  - resolve, plan and a locked feature;
  - jobs submitted, listed and cancelled, with a queue that never starts a
    job, so every run is the same;
  - errors: no such endpoint, a body that isn't JSON, a malformed segment.
- It also records the events the jobs sent, fourteen path segments and
  JSON-ready data. Long answers are pinned by the SHA-256 of their JSON
  (`watermark.util.json`), so the file stays readable.
- The JVM and the Dart VM (`bb kernel-dart`) both reproduce it strictly.
  The settings' JSON Schema isn't in it: it comes from the JVM only
  (malli), and the Dart VM answers `503 unavailable`.
- On the JVM, `decode-segment` agrees with the JDK's decoder on 2,000
  random strings. On the Dart host, the contract answers in-process against
  the real profile store (`bb dart`).

## Consequences

- An app running the core library in-process sends the same requests it
  sends an engine, to `rest/respond` instead of a socket, and reads the
  same answers.
- A new route is added once, in the library, and both transports serve it.
- What callers of the HTTP API see changes only in the edge cases above:
  malformed segments are `422` rather than `500`, and sets come out sorted.
  Every other answer, including every one the browser suite checks, is the
  same.

## Alternatives

- **The in-process app calls the Core API directly and shapes the answers
  itself:** rejected. It would be a second copy of the contract's shaping,
  where a missing key or another status breaks the app on one transport
  only.
- **An HTTP server inside the app, on loopback:** rejected. It keeps a
  socket, a token and the Host and Origin guards in the app, to talk to
  itself.
