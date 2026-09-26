# Contributing to clogem-wmark

Thank you for helping. A few rules keep the project healthy and its license
clear.

## License of contributions

clogem-wmark is licensed under the [Eclipse Public License 2.0](LICENSE). By
contributing, you agree that your contribution is licensed under the EPL-2.0
too.

Every commit must be signed off under the
[Developer Certificate of Origin 1.1](https://developercertificate.org/): add
a `Signed-off-by:` line with your real name, which `git commit -s` does for
you. The sign-off certifies that you wrote the change or otherwise have the
right to submit it under the project's license.

New source files start with the two SPDX lines every file here carries (the
architecture test checks them):

```clojure
;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
```

## Before you open a pull request

- `bb test` is green, with no `Reflection warning` in the output.
- `bb lint` is green.
- For UI changes, `bb e2e` is green; for rendering changes, the conformance
  tests are green.
- The relevant document in `docs/` is updated.

`CLAUDE.md` lists the invariants the tests enforce (kernel portability,
engine-neutral render specs, escaping in the web UI, the loopback server's
guards). A change that needs one of them loosened is a design discussion:
open an issue first.

## What doesn't belong here

- No Node.js, npm or JavaScript build step, and no Datastar Pro (its license
  forbids use in open-source projects).
- No secrets, keys or credentials, not even for tests: tests generate their
  own.
- wmark also has commercial editions, developed separately. They build on this
  repository and are never merged into it. This repository never names or
  requires their code.

## Reporting security issues

Please don't open a public issue for a vulnerability. Use GitHub's private
vulnerability reporting on this repository (Security → Report a
vulnerability).
