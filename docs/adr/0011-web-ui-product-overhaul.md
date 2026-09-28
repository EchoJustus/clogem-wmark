# 0011. The web UI as a product: design system, a settings form, live preview

- **Status:** Proposed. The owner reviews this direction before the UI is
  rebuilt (2026-09-28: "Hold off on writing the massive UI refactoring code
  until I approve the architectural direction").
- **Date:** 2026-09-28

## Context

- **The built-in UI works but reads as a debug tool** (`docs/img/ui-datastar.png`):
  - the settings are raw JSON in a textarea, next to a flat table of every
    leaf and where it came from;
  - inputs are full paths pasted one per line;
  - nothing shows what a video will look like until it has been rendered.
- **The owner's requirements (2026-09-28):**
  1. A modern visual design: a clear typographic hierarchy, generous
     whitespace, card layouts and refined interaction states, in the manner
     of high-conversion SaaS products.
  2. A settings form in the style of VS Code's settings editor, with
     click-to-edit. Nested settings are flattened into distinct inputs
     (`texts.mode`, `texts.content`), and an enum is always a dropdown,
     never typed.
  3. A file picker for the logo, clear inputs for text watermarks, and a
     **live preview**: the current settings rendered onto a frame, so
     tuning stops being blind.
- **What stays fixed:**
  - Datastar hypermedia: server-rendered HTML and signals over SSE, with no
    JavaScript of our own and no npm (decision 1);
  - the web UI invariants: HTML only through `watermark.web.html`, no user
    text inside `data-*` expressions, a CSP nonce per page, the token on
    `/` and `/ui/*`;
  - Clojure first (decision 9);
  - the UI works offline: no web fonts or CDNs.

## Decision (proposed)

### 1. A small design system, in CSS we own

**What the references have in common** (their published styles, studied
2026-09-28):
- **RedotPay:** Inter for body text and Poppins for headings, a 4 px
  spacing unit, 8 px corners, pill-shaped primary buttons, and one strong
  accent on a neutral palette.
- **TaskForge:** a warm off-white canvas (`#FAF8F4`, dark `#171511`),
  near-black text, one accent, the system UI font for text and a display
  face used sparingly, flat surfaces and dark primary buttons.
- **"FeverStudio":** not identified unambiguously (searches return Fever,
  the events app). The owner is asked for a link; the principles below
  don't depend on it.

**Principles we take from them:**
- **Tokens, not one-off values.** `web/resources/public/app.css` defines
  custom properties and every rule uses them:
  - **Colour:** a neutral canvas, surfaces, hairline borders, two text
    tones and **one accent** used only for the primary action and the
    focus ring. Semantic colours cover success, warning and danger.
  - **Themes:** light and dark from the same tokens, following
    `prefers-color-scheme`.
  - **Space:** a 4 px scale (4, 8, 12, 16, 24, 32, 48).
  - **Type:** a scale of 12, 14, 16, 20, 24 and 32 px with two weights, in
    the system UI font stack (`system-ui, -apple-system, "Segoe UI",
    Roboto, …`). A bundled web font would add bytes (ADR 0008) and depend
    on nothing we control.
  - **Shape:** 8 px corners on controls and 12 px on cards. Shadows appear
    only on floating layers (menus, dialogs).
- **Hierarchy:**
  - a page title per view;
  - card titles;
  - settings as label, description and control;
  - numbers in tabular figures.
- **Interaction states for every control:** hover, `:focus-visible` (a
  2 px accent ring), active, disabled, invalid (a message below the
  control, `aria-invalid`), and busy (`aria-busy` with a spinner). Motion
  stays at or under 150 ms and follows `prefers-reduced-motion`.
- **Accessible by construction:** WCAG AA contrast in both themes, real
  `<label>`s, keyboard order, and targets of at least 32 px.
- **No CSS framework** and no build step. The stylesheet stays one file of
  our own, ordered as tokens, base, layout, components and utilities.

### 2. Layout: an app shell with a workbench

```
┌ wmark ─────────────────────────── engine ready · Community ┐
│ Profiles  │  Settings (form)                │  Preview     │
│  ● Social │  ┌ Logo ─────────────────────┐   │  ┌────────┐ │
│    9:16   │  │ Image   [logo.png ▾] [⤴]  │   │  │ frame  │ │
│  ○ Festi- │  │ Anchor  [bottom-right ▾]  │   │  │  n     │ │
│    val    │  │ Opacity ──●────── 0.70    │   │  └────────┘ │
│  + New    │  └───────────────────────────┘   │  ◀━━●━━━━▶ │
│           │  ┌ Text layers ──────────────┐   │  time / flip │
│           │  │ …                         │   │              │
├───────────┴──────────────────────────────────┴──────────────┤
│ Queue: [ Add videos… ]   clip_a.mp4  ▓▓▓▓░░ 62%  ETA 0:41   │
└──────────────────────────────────────────────────────────────┘
```

- **The left rail** holds the profiles, with the last run marked; creating
  and renaming happen in place.
- **The centre is the settings form** (section 3). Its category headings
  double as a table of contents, and a search box filters it.
- **The preview is on the right** and stays in view while scrolling
  (section 5).
- **The queue sits at the bottom:** added videos, per-file progress,
  cancel and the outcome.
- **Narrow windows:** the preview moves above the form, and the rail
  becomes a menu.

### 3. The settings form, generated from the schema

- **One source of truth.** The form is generated on the server from the
  settings schema (`watermark.core.schema`, exported at
  `GET /api/v1/schema/settings`). A setting added to the schema appears in
  the form with no UI change.
  - The schema gains a human `title` and `description` per setting, and a
    `category`. Today it has enums, bounds and `x-tier` only.
- **Flattened rows, in the style of VS Code.** Each leaf is a row showing:
  - its title, and its path (`logo.opacity`, `texts.0.mode`) in small type;
  - a one-line description;
  - the current value;
  - where the value came from, as a badge: "set here", "from your last
    run", "built-in default" (the provenance invariant);
  - a "Reset to default" action, which removes the value from the profile
    so the lower layer shows through.
- **Click to edit.** A row shows its value as text. A click or Enter turns
  it into its control, and Enter, blur or a changed selection saves.
- **Controls from the schema type:**

  | Schema | Control |
  |---|---|
  | `enum` | a native `<select>`, never free text. Options show display names ("canary", ADR 0005). Locked Pro options stay visible with a "Pro" badge and are disabled (`x-tier: pro`). |
  | `boolean` | a switch (a styled checkbox) |
  | `number` with bounds | a number input, plus a range slider for ratios and opacity |
  | `integer` | a number input with its step and bounds |
  | colour strings | `<input type="color">` with a hex field |
  | file paths (`logo.path`) | a file button (section 4) |
  | free text (`texts.*.content`) | a text input or textarea, with a character count |
  | the `texts` vector | a card per layer ("Text layer 1 · continuous"): add, remove, reorder. The layer's `mode` select swaps its fields to that mode's variant (the schema's `oneOf`). |

- **Saving one setting at a time.**
  - An edit sends one path and its value. The server coerces the value
    with the schema, validates the whole profile and saves with the
    revision it read (`if-rev`).
  - A 409 `stale` shows "Changed elsewhere: reload", as today.
  - Errors appear next to the field, never as a toast alone.
- **How edits reach the server safely.** Values travel as signals (JSON),
  never inside a `data-*` expression. Field paths in URLs go through
  `views/path-segment`.
- **"Edit as JSON"** stays as a secondary view for power users and bulk
  paste. It is no longer the default.

### 4. Files: the logo and the videos

- **The logo: a file button and a drop zone.**
  - Datastar binds a file input to a signal: the vendored 1.0.4 reads it
    with `readAsDataURL` and sends the name, MIME type and contents, with
    no JavaScript of ours.
  - The server:
    - checks the size (at most 10 MB) and that the engine can decode it
      (`engine/StillDecoder`, the same path render spec v2 uses);
    - stores it by content under `<home>/assets/<sha-256>.<ext>`;
    - sets `logo.path`.
  - A thumbnail and the pixel size show in the row. Hosted builds store the
    upload through `MediaIO`.
- **Videos: a server-side picker for local use.**
  - Uploading gigabytes over loopback only to copy them makes no sense, and
    a browser's file input never reveals a path.
  - So "Add videos…" opens a dialog the engine renders: folders and video
    files under the user's home, with multi-select and remembered recent
    folders.
  - Pasting paths keeps working. The hosted dashboard uploads through
    presigned URLs instead (M4).
- **The text watermark:** its content, mode, anchor, size and colour are
  plain rows of the text layer's card (section 3).

### 5. Live preview: one frame through the real pipeline

**What it shows.** The current settings, saved or not, drawn onto:
- a frame of a chosen input video at a chosen time; or
- before any video is added, a built-in sample frame at a chosen aspect:
  16:9, 9:16 or 1:1.

A time scrubber moves through the clip, so the logo's flip phases and each
scheduled text are visible at the frames where they land.

**How it's made: the same path as a render, cut to one frame.** A new
`api/preview` (`(sys ctx {:settings :source :t})`):
1. resolves the settings exactly as a run does: defaults, profile and the
   unsaved edits;
2. builds the render spec, v2 when the engine needs it. The kernel draws the
   bitmaps (the logo's flip phase and the text layers) exactly as a render
   does;
3. asks the engine for frame n. FFmpeg seeks, composites with the same
   overlay graph, and writes a single PNG (`-frames:v 1`);
4. stores it in `<home>/work/previews/` under a random id, served
   token-protected at `/ui/preview/<id>.png`.

**What users see.**
- The UI patches the `<img>` element when a new frame is ready. Edits are
  debounced (300 ms), and a newer request cancels an older one.
- Layers the preview can't draw exactly say so rather than approximating
  ("keyed placement: shown with a sample seed").

**Why this and not a drawing in the browser.**
- A browser-drawn approximation would be JavaScript, which decision 1
  rules out.
- It would also be a second implementation of the reference semantics that
  could drift.
- Rendered this way, the preview is the output.

**Guarantees.**
- **Tested:** a conformance test checks that frame n of a preview equals
  frame n of a full render, pixel for pixel.
- **Engines that can't make a preview** declare it in their capabilities
  (`:preview-frame`), and the UI says so.
- **Keyed schedules stay private:** on a real input, the preview uses the
  studio's own seed on the owner's machine. On the sample frame it uses a
  fixed demonstration seed, labelled as such.
- **Cost:** about 0.2–0.5 s per frame on a laptop, dominated by starting
  FFmpeg and seeking. The decoded source frame and the drawn bitmaps are
  cached per settings hash.

### 6. How it's built and tested

- **Everything stays server-rendered**: views in `web/`, handlers going
  through `watermark.core.api` only, and the architecture tests unchanged.
- **The browser suite (`bb e2e`) grows:**
  - an enum edited through its `<select>` saves and updates the badge;
  - a logo upload shows its thumbnail;
  - a changed opacity updates the preview image;
  - a stale edit shows the reload message.
- It runs in Chromium and in WebKit, which is what the desktop window uses
  on macOS and Linux (ADR 0008).
- **Screenshots of the key views,** light and dark, are attached to each UI
  pull request for review.

## Consequences

- **The UI stops showing internals:** no JSON by default, no raw paths
  unless asked for, and no wire ids.
- **The kernel schema gains titles, descriptions and categories.** They are
  user-facing text, and it is where they belong: every client reads the
  same labels from the API.
- **The engine protocol gains an optional preview capability.** FFmpeg
  implements it first; native engines add it when they can.
- **More server round-trips per edit** (a save and a preview). Loopback
  makes them fast, and the hosted dashboard debounces the same way.
- **The Datastar-only rule holds.** Everything above is attributes,
  server-rendered fragments and signals.

## Alternatives

- **A ClojureScript single-page app** for the form and the preview:
  rejected by decision 1 (no JavaScript build) and as a second copy of the
  reference semantics.
- **A CSS framework** (Tailwind and the like): it needs a build step or a
  CDN, and a small token file does the job.
- **Previewing in the browser with a canvas:** JavaScript of our own, and
  an approximation.

## Owner actions

- Approve or amend the direction: the design principles, the layout, the
  form model and the preview design.
- Name the accent colour: keep wmark's yellow or pick another.
- Send a link for "FeverStudio".
