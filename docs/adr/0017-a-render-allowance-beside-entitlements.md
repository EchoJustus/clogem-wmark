<!-- SPDX-FileCopyrightText: 2026 The clogem-wmark authors -->
<!-- SPDX-License-Identifier: EPL-2.0 -->
# 0017. A render allowance beside entitlements, checked in the core

- **Status:** Accepted (2026-10-01), on the owner's choice: a generic,
  edition-neutral render allowance in the core rather than a check in one
  client.
- **Date:** 2026-10-01
- **Builds on:** decision 11 and the open-core boundary in `CLAUDE.md`
  ("Entitlements are checked in the core. Never check them only in a
  UI."), [ADR 0015](0015-a-job-queue-on-every-host.md) (the job queue on
  every host) and [ADR 0016](0016-the-rest-contract-in-the-core-library.md)
  (the REST contract).

## Context

- `Entitlements` answer one question: may a render use a feature? Some
  deployments need a second question answered: may a render run at all?
  - An app's free trial allows a few renders per device.
  - A hosted plan has a monthly quota.
- Each of these limits must hold in the core, like entitlements, so no
  client can go around it. That includes a GUI that talks REST to an
  engine, a script, and an app that runs the core library in its own
  process.
- A limit counted in a UI is not a limit: any other client of the same
  engine renders past it.

## Decision

1. **An optional port, `:allowance`, in the system map**, beside
   `:entitlements`. It implements `watermark.core.features/RenderAllowance`:
   - `renders-left`: renders still allowed for the caller (`ctx`), the
     running ones taken off, or `nil` for no limit;
   - `reserve-render!`: take one render's turn as it starts; truthy when
     one was left;
   - `settle-render!`: the reserved render ended. A finished render keeps
     its turn. A failed or cancelled one gives it back.

   With no `:allowance`, every render may run, as before. The community
   edition has none.
2. **Checked twice, in the core:**
   - `submit-job!` and `run-batch!` refuse at once when no render is left
     (`:render-limit`, HTTP 402), before `latest` is recorded or anything is
     queued;
   - `jobs/render-input!` reserves a turn before it plans each input. With
     none left that input fails with `:render-limit` before the engine is
     asked, and the job goes on to report it. The turn is settled when the
     render ends, whatever the outcome.

   The second check is the one that holds when jobs were queued while
   turns were left. A dry run (`plan-batch`) and a preview take no turn.
3. **Reported:** `api/features` (`GET /api/v1/features`) adds
   `:renders-left` when an allowance limits the caller, so a UI shows what
   the core counts instead of counting itself.
4. **What counts and where it is stored is the implementation's
   business.** Persistence, per-device or per-tenant counting, and what
   lifts a limit all belong to the adapter. The core only asks and reports.
5. **The JVM's desktop host** takes an optional `:allowance-fn` in its
   edition map (`watermark.app`), beside `:entitlements-fn`:
   `(fn [home opts] allowance-or-nil)`. It sees the command's options, so an
   edition can limit some runs and not others: for example only a server
   started for a GUI (`serve --parent-pid`), never a script's `run`. A host
   on the Dart VM puts `:allowance` in its system map itself.

## Consequences

- A trial or a quota is enforced the same way on the JVM and on the Dart
  VM, through REST and in-process: the gate is core library code
  (`.cljc`).
- No existing deployment changes: the port is optional, and no adapter in
  this repository implements it.
- `:render-limit` joins the REST contract's errors as a 402, beside
  `:feature-locked`.

## Alternatives

- **New methods on `Entitlements`.** Every existing implementation would
  have had to grow them, and a feature check and a render count change for
  different reasons. A separate, optional port keeps both small.
- **A check in each client.** It would be rejected for the same reason
  entitlements are checked in the core.
- **Counting at submission only.** A queued job can wait while other jobs
  use the turns up, so each render reserves its own turn as it starts.

## Tests

- JVM, a fake engine (`jobs_test`): a failed render gives its turn back, a
  finished one keeps it, a fourth input past a two-render allowance fails
  with `:render-limit` before the engine is asked, and a cancelled render
  gives its turn back.
- JVM, the Core API (`api_test`): `:renders-left` in the features report
  only for a limited caller, and `submit-job!` refused with a 402 before
  anything is queued.
- JVM, the local server (`http_test`): an edition's `:allowance-fn` sees
  the command's options, `GET /api/v1/features` reports 0 renders left,
  and `POST /api/v1/jobs` answers 402 `render-limit` with nothing queued.
  Without the hook, the system has no allowance.
- Dart VM, real FFmpeg (`dart/test`, `host_test`): a one-render allowance
  over a two-input batch gives `done`, then `failed` with
  `:render-limit`. The report then says 0, and the next batch is refused
  before it starts.
