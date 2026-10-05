<!-- SPDX-FileCopyrightText: 2026 The clogem-wmark authors -->
<!-- SPDX-License-Identifier: EPL-2.0 -->
# 0018. "Timing", and random positions among chosen spots

- **Status:** Accepted (2026-10-05), on the owner's review of the settings
  form.
- **Date:** 2026-10-05
- **Builds on:** the open-core boundary in `CLAUDE.md` (the *shapes* of
  commercial modes live in the core's schema, their implementations
  elsewhere) and the render spec's `:per-window` placement
  (`docs/ENGINE.md`).

## Context

- A text layer's `mode` field was titled **Kind**. Its choices
  (continuous, scheduled, canary, random) say *when* a layer shows, so
  the word was ambiguous next to the layer's position.
- The canary and random kinds show text in separate showings. Without a
  fixed `anchor` they move between showings to a keyed place anywhere in
  the frame. The form offered no way to keep them to a few spots, and it
  showed such a layer's unset position as "Bottom left (default)", which
  the engine never used.

## Decision

1. **`mode` is titled "Timing"** in the form catalog
   (`watermark.core.form/layer-fields`). The wire values don't change.
2. **An optional `anchors` field on the canary (`subliminal`) and random
   layer shapes:** a vector of one to nine anchors
   (`watermark.core.schema/Anchors`), the spots a layer without a fixed
   `anchor` jumps among, each showing at one of them. Neither field set:
   anywhere, as before. A fixed `anchor` wins over `anchors`.
3. **The form shows it as a set:** a new row kind, `:anchor-set`, whose
   value lists the chosen spots in the grid's order. Typed input
   (`top-right, top-left`) or a JSON list both parse, each spot once; an
   unknown spot is an `:invalid` error; nothing chosen unsets it. For a
   moving kind, the `anchor` row has no default and reads "Random" while
   it is unset. The web UI edits the set as text (its placeholder names
   the spots); richer clients draw a grid.
4. **No change to the render spec.** An implementation turns the chosen
   spots into a `:per-window` placement: each showing a window, each
   window a point at the anchor's fractions, inset from the edges by the
   keyed scatter's own margin (5 %). Engines draw it as they draw any
   per-window placement.

## Consequences

- `native/settings.schema.json` gains the two `anchors` arrays, and the
  form's golden vector (`form.edn`) the new row and title; both runtimes
  match it (`bb kernel-dart`).
- Existing profiles mean what they meant: `anchors` is optional, and a
  layer without it moves anywhere as before.
- Clients that render the form generically show the new row as a text
  field with the spots' names.

## Alternatives

- **`:random` as a value of `anchor`.** It would have given the logo and
  the still kinds a choice they can't honour, and still left nowhere to
  list the spots.
- **A new placement type in the render spec.** Not needed: `:per-window`
  already places each showing on its own point, so no engine changes.

## Sources

- `kernel/src/watermark/render.cljc`, `placement-fractions` (checked
  2026-10-05).
