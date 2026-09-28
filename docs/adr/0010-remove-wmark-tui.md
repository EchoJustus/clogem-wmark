# 0010. Remove `wmark-tui`; the CLI takes over what it did well

- **Status:** Accepted (owner, 2026-09-28): the owner evaluated `wmark-tui`,
  found it clunky and not worth its size, and asked for it to be removed with
  anything valuable consolidated into `wmark`.
- **Date:** 2026-09-28

## Context

- **What it was.** `wmark-tui` was a line-mode shell over the REST API of a
  running `wmark serve`, 159 lines of Clojure. It could:
  - list, show, create, rename, copy and delete profiles;
  - show a profile's effective settings, with where each came from.
- **What it cost.** As a native image it weighed **57.0 MB**, next to
  63.0 MB for the whole engine (measured on Linux, ADR 0008), because every
  native image carries a Clojure runtime. It also meant:
  - a second binary to build on every OS in CI and in each release;
  - a second target in the build matrix;
  - a second thing for users to understand.
- **What it didn't have.** No progress bar: jobs were started from the web UI
  or `wmark run`, never from the TUI.
- **What `wmark` already had.** `wmark profiles list | show | save | rename
  | copy | delete`, working directly on the data folder with no server
  running.

## Decision

1. **Remove `wmark-tui` from the main branch:**
   - `tui/` and its tests;
   - the `:tui` alias and build target, and the target in the
     `desktop-server` bundle;
   - `bb tui`;
   - the native builds in `ci.yml` and `release.yml`;
   - the smoke-test checks;
   - its mentions in the docs.
2. **Move what it did well into `wmark`:**
   - **`wmark profiles effective [NAME | --clean]`** prints every setting a
     run would use, its value and where it came from ("from profile …",
     "from your last run", "set here", "built-in default"), then any feature
     the plan doesn't include, by title. This is what the TUI's `effective`
     showed, in the words the web UI uses.
   - **`wmark profiles show NAME`** shows text modes by their display names
     ("canary", ADR 0005), as the TUI did. Before, it printed the wire id.
3. **Add what users expected from it: progress in the terminal.**
   - `wmark run` draws one line per file, redrawn in place: a bar, the
     percentage, FFmpeg's speed and an ETA. It ends with the outcome and the
     time taken.
   - On an interactive terminal this is the default (`--progress auto`).
     Logs, CI and piped output keep a line per 10% (`--progress lines`), and
     `--progress none` prints only each file's start and outcome.
   - The bar is ASCII only, so every console and code page shows the same
     thing.
4. **Where the code lives now.** The last commit that has `tui/` is
   `1cb2cb7`, tagged `v0.1.0-rc.1`:
   - `git checkout v0.1.0-rc.1 -- tui/` restores it.
   - The owner asked for an `archive/tui` tag as well. This session's git
     access can push branches only, and refused the tag (HTTP 403), so that
     tag is an owner action below.

## Consequences

- **Every download is 57 MB lighter unpacked,** and each CI and release
  run builds one native image per OS instead of two (about 2–4 minutes
  each).
- **One binary per bundle:** `wmark`, plus FFmpeg. The web UI, the CLI and
  the REST API are the ways in.
- **Talking to a running server from a terminal** now goes through the REST
  API (`curl` with the token from `<home>/runtime/server.edn`, which the
  server still writes) or the web UI. The CLI works on the data folder
  directly, so for local use nothing is lost.
- **Tests:**
  - the CLI's new commands have unit and integration tests
    (`watermark.cli.progress-test`, `watermark.app-test`);
  - the native smoke test checks `profiles save`, `profiles effective` and
    the progress bar in the built binary on every OS, where it used to check
    the TUI.

## Alternatives

- **Keep the TUI and make it smaller.** Not possible in a meaningful way:
  the runtime every native image carries, not its 159 lines, is what made
  it 57 MB.
- **A full-screen TUI with a terminal library.** More code and a
  dependency, for a client the web UI and the CLI already cover.
- **Ship the TUI as a mode of `wmark`** (`wmark shell`). It would add no
  bytes, but it would duplicate `wmark profiles` for no user need we know
  of.

## Owner actions

- Optional: create the `archive/tui` tag, for example from a clone:
  `git tag -a archive/tui 1cb2cb7 -m "wmark-tui before its removal (ADR 0010)" && git push origin archive/tui`.
  The same commit is already tagged `v0.1.0-rc.1`.
