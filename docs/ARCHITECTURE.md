# Architecture

wmark is a headless engine with a stable API and interchangeable clients (the
Clash model), split so that each roadmap stage adds adapters instead of
rewriting code. [ROADMAP.md](ROADMAP.md) maps the stages onto this structure;
[ENGINE.md](ENGINE.md) specifies the render engine contract.

## Components

Each directory is a source root with its own alias in `deps.edn`. The
components other repositories build on (`kernel/`, `web/`, `desktop/`,
`testkit/`, `build/`) also have their own `deps.edn`, so they can be consumed
from git with `:deps/root`; `bb lint` keeps each one in step with its alias.
The commercial editions (Pro modes, licenses, the hosted backend and the apps)
live in a separate, private repository that depends on this one. Nothing here
names or requires them.

| Directory | Contents | Runs on | May depend on |
|---|---|---|---|
| `kernel/` | Settings schema and resolution, the render spec (v1 and v2) and its reference semantics, the rasterizer that draws v2's bitmaps (TrueType, text, the warp), keyed seeds, the PRNG, the text-mode registry, the engine protocol, the feature catalog, the settings form, the FFmpeg plan compiler and parsers, the profile rules and the store port, the job pipeline and the Core API, the media and files ports with the media fingerprint and output names, the command line (`watermark.cli`: options, commands, their output, the progress display), the executable search (`watermark.util.locate`), its own Unicode tables | Any Clojure host: GraalVM/JVM, and the Dart VM through ClojureDart (`kernel/dart` runs its golden vectors there; ADRs 0008, 0012) | malli (two namespaces, JVM only, for JSON Schema) and `clojure.edn` (one host primitive, JVM only); `package:crypto` on the Dart VM; nothing else |
| `src/` | The home folder (`home`), the store, media, files, queue and rasterizer ports' local adapters, the FFmpeg and native engines, JSON REST routes | JVM | kernel |
| `dart/` | The Dart host (ADR 0014): the core library's adapters over `dart:io` (files, media, profiles, the rasterizer's I/O, the FFmpeg engine) and `wmark-dart`, the command line on the Dart VM | the Dart VM (ClojureDart) | kernel |
| `web/` | The built-in web UI: server-rendered HTML and Datastar events over SSE; vendored `datastar.js`, no npm | JVM | the Core API (kernel) |
| `desktop/` | CLI (with terminal progress), http-kit server, loopback security, sidecar mode, native-image metadata | JVM / native image | src, web |
| `testkit/` | Harnesses for code that plugs in from elsewhere: engine conformance, the store contract, golden vectors, architecture checks | JVM (tests) | src, kernel |
| `build/` | `wmark.build`, the interpreter of the build matrix | JVM (tool) | tools.build |
| `native/` | C ABI for native engines, a mock engine (the test double), exported JSON Schemas | C header and mock; platform engines in their OS's language behind it (decision 3) | nothing |

**The kernel grows into the core library**
([ADR 0008](adr/0008-desktop-architecture-and-binary-size.md), accepted).
- It is compiled for GraalVM and, through ClojureDart, for the Dart VM, so
  Dart and Flutter programs can embed it as JVM programs do.
- The pure logic still in `src/` moves into it behind ports: the profile
  rules, planning, the FFmpeg plan compiler and output parsers, and the use
  cases. New pure logic, such as the settings form model, starts there.
- `src/`, `web/` and `desktop/` then hold the GraalVM host's adapters: I/O,
  processes, the server, the views and the native window.

## Layers

```
 clients        web UI (Datastar, in a browser or the desktop window of ADR 0008) · CLI · scripts · GUI shells (sidecar)
                    │ HTML + SSE       │ REST + SSE                │ argv
 transports     desktop: http-kit, token, Host/Origin     desktop: watermark.app (CLI)
                saas:    function adapter + wrap-identity (OIDC)
                    │
 contract       watermark.web.handler (/ and /ui/*) and watermark.server.routes (/api/v1)
                    (Ring; no transport, no auth) ─► watermark.core.api
                    │
 orchestration  watermark.config (profile rules)        watermark.core.jobs (pipeline)
                    │                                          │
 kernel         resolve · render/build → render spec · seeds · modes · features
                    │
 ports          ProfileStore     MediaIO     JobQueue     VideoEngine     Entitlements    Rasterizer
 adapters       file · memory ·  local       local        FFmpeg ·        community ·     local
                PostgreSQL       files       executor     native (C ABI)  license ·       (scratch
                                                                          hosted plan     files)
```

