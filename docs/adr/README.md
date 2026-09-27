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

New records copy this shape: **Status**, **Context**, **Decision**,
**Consequences**, **Alternatives**, **Sources** (with the date facts were
checked), and **Owner actions** where something needs the owner.
