# Architecture decision records

One short record per non-trivial decision: what we decided, why, and what it
costs. A record is **Proposed** until the owner accepts it; decisions about
licensing, credentials, purchases and security trade-offs always go to the
owner first (CLAUDE.md, "Decisions").

| # | Decision | Status |
|---|---|---|
| [0001](0001-ffmpeg-in-release-bundles.md) | Pinned GPL FFmpeg builds in the release bundles, for now | Proposed |
| [0002](0002-windows-code-signing.md) | Windows Authenticode through a hardware-backed signing service | Proposed |
| [0003](0003-macos-signing-and-library-validation.md) | Developer ID, notarization, and keeping library validation on | Proposed |
| [0004](0004-release-supply-chain.md) | Release supply chain: tags from main, a protected environment, pinned actions, checksums, Sigstore, attestations | Accepted |

New records copy this shape: **Status**, **Context**, **Decision**,
**Consequences**, **Alternatives**, **Sources** (with the date facts were
checked), and **Owner actions** where something needs the owner.
