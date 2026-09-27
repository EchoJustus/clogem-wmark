# 0007. Intel Macs stay supported, on GraalVM 25.0.1

- **Status:** Accepted (the owner, 2026-09-27)
- **Date:** 2026-09-27

## Context

GraalVM CE 25.0.2 removed macOS x64 support: "Version 25.0.1 was the last
release that supported this hardware architecture. GraalVM now only
supports macOS on AArch64 (Apple Silicon)." CI's Intel job asked for GraalVM
`25` and got 25.0.1 without saying so (run 36324745992, job
108635355201). Nothing guaranteed it would keep doing that.

## Decision

- **Intel Macs (macOS x64) stay a supported platform.** Their native builds
  are permanently pinned to **GraalVM CE 25.0.1**.
- **The pin is data.** The build matrix in `deps.edn` holds
  `:graalvm {:macos-x64 "25.0.1"}`.
  - `bb native` (`wmark.build/native`) reads `native-image --version` and
    refuses to build for a pinned platform on any other release.
  - The workflows (`ci.yml` `native`, `release.yml` `build`) give every
    matrix entry a `graalvm` field and pass it to `setup-graalvm`. The
    macos-x64 entries say `25.0.1`, and a test (`wmark.build-test`) keeps
    them in step with `deps.edn`.
- The other platforms keep taking the latest GraalVM 25 (`"25"`), so they
  get its updates.

## Consequences

- Intel Mac binaries stop receiving GraalVM and JDK updates. Security fixes
  in later 25.x releases (and in the JDK they bundle) won't reach them.
  Revisit if a fix matters for how wmark uses the JDK: TLS in license checks
  or downloads, image decoding, or the HTTP server.
- Features that need a newer GraalVM can't reach Intel Macs. The FFM API
  already isn't available there (GraalVM has no FFM on Intel macOS), so
  native engines stay Apple silicon only. Render spec v2 works on Intel Macs
  through FFmpeg.
- Local Intel builds need GraalVM CE 25.0.1 in `GRAALVM_HOME`. Its archive
  (`graalvm-community-jdk-25.0.1_macos-x64_bin.tar.gz`) is on the
  graalvm-ce-builds releases page.
- GitHub's `macos-15-intel` runners are a separate dependency. When GitHub
  retires them, Intel builds need another x64 Mac or cross-building (which
  GraalVM doesn't support).

## Sources (checked 2026-09-27)

- GraalVM CE 25.0.2 release notes: https://www.graalvm.org/release-notes/JDK_25/
- GraalVM CE 25.0.1 downloads: https://github.com/graalvm/graalvm-ce-builds/releases/tag/jdk-25.0.1
- `graalvm/setup-graalvm` (`java-version` takes an exact release):
  https://github.com/graalvm/setup-graalvm
