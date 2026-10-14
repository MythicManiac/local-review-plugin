# Publishing Local Diff Review to the JetBrains Marketplace

State of the release preparation is tracked here (check boxes) so it survives between sessions. Listing content lives in
[marketplace/listing.md](marketplace/listing.md).

## Status

- [x] Original name chosen and checked against the Marketplace (**Local Diff Review**)
- [x] Plugin ID fixed: `com.mythic.localreview` (cannot change after first upload)
- [x] License: Apache-2.0 (`LICENSE`)
- [x] Logo: `pluginIcon.svg` / `pluginIcon_dark.svg` (40x40, light and dark)
- [x] Description (`docs/marketplace/description.html`) and change notes (`CHANGELOG.md`) are build inputs
- [x] Build wiring: signing, publishing, Plugin Verifier, readiness gate (`./gradlew marketplaceReadiness`)
- [x] Plugin Verifier: compatible with PyCharm 2026.2.3 and IntelliJ IDEA 2026.2.3 (262), no deprecated/experimental/internal API usage
- [x] Signing pipeline tested with a throwaway key (`signPlugin` + `verifyPluginSignature`)
- [x] Six screenshots, 1280x800, in `docs/marketplace/screenshots/` (order and captions in its README)
- [x] Vendor (Mythic, mythicmaniac@gmail.com, https://github.com/MythicManiac) and source URL set in `gradle.properties`; `marketplaceReadiness` passes
- [ ] **GitHub repo `MythicManiac/local-review-plugin` made public and current `master` pushed** (it exists but is private, so the plugin page links would 404; it is also behind this branch)
- [ ] Author signing key (optional, see "Signing" below)
- [ ] Marketplace account: vendor profile, Developer Agreement, trader/non-trader declaration
- [ ] First version uploaded manually and approved
- [x] Marketplace API token exists (kept outside the repo; accepted by the upload endpoint, see "Later releases"). It cannot create the first listing.

`./gradlew marketplaceReadiness` fails with the list of what is still missing; `signPlugin` and `publishPlugin`
depend on it, so an incomplete listing cannot be published by accident.

## 1. Identity (done)

`gradle.properties` carries the vendor name, email, website and the public source URL (`pluginUrl`, used for the links in the
description). Before submitting, the source repository must be **public** (Apache-2.0 requires a source link and all links on the
plugin page must be reachable). Optionally add a `NOTICE` file with your copyright line.

## 2. Signing (optional)

The Marketplace signs every upload itself; signing with your own certificate chain is an additional author signature
(without it the IDE may show an extra warning when installing from a file). The Marketplace zip signer only accepts **RSA, ECDSA
(SHA-384) or DSA** keys in **PEM** format. It does **not** support Ed25519 or OpenSSH-format keys, so an existing git/SSH key such as
`~/.ssh/*_ed25519` cannot be used directly; no signing key is configured at the moment. To add one, generate a dedicated key
(never commit these files):

```
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

and expose them to the build as environment variables (`signPlugin` is skipped when they are absent):

```
set CERTIFICATE_CHAIN=<contents of chain.crt>
set PRIVATE_KEY=<contents of private.pem>
set PRIVATE_KEY_PASSWORD=<password>      (only if the key is encrypted)
```

## 3. Build and verify

Use a JDK 21+ (PyCharm's JBR works) as `JAVA_HOME`.

```
gradlew clean test                       # 53+ tests must pass
gradlew marketplaceReadiness             # metadata/screenshots/changelog checks
gradlew verifyPluginStructure verifyPlugin
gradlew verifyPlugin -PverifyIdea=2026.2.3     # also IntelliJ IDEA (downloads ~1.6 GB)
gradlew buildPlugin                      # build/distributions/local-diff-review-<version>.zip (signPlugin adds a -signed.zip when keys are set)
```

The verifier must report **Compatible** with no "scheduled for removal" or internal API usage (approval guideline 2.1).

## 4. First upload (manual)

The first version must be uploaded through the website, which also creates the plugin page.

1. Sign in at <https://plugins.jetbrains.com>, user menu -> **Upload plugin**.
2. Pick/create the **vendor profile** (accept the Developer Agreement; make the trader/non-trader declaration).
3. Upload `build/distributions/local-diff-review-0.1.0.zip` (or the `-signed.zip` if you set up signing).
4. License: **Apache-2.0**, paste the source URL. Tags: `Code Quality`, `VCS Integration`, `Productivity`.
5. After upload: **Media** -> add the screenshots in `docs/marketplace/screenshots/` (order and captions in its README);
   **Getting started** -> paste the text from `marketplace/listing.md`.
6. Submit. Review is manual and can take a few working days; write to marketplace@jetbrains.com if nothing arrives after 3-4.

## 5. Later releases

1. Add notes under `## [Unreleased]` in `CHANGELOG.md` while developing.
2. Release: set `pluginVersion` in `gradle.properties`, run `gradlew patchChangelog` (moves Unreleased into the version section).
3. Re-run step 3. Then either upload the zip on the plugin page, or automate with `gradlew publishPlugin`, which uses the
   Marketplace upload API (`POST /api/updates/upload`, which can only **update an existing plugin**; the first version must be uploaded
   manually). It reads the permanent token from the `PUBLISH_TOKEN` environment variable, e.g. in PowerShell:
   `$env:PUBLISH_TOKEN = (Get-Content C:\Users\Admin\Projects\Mythic\jetbrains-marketplace-token.txt -Raw).Trim()`.
   Keep the token file outside the repo. Use `-PpublishChannel=eap` for pre-release channels. Every update is reviewed again.
4. Keep `since-build` at the oldest IDE build you actually verify; extend verification with `-PverifyIdea=<version>`
   (or `-PverifyRecommended`) when you widen it.

## Rules this build enforces (approval guidelines v1.3)

| Rule | Enforced by |
|------|-------------|
| Original name, <= 30 chars, no "Plugin"/"IntelliJ"/"JetBrains"/product names | `marketplaceReadiness` |
| Vendor website + valid email | `marketplaceReadiness`, `verifyPluginStructure` |
| English description with a summary in the first 40 characters; change notes present | `docs/marketplace/description.html`, `CHANGELOG.md` check |
| 40x40 SVG logo, not the template default, light + dark | `marketplaceReadiness` |
| License + source link for open source | `LICENSE`, `pluginUrl` |
| Binary compatibility via Plugin Verifier | `verifyPlugin` |
| No telemetry without consent | none collected (see listing sheet) |

## Not done / needs the publisher

* Vendor details, source URL, signing key, Marketplace account declarations and the actual upload are account-bound and cannot be done from this repo.
* Verified: PyCharm 2026.2.3 and IntelliJ IDEA 2026.2.3 (both build 262). Other IDE products and older IDE versions are not claimed.
