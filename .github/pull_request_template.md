## What and why

<!-- What changes, and why. Link the issue, and the ADR (docs/adr/) for a non-trivial decision. -->

## Definition of done

- [ ] `bb test` is green and its output has no `Reflection warning`
- [ ] `bb lint` is green
- [ ] UI change: `bb e2e` is green (otherwise: not a UI change)
- [ ] Rendering change: the conformance tests are green; golden vectors changed only on purpose, and their diff is reviewed (otherwise: not a rendering change)
- [ ] Docs updated (RUNBOOK, ARCHITECTURE, ENGINE, FFMPEG_STRATEGY, ROADMAP); `CLAUDE.md` too if a decision changed
- [ ] No invariant in `CLAUDE.md` was loosened to make a test pass
- [ ] New files start with the SPDX lines (`EPL-2.0`), and every commit is signed off (`git commit -s`)
- [ ] New dependency: the reason is written below (license, native-image cost, size)

## Evidence

<!-- What you ran and what came out: commands, test and assertion counts, versions.
     List anything that remains unverified. -->