- **One binary, several modes.** Run with no arguments (a double-click), the
  engine serves on loopback and opens the browser. `serve` is headless, for
  scripts or a GUI shell. `run` encodes from the CLI through the same Core
  API, with a progress bar on a terminal. `profiles` manages profiles and
  shows what a run would use (`effective`). `doctor` explains which engine
  and binaries were found.
- **One program.** The terminal client `wmark-tui` was removed
  ([ADR 0010](adr/0010-remove-wmark-tui.md)): as a second native image it
  weighed nearly as much as the engine, and the CLI now does what it did.
- **External UI.** `--ui-dir` replaces the built-in UI with one served from a
  directory, like Clash's external-ui. It talks to the REST API.
- **The routes know nothing about transport or identity.** They read the
  caller from `(:wmark/ctx req)`. The desktop security middleware sets it to
  the local user; the hosted handler sets it from a verified token. Both
  deployments therefore serve the identical route table.

## Ports and adapters

| Port | Protocol | Adapters today | Planned adapters |
|---|---|---|---|
| Profile storage | `watermark.store/ProfileStore` (core library) | file (`store.file`, JVM), memory (`store.memory`, core library), a SQL store in hosted backends | app-sandbox store for the GUI, a Dart file store (M3d) |
| Rendering | `watermark.engine/VideoEngine` + `RenderHandle`, and `StillDecoder` for engines that take render spec v2 | `FFmpegProcessor`, `NativeFFIProcessor` (C ABI) | AVFoundation, Media3, a Rust core: all behind the C ABI |
| Host drawing (render spec v2) | `watermark.raster/Rasterizer` (`realize!`, `release!`) | local: bitmaps in a scratch folder per render (`raster.local`) | object storage next to hosted workers |
| Media | `watermark.media/MediaIO` (core library) | local files (`media.local`) | object storage, `dart:io` (M3d) |
| Files (the preview folder) | `watermark.files/Files` (core library) | local files (`files.local`) | `dart:io` (M3d) |
| Queue | `watermark.core.jobs/JobQueue` | in-process executor (`jobs.local`) | SQS / Cloud Tasks / a Postgres table |
| Entitlements | `watermark.core.features/Entitlements` | community, offline license, hosted plan | StoreKit, Play Billing |
| Text modes | `watermark.core.modes/register!` (a registry) | continuous, scheduled; Pro: canary (wire id `subliminal`), random | — |

Text modes are a registry rather than a multimethod because ClojureDart has
no multimethods. The open core's two modes are the registry's first entries,
not `register!` calls, because the Dart VM runs no top-level forms when a
library loads.

**Validation.** The schemas are malli's vector syntax, but
`watermark.util.schema` validates, explains and decodes them, on the JVM and
the Dart VM alike, with malli's messages in malli's shape
([ADR 0012](adr/0012-the-kernel-on-the-dart-vm.md)). malli still turns them
into JSON Schema on the JVM (`/api/v1/schema/settings`, `native/*.schema.json`), and
tests check that it agrees with the validator on a corpus
(`kernel/test/golden/schema.edn`). The one deliberate difference: a pattern
must match the whole string (Java's `$` also matches before a final line
break).

## Dependency rules

`test/watermark/architecture_test.clj` turns the rules into tests. They read
`ns` forms only and run in milliseconds. Deliberately adding forbidden
requires made them fail, as intended.

