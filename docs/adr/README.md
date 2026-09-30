# Architecture decision records

One short record per non-trivial decision: what we decided, why, and what it
costs. A record is **Proposed** until the owner accepts it; decisions about
licensing, credentials, purchases and security trade-offs always go to the
owner first (CLAUDE.md, "Decisions").

| # | Decision | Status |
|---|---|---|
| [0001](0001-ffmpeg-in-release-bundles.md) | LGPL FFmpeg builds in the release bundles (macOS built from the signed source) | Accepted |
| [0002](0002-windows-code-signing.md) | Windows Authenticode through a hardware-backed signing service | Rejected for now: signing deferred |
| [0003](0003-macos-signing-and-library-validation.md) | Developer ID, notarization, and keeping library validation on | Rejected for now: signing deferred |
| [0004](0004-release-supply-chain.md) | Release supply chain: tags from main, a protected environment, pinned actions, checksums, Sigstore, attestations | Accepted |
| [0005](0005-canary-display-name.md) | The canary mode keeps its wire id `subliminal`; users see "canary" | Accepted |
| [0006](0006-render-spec-v2-host-rendered-overlays.md) | Render spec v2: the host renders, engines only composite | Accepted |
| [0007](0007-intel-macs-on-graalvm-25-0-1.md) | Intel Macs stay supported, on GraalVM 25.0.1 | Accepted |
| [0008](0008-desktop-architecture-and-binary-size.md) | Hexagonal architecture: a `.cljc` core library for GraalVM and the Dart VM; the open core on GraalVM with the Datastar UI in a webview window; ClojureDart GUI apps, sidecar then in-process; a size diet; Clojure first | Accepted |
| [0009](0009-community-distribution-through-package-managers.md) | The community edition through package managers: Scoop, winget, a Homebrew tap, AppImage, .deb/.rpm first | Proposed |
| [0010](0010-remove-wmark-tui.md) | Remove `wmark-tui`; the CLI takes over its useful parts and gains a progress bar | Accepted |
| [0011](0011-web-ui-product-overhaul.md) | The web UI as a product: design system with light and dark themes, a schema-generated settings form, live preview | Accepted (first cut built) |
| [0012](0012-the-kernel-on-the-dart-vm.md) | The kernel on the Dart VM: a ClojureDart harness, host primitives, one validator for both runtimes | Accepted |
| [0013](0013-host-logic-into-the-core-library.md) | The host's pure logic moves into the core library (M3c): FFmpeg plans and parsers first | Accepted (in steps) |
| [0014](0014-the-dart-host.md) | The Dart host: adapters over `dart:io`, a Dart CLI, and the logic they share (M3d) | Accepted (in steps) |
| [0015](0015-a-job-queue-on-every-host.md) | One in-process job queue in the core library, for every host, with a contract | Accepted |

New records copy this shape: **Status**, **Context**, **Decision**,
**Consequences**, **Alternatives**, **Sources** (with the date facts were
checked), and **Owner actions** where something needs the owner.
