# 0003. Developer ID, notarization, and keeping library validation on

- **Status:** Rejected for now (the owner, 2026-09-27): Developer ID signing
  and notarization are deferred indefinitely, see "Owner decision" below.
  Library validation (point 3) is moot until builds are signed. The rest of
  this record stays as the researched option for when it is revisited.
- **Date:** 2026-09-27

## Owner decision (2026-09-27)

The owner puts core functionality first and defers enrolling a Developer ID
indefinitely. Offline desktop users open unsigned builds with a manual
override.

- The `sign-macos` job stays in `release.yml`, **frozen**: it is skipped
  because `MACOS_SIGNING` is unset. Don't enable, remove or extend it without
  the owner. The CI step that runs the smoke test under an ad-hoc
  hardened-runtime signature stays: it keeps the evidence for point 3 fresh.
- macOS bundles are neither Developer ID-signed nor notarized, and the draft
  release says so. Checksums, their Sigstore signature and attestations
  (ADR 0004) still prove provenance, but Gatekeeper doesn't check them.
- **What users meet:** Gatekeeper refuses the first launch of each
  downloaded program. Apple's documented override is System Settings →
  Privacy & Security → "Open Anyway", after one attempt to open it. wmark
  starts `bin/ffmpeg` and `bin/ffprobe` itself, so those may need it too;
  clearing the quarantine attribute of the whole extracted folder covers all
  four Mach-O files at once. Neither path has been tried on a real quarantined
  download yet. RUNBOOK.md, "Unsigned downloads", gives the steps.

## Context

Gatekeeper blocks downloaded, unsigned or unnotarized executables. The macOS
bundles (arm64 and Intel) contain four Mach-O files: `wmark`, `wmark-tui`,
`bin/ffmpeg` and `bin/ffprobe`. Notarization requires the hardened runtime
on every executable. The hardened runtime turns on **library validation**:
the process may load only libraries signed by Apple or by its own team. That
collides with `--engine native --native-lib X.dylib`, which loads a native
engine library, possibly a third party's.

## Decision (proposed)

1. **Developer ID Application certificate** from the Apple Developer Program
   (open to individuals and organizations worldwide; an organization needs a
   D-U-N-S number). Sign every Mach-O, FFmpeg's included, with
   `codesign --force --options runtime --timestamp`, then verify each with
   `codesign --verify --strict`.
2. **Notarize with `notarytool`** and an App Store Connect API key (no Apple ID
   password in CI). A ticket can't be stapled to a bare executable or a zip,
   so the zip relies on Gatekeeper's online check at first launch. A stapled
   `.dmg` holding the same folder works offline too; it is a follow-up once
   signing runs.
3. **Keep library validation on.** Do not add the
   `com.apple.security.cs.disable-library-validation` entitlement.
   - First-party native engines (the AVFoundation engine) ship inside the
     bundle, signed with the studio's Team ID, and load normally.
   - Third-party engine developers work against a build from source or the JVM
     (`bb dev --engine native --native-lib ...`), which has no hardened runtime.
   - Rationale: wmark's library search includes the working folder
     (`native/README.md`). With validation off, a malicious
     `libwmark_engine.dylib` planted in a Downloads folder would load into a
     notarized, trusted process. Validation turns that planting into a refusal.
4. **No JIT entitlements.** A GraalVM native image is ahead-of-time compiled,
   and its FFM upcall stubs are generated at build time from the reachability
   metadata, so it needs neither `allow-jit` nor
   `allow-unsigned-executable-memory`.

## Consequences

- An Intel Mac bundle is built too (`macos-15-intel` runners exist), but
  GraalVM supports FFM only on macOS AArch64, so native engines don't work
  there at all; FFmpeg rendering does.
- Third-party native engines can't run in the signed macOS binary unless the
  studio signs them. If a marketplace of engines ever matters, revisit with
  a separately entitled "developer" build, never by weakening the release.
- **Evidence for point 4 (CI, 2026-09-27, macOS 15 arm64):** the bundle signed
  ad hoc with the hardened runtime (`flags=0x10002(adhoc,runtime)`) passes all
  17 smoke checks, the render through the C mock with its FFM upcalls
  included. No JIT entitlement is needed.
- **Point 3 is not yet shown:** that run also loaded the *unsigned* mock
  library, but an ad-hoc signature carries no Team ID, so library validation
  doesn't apply the way it will under a Developer ID signature. The first
  signed build must check that an unsigned `--native-lib` is refused and that
  one signed with the studio's Team ID loads.

## Sources (checked 2026-09-27)

- Notarizing macOS software before distribution:
  https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution
- Hardened runtime and library validation:
  https://developer.apple.com/documentation/security/hardened-runtime
- `com.apple.security.cs.disable-library-validation`:
  https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.cs.disable-library-validation
- GraalVM 25, FFM API in Native Image (supported platforms):
  https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/
- Safely open apps on your Mac ("Open Anyway"; checked 2026-09-27):
  https://support.apple.com/en-us/102445

## Owner actions (when signing is revisited)

1. Enroll in the Apple Developer Program (if not already) and create a
   **Developer ID Application** certificate and an **App Store Connect API
   key** (Developer role).
2. In the `release` environment set variables `MACOS_SIGNING=developer-id`,
   `MACOS_SIGNING_IDENTITY` (e.g. `Developer ID Application: Studio (TEAMID)`),
   `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`, and secrets
   `MACOS_DEVELOPER_ID_P12_BASE64`, `MACOS_DEVELOPER_ID_P12_PASSWORD`,
   `APP_STORE_CONNECT_API_KEY_P8`.
3. Accept or reject point 3 (library validation stays on).