| Rule | Protects |
|---|---|
| The kernel is `.cljc` only and requires nothing outside itself except `clojure.string`, plus malli in the two schema namespaces (JVM only) | Stages 3–4: the GUI runs the kernel in-process |
| The Dart VM harness loads every kernel namespace, and no kernel alias is spelt like a Dart type (`num`, `int`, …) | M3a: the whole kernel compiles for the Dart VM |
| Pro schedules are portable too | Pro modes in the GUI apps |
| `watermark.core.*` requires no engine, media or store implementation, no OS utilities, no server code | Stage 4+: new engines don't touch orchestration |
| `src/` requires nothing from desktop, web, Pro, SaaS or http-kit | Stage 5: the backend reuses the host core as is |
| `web/` talks to `watermark.core.api` only: no engines, stores, media, config, jobs internals, desktop, Pro or SaaS code | The same views serve the local UI and a hosted dashboard |
| The backend doesn't use the desktop server | Lean binaries and images |

## Core API ↔ REST

| Core API (`watermark.core.api`) | Route |
|---|---|
| `health` | `GET /api/v1/health`: version, edition, engine status |
| `diagnose` | `GET /api/v1/doctor`: the engine, its problems and warnings, where each binary came from |
| `features` | `GET /api/v1/features` |
| `settings-schema` | `GET /api/v1/schema/settings`: JSON Schema, with Pro fields tagged `x-tier: pro` |
| `list-profiles` / `get-profile` | `GET /api/v1/profiles`, `GET /api/v1/profiles/:name` |
| `create-profile!` | `POST /api/v1/profiles` `{name, settings}` |
| `save-profile!` | `PUT /api/v1/profiles/:name` `{settings, overwrite?, if-rev?}` |
| `rename-profile!` / `copy-profile!` | `POST /api/v1/profiles/:name/rename` and `/copy` with `{to}` |
| `delete-profile!` | `DELETE /api/v1/profiles/:name` |
| `settings-form` | `GET /api/v1/profiles/:name/form`: the settings form model, a row per setting with its value, where it came from, its control and its choices ([ADR 0011](adr/0011-web-ui-product-overhaul.md), section 3) |
| `edit-profile!` | `POST /api/v1/profiles/:name/edit` `{op, id?, value?, mode?, index?, delta?, if-rev?}`: one form edit (set or reset a setting, add, remove or move a text layer), saved; answers with the profile and its new form |
| `draft-form` | `POST /api/v1/profiles/:name/form` `{settings, edit?}`: the form of an unsaved draft, after one more edit when given; nothing is saved (Drafts, below) |
| `preview-frame` / `preview-file` | `POST /api/v1/preview` `{profile?, settings?, source?, t?, aspect?}`, then `GET /api/v1/previews/:id` (`image/png`): one frame of the render, drawn by the engine (section 5 of the same ADR) |
| `resolve-settings` | `POST /api/v1/resolve`: effective settings, provenance, locked features |
| `plan-batch` | `POST /api/v1/plan`: dry run returning each render spec and engine plan |
| `submit-job!` / `list-jobs` / `cancel-job!` | `POST`/`GET /api/v1/jobs` `{inputs, profile?, settings?, cover?}`, `DELETE /api/v1/jobs/:id`. `cover {t}` embeds each copy's frame at t seconds as its cover picture (MP4); per run, never saved |
| `subscribe-jobs!` / `unsubscribe-jobs!` | `GET /api/v1/events`: server-sent JSON events (desktop transport) |

Every function takes `(sys ctx ...)`. `ctx` is `{:tenant :user}`: locally
`{"local" "local"}`, hosted it comes from the token. `(:profiles-for sys)`
returns the store for that caller. **Jobs are tenant-scoped.** `list-jobs`,
`cancel-job!` and `subscribe-jobs!` only see the caller's tenant, so a shared
hosted queue never shows one studio another's work.

Error kinds map to HTTP statuses:

| Kind | Status | Example |
|---|---|---|
| `:invalid` | 422 | settings fail the schema |
| `:unsupported` | 422 | "The ffmpeg engine can't render this: layers text." |
| `:not-found` | 404 | unknown profile |
| `:conflict` | 409 | name collision, or a stale `if-rev` (`reason: "stale"`) |
| `:feature-locked` / `:feature-unavailable` | 402 | a Pro mode without entitlement, or in the community binary |
| `:unavailable` | 503 | no usable engine |

