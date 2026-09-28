# 0009. The community edition through package managers

- **Status:** Proposed. The owner's direction (2026-09-28) is to fully
  embrace package managers for the open-core edition; this record picks the
  channels, their order and what each needs.
- **Date:** 2026-09-28

## Context

- **Downloads ship unsigned** (decision 6). A browser marks each download:
  Mark-of-the-Web on Windows, the quarantine attribute on macOS. Windows
  SmartScreen or macOS Gatekeeper then stops the first launch (RUNBOOK,
  "Unsigned downloads").
- **Package managers change that and more.**
  - They download with their own tools, verify a pinned checksum, put the
    program on the `PATH`, and update it.
  - Scoop and Homebrew formulae install without the browser's marks.
  - They are also where developers and studios expect to find a CLI.
- **The release already produces what package managers consume:**
  per-platform archives, `SHA256SUMS` signed with Sigstore, and build
  provenance attestations (ADR 0004).

### Facts that shape the choice (checked 2026-09-28)

- **Homebrew:**
  - Homebrew 5.0 deprecated casks that aren't signed and notarized. They
    leave the official tap by September 2026, and `--no-quarantine` is gone.
  - That affects casks (app bundles) only. Formulae, including prebuilt
    binaries from third-party taps, are unaffected. `wmark` is a
    command-line program that serves its own UI, so it ships as a
    **formula in our own tap**, which works on macOS and Linux alike.
- **winget:**
  - It supports portable and zip installers.
  - Every submission to `winget-pkgs` goes through automated validation,
    including a malware scan, and false positives can be sent to Microsoft
    Defender for analysis.
  - Whether an unsigned portable zip passes review is confirmed by the
    first submission.
- **Scoop:** a bucket is a Git repository of JSON manifests with a URL and a
  SHA-256 each. Our own bucket needs nobody's approval.
- **Flathub:** apps whose source is available must be built entirely from
  source with no network during the build. For a GraalVM and Clojure build,
  that means vendoring every Maven dependency and a GraalVM toolchain in the
  manifest, which is real work.
- **Snap:** the Snap Store accepts prebuilt binaries. Strict confinement
  needs the `home` and `removable-media` interfaces for videos outside the
  sandbox.
- **AppImage:** one file with no installation. It needs FUSE on the host,
  or it runs with `--appimage-extract-and-run`.

## Decision (proposed)

**Tier 1, with the next release:**

| Channel | Platforms | What we publish | Who approves |
|---|---|---|---|
| GitHub Releases (today) | all | archives, `SHA256SUMS`, Sigstore bundle, attestations | us |
| **Scoop**: our bucket `scoop-wmark` | Windows | a manifest per release (URL, SHA-256, `bin`, `persist` for `wmark-data`) | us |
| **winget**: `winget-pkgs` | Windows | a portable-zip manifest per release | Microsoft's automated validation |
| **Homebrew**: our tap `homebrew-wmark` | macOS, Linux | a formula installing the prebuilt bundle | us |
| **AppImage** | Linux x64 | `wmark-<version>-x86_64.AppImage` on the release | us |
| **.deb and .rpm** | Linux x64 | packages on the release, installing to `/opt/wmark` with a symlink on the `PATH` | us |

**Tier 2, once Tier 1 is routine:**
- **Chocolatey:** its community repository, moderated and virus-scanned.
- **AUR:** a `wmark-bin` package; community-maintained packaging is fine.
- **Snap:** strict confinement with the `home` and `removable-media`
  interfaces.
- **An OCI image** on GHCR for headless batch servers (`wmark serve` or
  `run` in a container).

**Tier 3, when someone needs them:**
- **Flathub:** needs the offline source build described above. It becomes
  worth it when the desktop window (ADR 0008) makes wmark a desktop app on
  Linux.
- **nixpkgs:** for the community to maintain.

**Rules for every channel:**
1. **Every package carries the same bundle as the release:** the engine,
   the pinned LGPL FFmpeg and `licenses/`. Distribution packages don't swap
   in the system's FFmpeg: marks must render identically everywhere, and the
   conformance tests pin the build.
2. **Manifests are generated, never hand-edited.** The release workflow
   writes them from `SHA256SUMS` after the draft is published, and proposes
   them as pull requests to the bucket, the tap and `winget-pkgs`.
3. **Package managers own updates.** wmark never updates itself: an
   unsigned self-updater is exactly what an attacker wants to hijack.
4. **Nothing else changes for the direct download:** it stays documented,
   checksummed and attested.

## Consequences

- **Installing no longer involves the SmartScreen or Gatekeeper override**
  for Scoop, Homebrew and the Linux packages. winget installs may still
  meet SmartScreen, depending on the installer type.
- **Two new public repositories** (the bucket and the tap), and a bot
  identity or token that can open pull requests on them and on
  `winget-pkgs`.
- **The release workflow grows a publishing job.** It runs after the owner
  publishes the draft, so a withdrawn draft never reaches a package
  manager.
- **Linux packages install system-wide.** `wmark-data` then defaults to the
  per-user directory (the lookup already falls back to it).
- **More surfaces to keep working:** each channel gets a smoke test on a
  clean runner (install, `wmark version`, `wmark doctor`) in the publishing
  job.

## Alternatives

- **Only GitHub Releases:** what we have. It leaves every user with the OS
  override and no updates.
- **A Homebrew cask:** not possible for unsigned software in the official
  tap any more, and a cask in our own tap would still be quarantined. A
  formula fits a command-line program better anyway.
- **Signing instead** (ADRs 0002 and 0003): deferred by the owner. Package
  managers are the cheaper way to a smooth install until signing is
  revisited.

## Sources (checked 2026-09-28)

- Homebrew 5.0.0 (unsigned casks deprecated, removal from the official tap
  by September 2026): https://workbrew.com/blog/homebrew-5-0-0
- Removing `--no-quarantine` for casks: https://github.com/Homebrew/brew/issues/20755
- Homebrew's project leader, "this only affects casks, not formulae":
  https://news.ycombinator.com/item?id=45907259
- winget: supported installer formats (portable) and validation:
  https://learn.microsoft.com/en-us/windows/package-manager/winget/ and
  https://learn.microsoft.com/en-us/windows/package-manager/package/repository
- Flathub requirements (build from source, no network during the build):
  https://docs.flathub.org/docs/for-app-authors/requirements

## Owner actions

- Approve the channels and their tiers.
- Create the public repositories `scoop-wmark` and `homebrew-wmark` (the
  names are a proposal).
- Provide the credential that opens pull requests on them and on
  `winget-pkgs`, as a secret in a protected environment (credentials are
  the owner's, CLAUDE.md).
