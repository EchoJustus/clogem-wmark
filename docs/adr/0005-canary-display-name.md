# 0005. The canary mode keeps its wire id; users see "canary"

- **Status:** Accepted (the owner, 2026-09-27)
- **Date:** 2026-09-27
- **Update 2026-09-28:** `wmark-tui` was removed (ADR 0010). The CLI's
  `profiles show` and `profiles effective` now show the display name where
  the TUI did.

## Context

The keyed canary text mode was named `subliminal` in code. CLAUDE.md says
never to market anything as "subliminal": the word has regulatory baggage in
broadcast rules on subliminal techniques, and a 1–3 frame insert is a visible
flicker, not a subliminal message. The name still reached users in three
places: `wmark run --help`, the "part of wmark Pro" error, and the settings
JSON in the web editor and the TUI.

The name is not only a label. `watermark.core.seeds` hashes the mode's name
into every keyed seed:
`HMAC-SHA256(secret, "wmark/v1|<fingerprint>|<layer>|<mode>|<text>")`.
`kernel/test/golden/seeds.edn` pins that for `subliminal`. Renaming the mode
would give every existing video a different canary schedule, and the owner
could no longer re-derive the frames that prove ownership of videos already
published.

## Decision

- **The wire id stays `subliminal`.** Settings, stored profiles, `/api/v1`,
  render specs, feature ids (`text.mode/subliminal`, which also appear in
  license payloads) and the seed input keep it.
- **Users see and type `canary`.** The feature catalog records the display
  name (`:display-name "canary"`). `watermark.core.features` derives the
  alias table from it and resolves aliases with `canonical-mode` and
  `canonical-settings`.
- **Every entry point resolves before seeding or gating:**
  `schema/decode-json`, `schema/validate!` (whose return value callers keep),
  `render/build` (before computing each seed), `modes/layer-spec` and
  `features/required-features`.
- **Every user-facing surface shows the display name:**
  - the CLI's help and errors, which show feature titles, not ids;
  - the web UI's editor and effective table (`features/display-settings`);
  - the TUI, which reads `display-name` and titles from `/api/v1/features`;
  - the JSON Schema's title for that branch;
  - the docs.

## Consequences

- Existing profiles, `latest` files and license payloads keep working
  unchanged, and no golden vector changes.
- A profile saved as `canary` is stored as `subliminal`. The API returns
  `subliminal`, and the built-in UIs show `canary`.
- Hosts without malli (ClojureDart) apply `features/canonical-settings`
  before validating against the exported JSON Schema.
- Tests pin it:
  - `watermark.core.mode-names-test`: the planner gives a `canary` layer the
    golden seed, through EDN and through JSON;
  - the CLI, HTTP, web and TUI tests: the name users see;
  - the commercial editions' schedule tests.

## Alternatives

- **Rename the wire id, with a migration.** This changes every existing
  schedule, or needs a permanent `if canary then hash "subliminal"`
  exception inside the seed derivation. That is worse than an alias at the
  edges, because the seed derivation is a cross-platform contract (golden
  vectors).
- **A second seed version (`wmark/v2|...`).** It would make old and new
  videos derive differently for no gain in evidence value.
