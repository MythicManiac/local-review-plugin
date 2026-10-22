# Marketplace listing sheet: Local Diff Review

Everything the JetBrains Marketplace upload form and plugin page ask for, in one place. Text that ships inside the plugin
(name, description, change notes, vendor, logo) is generated from files in this repo at build time; see
[../PUBLISHING.md](../PUBLISHING.md) for the process.

| Field | Value | Where it comes from |
|-------|-------|---------------------|
| Name | **Local Diff Review** (17 chars; checked unused on the Marketplace on 2026-10-14) | `gradle.properties` `pluginName` |
| Plugin ID | `com.mythic.localreview` (**permanent** after the first upload) | `plugin.xml` |
| Version | `0.2.1` | `gradle.properties` `pluginVersion` |
| Price | Free | n/a |
| License | Apache-2.0 | `LICENSE`; select "Apache-2.0" in the upload form |
| Source code URL | https://github.com/MythicManiac/local-review-plugin (**must be public before submitting**) | `gradle.properties` `pluginUrl` + upload form |
| Vendor name / website / email | Mythic / https://github.com/MythicManiac / mythicmaniac@gmail.com | `gradle.properties` `pluginVendorName/Url/Email` |
| Compatible products | All IntelliJ-based IDEs with the bundled Git plugin, 2026.2+ (`since-build` 262, no upper bound) | `build.gradle.kts` |
| Tags (pick in the form) | `Code Quality`, `VCS Integration`, `Productivity` | upload form |
| Description | `docs/marketplace/description.html` | injected into `plugin.xml` |
| Change notes | latest section of `CHANGELOG.md` | injected into `plugin.xml` |
| Logo | `src/main/resources/META-INF/pluginIcon.svg` + `pluginIcon_dark.svg` (40x40) | bundled in the plugin |
| Screenshots | `docs/marketplace/screenshots/` (1280x800, 16:10) | upload form, Media section |

Choices to double-check before submitting:

* The name is close in meaning to existing listings ("Local Review" by another vendor, "Git Local Review", "AI Diff Review").
  Names are compared for originality by reviewers; the description deliberately says what is different (review groups,
  pinned revisions, AGENTS.md workflow).
* Tag `AI` was **not** chosen: the plugin does not use AI itself, it only exports prompts.

## One-line summary (first 40+ characters of the description, shown on the plugin card)

> Review your own git changes in the IDE before you push them for team review.

## "Getting started" text (Marketplace admin page, supports HTML)

```html
<ol>
  <li>Open the <b>Local Diff Review</b> tool window (right side, or <i>Ctrl+Shift+A</i> and type "Local Diff Review").</li>
  <li>Click <b>New Review</b>, pick the base (for example <code>master</code>) and the head (your feature branch), and choose a review group.</li>
  <li>Double-click a file in the <b>Changes</b> tab to open the diff. Hover a line and click <b>+</b> to add a comment; tick the checkbox in the gutter to mark a hunk as reviewed.</li>
  <li>When done, use <b>Export Review</b> for a Markdown report, JSON and an agent prompt, or <b>Propose AGENTS.md Rules</b>.</li>
  <li>Optional: <i>Manage Review Groups</i> lets you set a git remote (and an SSH key) so a group syncs between your devices.</li>
</ol>
```

## Screenshots (captions for the Media section)

See `screenshots/README.md` for the exact set, order and captions.

## Privacy and data handling (for the form / support questions)

* No telemetry, analytics or crash reporting. No network connections are made by the plugin itself.
* The only network traffic is the `git` executable talking to a remote the user configured for a review group.
* Review data is stored locally in the folder the user chooses (default `~/.local-review`); credentials are never stored
  or read by the plugin (SSH keys are referenced by path only; `git`/`ssh` do the authentication).
* No personal data is collected, so no privacy policy is required by the approval guidelines (3.4); this statement is
  provided for transparency.

## Account declarations (done in the Marketplace UI, not in the repo)

* Accept the JetBrains Marketplace Developer Agreement and create the vendor profile.
* Declare **trader / non-trader** status (EEA consumer-protection rules; guideline 3.5). A free hobby/open-source plugin
  is usually non-trader, but this is the account holder's declaration to make.
* Provide the license (Apache-2.0) and source-code link in the upload form.
