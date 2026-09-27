# 0002. Windows Authenticode through a hardware-backed signing service

- **Status:** Rejected for now (the owner, 2026-09-27): code signing is
  deferred indefinitely, see "Owner decision" below. The rest of this record
  stays as the researched option for when it is revisited.
- **Date:** 2026-09-27

## Owner decision (2026-09-27)

The owner puts core functionality (render spec v2, the GUI spike) first and
defers procuring a certificate or signing service indefinitely. Offline
desktop users install unsigned builds with a manual override.

- The `sign-windows` job stays in `release.yml`, **frozen**: it is skipped
  because `WINDOWS_SIGNING` is unset. Don't enable, remove or extend it
  without the owner.
- Windows bundles are unsigned, and the draft release says so. They still
  carry `SHA256SUMS`, its Sigstore signature and provenance attestations
  (ADR 0004), which prove where a download came from but aren't checked by
  Windows.
- **What users meet:** SmartScreen shows "Windows protected your PC" for a
  downloaded unsigned program ("More info" → "Run anyway"; or `Unblock-File`
  on the extracted folder first). **Smart App Control blocks unsigned
  programs outright, with no per-app exception**: a user who has it on must
  turn it off in Windows Security to run wmark. Managed machines that allow
  only signed code can't run it at all. RUNBOOK.md, "Unsigned downloads",
  gives the steps.

## Context

Unsigned `.exe` files trigger SmartScreen warnings and are blocked in managed
environments. Since 1 June 2023, the CA/Browser Forum's Code Signing Baseline
Requirements (§6.2.7.4) require every publicly trusted code-signing key, OV
included, to be generated, stored and used in a hardware crypto module. A
`.pfx` file in a CI secret is therefore no longer possible; signing goes
through a token, a cloud HSM or a signing service.

The release workflow (`.github/workflows/release.yml`) already builds and
smoke-tests the Windows bundle; it signs only when the owner has configured a
signing method.

## Options

| | Azure Artifact Signing (formerly Trusted Signing) | OV certificate + cloud HSM, through jsign | SignPath Foundation (free for open source) |
|---|---|---|---|
| Cost | Basic $9.99/month (5,000 signatures), then $0.005 each | CA certificate (≈$100–$300/year) plus the CA's cloud-signing fee | Free for OSI-licensed projects |
| Who is eligible | Public trust for **organizations** in the US, Canada, EU, UK, Australia, New Zealand, Japan, South Korea, Singapore, Switzerland, Norway, Israel; **individuals only in the US and Canada** | Organizations and individuals worldwide (CA vetting) | Open-source projects that pass their review |
| Publisher shown | The validated legal entity | The validated legal entity | "SignPath Foundation", not the studio |
| CI credential | None stored: GitHub OIDC to Azure (`azure/login`) | An API credential for the CA's signing service | Their CI integration |
| Also signs the commercial editions | Yes | Yes | No (they are proprietary) |

jsign 7.5 supports the cloud services of the main CAs (SSL.com eSigner, DigiCert
ONE/KeyLocker, Certum, GaraSign, SignPath) as well as Azure Key Vault, AWS,
Google Cloud KMS and Azure's signing service.

## Decision (proposed)

- **If the studio's legal entity is in an eligible country** (a Singapore
  company, for example): **Azure Artifact Signing**, Basic tier. It is the
  cheapest option and stores no secret in GitHub; the release workflow logs in
  by OIDC and calls `azure/artifact-signing-action` v2.0.0 (pinned by SHA).
- **Otherwise** (an individual, or an entity in Hong Kong or mainland China):
  an **OV code-signing certificate from a CA with cloud key storage**, used
  through **jsign 7.5** (pinned by SHA-256 in the workflow). Choose the CA by
  its cloud-signing API and price; SSL.com eSigner and DigiCert KeyLocker are
  both jsign store types.
- Sign **every** `.exe` in the bundle (`wmark.exe`, `wmark-tui.exe`,
  `bin/ffmpeg.exe`, `bin/ffprobe.exe`) with SHA-256 and an RFC 3161 timestamp,
  so signatures outlive the certificate. The workflow then checks each file
  with `Get-AuthenticodeSignature` and fails if one lacks a valid,
  timestamped signature.
- SignPath's free program isn't recommended: the publisher name wouldn't be
  the studio's, and it can't sign the commercial editions.

## Consequences

- Signing runs only in the protected `release` environment (ADR 0004).
- SmartScreen reputation still builds up per publisher over time; a signature
  removes the "unknown publisher" warning, not every prompt.
- Azure's certificates are short-lived, which makes the timestamp essential;
  the workflow always timestamps.

## Sources (checked 2026-09-27)

- CA/Browser Forum, Code Signing Baseline Requirements, §6.2.7.4:
  https://cabforum.org/working-groups/code-signing/requirements/
- Azure Artifact Signing quickstart (eligibility):
  https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart
- Azure Artifact Signing pricing: https://azure.microsoft.com/en-us/pricing/details/artifact-signing/
- `azure/artifact-signing-action` v2.0.0: https://github.com/Azure/artifact-signing-action
- jsign 7.5 (store types, timestamping): https://ebourg.github.io/jsign/
- Smart App Control FAQ ("There is currently no way to bypass Smart App
  Control protection for individual apps"; checked 2026-09-27):
  https://support.microsoft.com/en-us/windows/security/threat-malware-protection/smart-app-control-frequently-asked-questions

## Owner actions (when signing is revisited)

1. Choose the option that fits the studio's legal entity, and procure it
   (identity validation or certificate vetting takes days).
2. Create the `release` environment (ADR 0004), then set, for Artifact Signing:
   variables `WINDOWS_SIGNING=artifact-signing`, `AZURE_CLIENT_ID`,
   `AZURE_TENANT_ID`, `ARTIFACT_SIGNING_ENDPOINT`, `ARTIFACT_SIGNING_ACCOUNT`,
   `ARTIFACT_SIGNING_PROFILE`, plus a federated credential in Entra ID for this
   repository's `release` environment. For jsign: variables
   `WINDOWS_SIGNING=jsign`, `JSIGN_STORETYPE`, `JSIGN_KEYSTORE`, `JSIGN_ALIAS`,
   `JSIGN_TSAURL` and secrets `JSIGN_STOREPASS` (and `JSIGN_CERTFILE_PEM` if the
   service needs the chain).
