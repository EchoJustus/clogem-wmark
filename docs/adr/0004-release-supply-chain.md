# 0004. Release supply chain

- **Status:** Accepted (engineering practice; the owner creates the environment)
- **Date:** 2026-09-27

## Decision

Releases are built only by `.github/workflows/release.yml` (bundles) and
`sdk.yml` (the engine SDK), from tags, and published only by the owner:

1. **Tags from `main` only.** A guard job fails unless the tagged commit is an
   ancestor of `origin/main`.
2. **A protected `release` environment** holds every signing credential. It
   requires a reviewer's approval for each run and accepts deployments only
   from `v*` tags. No other job can read those secrets.
3. **Least privilege.** Workflows start from `permissions: {}`; build jobs read
   contents only; only the publish job may write contents, request an OIDC
   token and write attestations.
4. **Every third-party action is pinned by commit SHA**, with its version in a
   comment; Dependabot proposes updates. Downloaded tools are pinned by SHA-256
   (FFmpeg, its license text, jsign).
5. **Verifiable artifacts:** `SHA256SUMS` over every archive; a keyless
   Sigstore signature of `SHA256SUMS` (`cosign sign-blob --bundle`), tied to this
   workflow's identity; and a GitHub build-provenance attestation for every
   archive (`gh attestation verify`).
6. **Drafts only.** The workflow creates a draft release whose notes say which
   bundles are signed; the owner reviews and publishes it.
7. **Smoke-tested before signing:** each platform's binaries and assembled
   bundle pass `test/smoke/native.clj`, and the Linux binary passes the browser
   suite, before anything is signed or uploaded.

## Consequences

- Nothing ships without a human approving the signing run and publishing the
  draft.
- A compromised dependency action can't read signing secrets: the only jobs in
  the `release` environment use first-party actions from Microsoft and GitHub,
  pinned by SHA.

## Sources (checked 2026-09-27)

- Security hardening for GitHub Actions (pinning, permissions, environments):
  https://docs.github.com/en/actions/reference/security/secure-use
- Artifact attestations: https://docs.github.com/en/actions/concepts/security/artifact-attestations
- Sigstore cosign `sign-blob`: https://docs.sigstore.dev/cosign/signing/signing_with_blobs/

## Owner actions

Create the `release` environment in the repository settings: a required
reviewer (the owner), "Prevent self-review" off if the owner is the only
maintainer, and a deployment tag rule `v*` (plus `abi-v*` and `kernel-v*` if
the SDK workflow should use it later).