On the wire, keywords keep their namespaces (`"text.mode/subliminal"`);
plain data.json would drop them.

**Text-mode names.** The canary mode's wire id is `subliminal`: settings,
profiles, `/api/v1` and render specs carry it, and the keyed seed hashes it,
so it can't change without moving every existing schedule. Users only ever
see and type `canary` ([ADR 0005](adr/0005-canary-display-name.md)):
- Every entry point accepts `canary` and resolves it to the wire id through
  `features/canonical-settings`: `schema/decode-json`, `schema/validate!` and
  the planner.
- The CLI (`profiles show`, `profiles effective`, help), the web UI (the
  editor and the effective table) and error messages show the display name.
- API clients learn it from `/api/v1/features` (`display-name` on
  `text.mode/subliminal`). The JSON Schema titles that branch `canary`.

### Drafts

A UI that saves only when asked (the GUI apps' "auto-save off") keeps the
profile's own settings as a **draft** on its side and asks the engine for the
draft's form: `POST /api/v1/profiles/:name/form` `{settings, edit?}`.
- `settings` are the profile's own settings as edited so far. They replace
  the saved ones; they are not overrides on top, so a reset in a draft goes
  back to the built-in default, as it would once saved.
- `edit` is one more form edit (the same operations as `/edit`) applied to
  the draft first. The answer carries the new draft (`settings`, validated
  and canonical) and `unsaved?`.
- Rows whose value differs from the saved profile have the source
  `unsaved` ("Not saved yet"). Nothing is written.
- The draft is saved with `PUT /api/v1/profiles/:name` and the revision it
  was read at (a stale write is a 409), or as a new profile with
  `POST /api/v1/profiles`. Previews and jobs take it as `settings` with
  `clean: true` (no profile under it).

## The built-in web UI (`web/`)

The UI is server-rendered: Clojure renders HTML, and
[Datastar](https://data-star.dev) (one vendored 13 KB script) morphs fragments
into the page. There is no Node.js, npm or JavaScript build, and no JavaScript
of our own.

| Namespace | Role |
|---|---|
| `watermark.web.html` | Hiccup to HTML, escaping every text node and attribute value |
| `watermark.web.sse` | Datastar's event format (`datastar-patch-elements`, `datastar-patch-signals`), written directly and checked against the 15 official SDK wire-format cases |
| `watermark.web.views` | Pure functions from Core API results to hiccup: the app shell and theme switcher, profiles, the JSON editor, effective settings with provenance, queue |
| `watermark.web.form` | The settings form (click to edit, one row per setting) and the preview panel, from the Core API's form model |
| `watermark.web.handler` | `GET /` renders the page. Actions under `/ui/` (select, create, save, rename, duplicate, delete, the form's field and layer edits, preview frames, the theme, submit, cancel) answer with events. `GET /ui/stream` holds the queue open |

**The look** ([ADR 0011](adr/0011-web-ui-product-overhaul.md), sections 1–2):
`web/resources/public/app.css` is one file of tokens (colour, space, type,
shape) and the components built from them. Light and dark themes come from
the same tokens: the operating system's choice by default, or one the person
pins with the switcher (remembered in a cookie). The layout is an app shell:
profiles on the left, the settings form in the middle, the preview on the
right, the render queue below.

**How a page behaves:**
- **Every interaction is a request** under `/ui/`. The response carries
  events that patch elements by id and update signals. For example, the
  profile's revision (`rev`) comes back after each save and goes with the next
  one, so a stale save is refused with a 409.
- **The queue is one stream per visible tab.** Every (re)connect sends the
  whole queue. After that, row updates are coalesced to at most ten a second,
  whatever the engine emits. Datastar closes GET streams in hidden tabs and
  reopens them, and a reopen simply re-renders.
- **The settings form edits one path at a time.** A row shows its value; a
  click asks the server for the row in edit mode, whose control is bound to
  the `fv` signal. Enter, Save or a changed choice sends it; the server saves
  that one path (`edit-profile!`) with the revision the page read and answers
  with the whole form in view mode. A bad value is explained at the field; a
  stale revision is a 409, as for any save.
- **The preview is the render's own frame.** The preview panel asks for a
  frame when it appears and whenever the shape, the time, the video or the
  saved settings change (Datastar's `data-effect`). The server draws it
  through the engine, one at a time with the newest request winning, and the
  page loads the PNG from `/api/v1/previews/:id` with its session cookie.
- **The JSON view stays, for bulk edits.** "Edit as JSON" resolves the
  unsaved text (debounced) and shows each value's source. Invalid JSON or
  schema errors appear as a message; the table keeps its last good state.

**Security specific to the UI** (tests: `web_ui_test`, `views_test`, `html_test`):
- **Pages carry data,** so `/` and `/ui/*` need the token, like `/api/*`.
- **`/ui/*` also requires the `Datastar-Request` header,** which cross-site
  forms can't set.
- **A fresh CSP nonce per page.** Datastar's CSP mode compiles expressions into
  nonce-carrying scripts, so the policy needs neither `unsafe-eval` nor
  `unsafe-inline`.
- **Injected markup would run despite the CSP,** because Datastar evaluates
  `data-*` attributes and runs `<script>` tags inside patched fragments. So all
  markup goes through the escaping renderer. User text never appears inside a
  `data-*` expression: it travels as escaped element text, or as JSON signals
  that the browser parses with `JSON.parse`. URLs inside expressions contain
  only percent-encoded slugs and server-generated ids.

**Hosting.** The same handler can serve a hosted dashboard behind its own
authentication (`(handler sys ctx)`). It needs a long-running container,
because every open tab holds a stream. See [ROADMAP.md](ROADMAP.md).

## The job pipeline

`watermark.core.jobs` talks only to ports, and is part of the core library
([ADR 0013](adr/0013-host-logic-into-the-core-library.md), section 3).
Planning is synchronous; a render finishes later, so its outcome is a task
(`watermark.util.task`: a `CompletableFuture` on the JVM, a `Future` on the
Dart VM, which can't wait for one). `render-input!` and `run-job!` return
tasks, and so do `api/run-batch!` and `api/preview-frame`. JVM hosts that
block (the CLI, the routes, the web UI, the in-process queue) wait for them
with `api/await`. For each input:

1. `media/open-input` checks the file and fingerprints it (for keyed seeds).
2. `engine/probe` returns media facts. The input must be a video.
3. `engine/probe` on the logo returns its size.
4. `render/build` produces the render spec: every layer resolved to pixels and
   frame indices.
5. The spec version is chosen (`jobs/spec-version`): v1 where the engine can
   draw all of it, else v2. For v2, the `Rasterizer` draws every bitmap
   (the engine decodes the logo) and returns the v2 spec; its files live
   until the render ends or planning fails.
6. `media/open-output` reserves a temporary `.part` path.
7. `engine/prepare` checks capabilities, then compiles an engine plan.
8. `engine/execute!` renders, reporting progress. On `:done`,
   `media/commit!` publishes the output atomically; otherwise
   `media/discard!` removes it.

A failure in one file is recorded for that file and the batch continues. A
dry run (`plan-batch`) runs steps 1–7 with `:dry-run? true`: no output is
created, `latest` isn't touched, and a v2 plan's bitmaps are deleted as soon
as the plan is returned.

## Configuration (`watermark.config`)

The profile rules are part of the core library
(`kernel/src/watermark/config.cljc`, [ADR 0013](adr/0013-host-logic-into-the-core-library.md),
section 2): the same code runs on the JVM and the Dart VM, and
`kernel/test/golden/profiles.edn` pins slugs, conflicts, fallback, provenance
and the text of stored documents on both. Where the home directory is, and
the file store in it, are the JVM host's (`watermark.home`).

**Home directory**, first match wins:
1. `--home`
2. `WMARK_HOME`
3. `./wmark-data`, if it exists (portable, unzip-and-run installs)
4. the OS default: `%APPDATA%\wmark`, `~/Library/Application Support/wmark`,
   or `$XDG_CONFIG_HOME/wmark`

**Profiles** have a display name and a slug:

- `"16:9 Video Profile"` is stored under the slug `16-9-video-profile`.
  Colons are illegal on Windows, and names are case-folded because NTFS and
  APFS are case-insensitive. Windows device names are escaped (`CON` becomes
  `_con`). Letters in any script survive.
- Two different names that map to the same slug are a **conflict**, never a
  silent overwrite. Addressing a profile by its slug never renames it.
- On disk, a profile is a human-editable EDN file, `<home>/profiles/<slug>.edn`,
  written by `watermark.util.edn`: keys sorted, one entry per line where a
  map doesn't fit on one, byte for byte the same on every host.
- **Text rules are the library's own.** Normal forms, lowercase, whitespace
  and "letter, mark or number" come from its copy of the Unicode Character
  Database (16.0.0, `watermark.util.unicode`), not from the runtime, so a
  slug doesn't change between hosts or when a runtime moves to a newer
  Unicode version. They give Java 25's results, so existing slugs stay put,
  except in one corner: a capital sigma right after a character outside the
  Basic Multilingual Plane, where Java's word iterator errs (ADR 0013).

**`latest`** is reserved and auto-saved by every real run, before encoding
starts, so a crashed batch can be re-run with identical settings. Dry runs
don't touch it. Inputs and seeds are never persisted into it.

**Resolution** runs from lowest to highest precedence:
`built-in defaults < base profile < explicit overrides`.

- **Base profile:** the named profile, or `latest` when none is named, or
  nothing with `--clean`.
- **Missing parameters** (nil) fall through to the layer below.
- **Merging:** maps merge deeply; vectors, such as text layers, replace
  wholesale.
- **Provenance:** every field records which layer won it, so UIs can show
  "from last run".
- **An unknown explicit profile is an error**, never a silent fallback to
  `latest`.
- **A damaged `latest` produces a warning and is skipped**, so it can't block a run.

### Storage and concurrency

`watermark.config` implements all of the rules above on the `ProfileStore`
protocol and nothing else. The same functions therefore run over files, an
atom, or database rows. `testkit/src/watermark/store_contract.clj` is the executable
definition of the behaviour; every store must pass it.

- **Revisions.** Every profile carries `:profile/rev`. Writes are
  compare-and-set: create requires absence, replace requires the revision that
  was read, and `latest` is last-writer-wins.
- **Stale edits fail cleanly.** The web UI sends `if-rev`. If someone saved
  the profile in the meantime, the save returns 409 with `reason: "stale"`
  instead of overwriting their change. In the contract test, eight racing
  editors produce exactly one winner.
- **Units of work.** `-transact` runs a multi-step operation atomically. The
  PostgreSQL store uses a database transaction. The file and memory stores
  serialise operations and order their writes to fail safe: rename writes the
  new name before deleting the old, so a crash leaves a copy, never a loss.
- **File writes** go to a temp file, then fsync, then an atomic rename.
  Retries cover the brief file locks that Windows antivirus and sync clients take.
- **PostgreSQL** (the hosted backend, in the commercial repository): one row
  per tenant, owner and slug; row-level security keyed on a transaction-local
  tenant setting; `latest` per user, named profiles shared by the tenant. It
  passes the same store contract (`testkit/`).

## Security of the local server

A server on 127.0.0.1 is reachable by any web page the user visits and by
DNS-rebinding attacks. The defences, all covered by the HTTP test:

- **Per-launch token.** A random 256-bit token guards `/api/*` and the
  built-in UI (`/`, `/ui/*`). Scripts and GUI shells send `Authorization: Bearer`.
  The browser gets the token once via `/?token=`: the server sets an
  `HttpOnly; SameSite=Strict` cookie and redirects, which removes the token
  from the address bar. The cookie also authenticates the UI's streams.
- **Host allow-list** (loopback names plus the bound port) against DNS rebinding.
- **Origin check** on state-changing requests, against CSRF.
- **Browser hardening:** no CORS headers at all; a Content Security Policy
  with `frame-ancestors 'none'` (per-page nonce for the UI); path-traversal
  guards for static files.
- **Runtime file.** `<home>/runtime/server.edn` holds the URL and token,
  owner-only (0600 on POSIX), so scripts can find the server. A shutdown hook
  removes it.

**Sidecar mode for GUI shells.** `wmark serve --announce json --parent-pid <pid>`
prints one JSON line (`url`, `token`, `version`, `edition`, `pid`) for the
process that launched it, and exits when that process exits, so a crashed GUI
never leaves an orphaned server.

**No orphaned renders.** When the server exits, its shutdown hook ends every
process it started, FFmpeg included (`watermark.app/end-descendants!`):
asked first, then forced after two seconds. That covers Ctrl+C, SIGTERM and
the parent watch. Without it, FFmpeg kept rendering alone after the server
was gone, and its unfinished `.part` file stayed. A hard kill of the server
itself (SIGKILL, `taskkill /F`) runs no hook, so a GUI shell ends the whole
process tree when it stops the server.

## Open-core boundary

Pro features are protected twice:

1. **Code absence.** The community binary is built from this repository alone
   (verified: zero Pro classes in the AOT output). Its mode registry simply
   has no canary (`:subliminal`) or `:random` entry, so such a layer fails
   with `:feature-unavailable` ("part of wmark Pro").
2. **Entitlement.** The Pro binary registers its modes when
   `watermark.pro.modes` loads (at image build time under native-image). The
   core gate (`features/check!`) runs at planning time, and Pro code re-checks
   with `features/assert!`.

The hosted backend is built with Pro modes and gates them per request, from
the caller's plan.

The Pro and hosted code lives in a separate, private repository that depends
on this one at a pinned commit. The architecture test keeps the direction one
way: no namespace here is named `watermark.pro.*` or `watermark.saas.*`, none
requires one, and every source file carries `SPDX-License-Identifier:
EPL-2.0`.

## GraalVM native-image rules

- **JDK 25 / GraalVM 25 baseline.** The native engine binding uses the Foreign
  Function & Memory API (final since JDK 22). Native Image 25 supports it when
  the call shapes are registered: the six downcall shapes, one upcall shape and
  the reflective `IFn.invoke` it targets are in
  `desktop/resources/META-INF/native-image/.../reachability-metadata.json`.
  The build passes `--enable-native-access=ALL-UNNAMED`.
- **Build-time initialisation.** Clojure namespaces are initialised at image
  build time (graal-build-time; v1 needs the explicit `--features=` flag). So
  no top-level def may read the environment, the clock, or an RNG, or start a
  thread; those would be frozen into the binary. Executors, tokens, secrets,
  the home directory, engine discovery and the job queue are all created in
  functions at run time.
- **No reflection.** Every namespace sets `*warn-on-reflection*` right after
  its `ns` form (`architecture_test` fails otherwise), and the suite compiles
  with zero warnings; CI fails on any.
- **Arenas.** Native Image 25 supports `Arena.ofShared` only behind an expert
  option, so the native engine keeps a render's upcall stub in an automatic
  arena, reachable until `wmark_render_release` returns.
- **Charset.** An image keeps the build machine's `sun.jnu.encoding`; Linux
  builds run in `C.UTF-8` (RUNBOOK, "Native binaries").
- **Resources** are declared in the same metadata file: `public/**` (the web
  UI's `datastar.js`, its license and `app.css`) and `fonts/**`, plus `wmark/**`
  for Pro.
- **Build flags** (from the build matrix in `deps.edn`):
  - `-march=compatibility`, because the default targets x86-64-v3 and would
    crash on older laptops;
  - `--no-fallback`.

  Shutdown hooks run on Ctrl+C without a flag: GraalVM 25 installs exit
  handlers in executables by default.
- **No cross-compilation.** WSL2 produces a Linux ELF. The Windows `.exe`
  comes from a Windows machine with MSVC, or the `windows-latest` CI runner.
  Keep the repo on the WSL ext4 filesystem, not a `/mnt` or 9p mount;
  builds there are dramatically slower.
- **GraalVM status.** Oracle detached GraalVM from the Java SE release train
  in September 2025; GraalVM Community 25 continues. The hedge is that
  nothing here depends on native-image: the same uberjar runs under a
  `jlink`/`jpackage` runtime if needed.

## What's tested

86 tests with 10,592 assertions in 25 namespaces, all passing, with zero
reflection warnings (2026-09-27; JDK 25, FFmpeg 6.1.1 and a C compiler). A
browser smoke test (`test/e2e/ui_smoke.py`, 20 checks in Chromium) covers the
web UI end to end, on the JVM and against the native binary, and
`test/smoke/native.clj` checks built binaries and bundles on every OS (RUNBOOK,
"Native binaries").

| Area | What the tests cover |
|---|---|
| Kernel | SplitMix64 draw-for-draw against `java.util.SplittableRandom`; golden vectors for the PRNG, seeds, a full render spec, render spec v2 down to every bitmap, the schema's verdicts and messages, and the settings form, on the JVM and on the Dart VM (`bb kernel-dart`); the validator against malli; settings layering and provenance; half-open scheduled windows; the flip projection; capability negotiation; the TrueType reader against fontTools; text coverage, borders and colours; the warp against its homography; both exported render spec schemas equal to the code |
| Architecture | The dependency rules above |
| Profiles | The store contract on files, memory and PostgreSQL: slugs, aliases, `latest`, fallback, revisions, 8 racing editors, 40 concurrent auto-saves, damaged files, pre-revision files |
| Engine lookup | Search order (`./`, then `./bin/`, then the install folder), the hardened order, explicit files and folders, unusable files reported in the diagnostic trail, ffprobe taken from ffmpeg's folder (and a warning when it isn't) |
| FFmpeg compile | Escaping, German-locale numbers, argv per FFmpeg version, encoders and quality tiers, segment offsets, text never inside the graph, every emitted filter covered by the capability check |
| Conformance | Real renders measured against the reference semantics, for clips starting at 0 s and at 0.5 s: logo within 1.35 px on every frame, text on exactly the scheduled frames. Render spec v2 on the FFmpeg on PATH, the pinned LGPL FFmpeg and the C mock: within 1.11 px |
| Native engine | A C mock compiled by the test, driven through FFM and the unchanged job pipeline: handshake and the ABI compatibility rule (the mock built as ABI 1 and 3), probe errors, upcall progress from a native thread, cancel, capability refusal, a non-ASCII path arriving as UTF-8, still decoding, a v2 render checked frame by frame |
| Jobs | A fake engine behind the protocol: publish on success, per-input failures, an engine that reports success but writes nothing, existing outputs never overwritten, capability gaps reported before rendering, cancel mid-render; the spec version choice; a v2-only engine given host-drawn bitmaps that exist while it renders and are gone afterwards |
| HTTP | A live server: 401, 421, cookie bootstrap, 403 for a foreign Origin, CRUD, stale `if-rev`, the doctor route, the built-in UI's protection, an external UI with SPA fallback, traversal |
| Web UI | The official Datastar SDK wire-format cases; escaping of hostile names and texts; only numbers in `data-signals`; the page's CSP nonce; token and `Datastar-Request` checks; editing with revisions, live preview and validation messages; a render followed over the queue stream to "done" and the activity log |
| Core API | Jobs are tenant-scoped: list, cancel and subscribe |
| Sidecar | A server started with `--parent-pid` exits when its parent ends, including a parent that was gone before the watch began; an exiting server ends everything under it, a process that ignores SIGTERM included |
| CLI | `run --help` and `--progress`; the progress display's bar, lines and quiet modes against a fake clock; `profiles show` and `profiles effective` (values, where each came from, locked features, canary by name); canary refused on the community plan |
| FFmpeg discovery | `-filters`, `-encoders` and `-version` output from 6.1 and 9.0 builds (9.0 dropped a flag column) |
| Build | The uberjar carries every component's resources; FFmpeg pins must be https with a SHA-256; licenses through parent POMs; the SDK's ABI tag matches the header |
| Commercial editions | Tested in their own repository against this one, with the same harnesses (`testkit/`) |
