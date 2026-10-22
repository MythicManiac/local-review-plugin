# Local Diff Review

JetBrains IDE plugin (built and smoke-tested on PyCharm 2026.2) for reviewing **local** git changes, such as
AI-authored branches, before they go to remote PR review. Pick two comparison points, review in the IDE's own diff viewer,
annotate code, track progress, export a report, and turn recurring findings into `AGENTS.md` rules.

* Design & decisions: [docs/DESIGN.md](docs/DESIGN.md)
* Plan & status: [docs/PLAN.md](docs/PLAN.md)
* Changes: [CHANGELOG.md](CHANGELOG.md)

## Using it

Open the **Local Diff Review** tool window (right stripe, or *Ctrl+Shift+A* → "Local Diff Review").

| Task | How |
|------|-----|
| Start a review | **New Review…**: pick base and head (branch/tag/commit). Default is PR-style: diff from the merge-base. SHAs are pinned. |
| See what changed | **Changes** tab: directory tree with A/M/D/R status, rename origins, per-file *reviewed* checkbox, progress by changed lines (`12/40 lines`), open-comment counts. Progress is computed in the background; the tree says "calculating…", or "unverified" if the reviewed commits are not on this machine. |
| Review code | Double-click a file: the IDE diff viewer opens (side-by-side or unified). Hover a line → **+** to comment; select lines first for a range (the lines the comment will be pinned to light up in orange while you hover the **+**; saved comments tint their lines), and in a unified diff a selection across removed and added lines becomes one range per side. |
| Comment types | *Change request*, *Question*, *Note*; mark **Recurring pattern** to propose an AGENTS.md rule (the rule text is optional: without it the comment shows as "needs wording"). Edit / Resolve / Reopen / **Reply** / Delete (tombstone) inline. File- and review-level comments via the toolbar. |
| Track progress | Checkbox per hunk (gutter), **Mark File as Reviewed** (diff toolbar), **Next Unreviewed File**. A mark follows its lines when the file changes and goes stale where they changed. |
| Agent pushed more commits | **Update Review**: pins the new commits. Comments stay where they were written; each is checked against the new code and, if its lines moved or changed, marked *outdated* with a suggested new place (right-click → **Accept Suggested Position**). Marks follow their lines, and show as stale where the lines changed. |
| Find things again | **Comments** tab: filter by status/kind/outdated/deleted, double-click to jump to the code, reply, restore deleted. |
| Finish | **Review Status…**: mark the review *Done* or *Archived*. Archived reviews are left out of **Open Review**, which lists them under "Archived reviews…". |
| Sync / groups | **Manage Review Groups…** (create groups, set remote, auto-sync on/off, sync now), **Sync Review Group**; the header shows the group and sync state. |
| Hand off | **Export Review…** → `report.md`, `report.json` (stable ids), `agent-prompt.md`. **Copy prompt** on a single comment. **Propose AGENTS.md Rules…** shows an editable diff before writing. |

Everything is saved the moment you press save; the header shows "✓ saved". Closing the IDE (or killing it) loses nothing, and the last
open review reopens automatically.

## Where data lives: review groups

Reviews live in **groups** under `~/.local-review/` (configurable: *Settings → Tools → Local Diff Review*). A group is **one git repo, auto-committed**
on every change, holding the reviews of as many projects as you like (`<group>/reviews/<review-id>/`). A review is a small `review.json` plus **one append-only event log per device**
(`events/<device-id>.jsonl`): every comment, reply, mark and status change is an event, and the state you see is the fold of all of them.

* **Sync between devices:** *Manage Review Groups…* → select the group → *Set Remote…* (any git remote, e.g. a private GitHub repo). It then syncs automatically (after changes,
  when you open a review, every few minutes) or on demand (*Sync Review Group*). On another device, create a group with the same remote and everything appears; no per-review setup.
* **SSH keys per group:** *Manage Review Groups… → SSH Key…* (or when creating a group) picks the private key used to reach that group's remote. Keys found in `~/.ssh` are listed, or Browse. It is stored as
  `core.sshCommand = ssh -i <key> -o IdentitiesOnly=yes -o BatchMode=yes` in that group's *own* `.git/config` (this machine only, never synced). That is the same mechanism as an `includeIf` + `core.sshCommand` setup, so different
  groups can use different identities. "Default" leaves it to your own git/ssh configuration. Keys with a passphrase must be loaded in ssh-agent (`BatchMode` fails fast instead of hanging).
* **Isolation:** create separate groups (*New Review → New group…*) for work that must not share history or a remote, e.g. different organisations.
  Each group has its own history and remote; the New Review dialog remembers the group per repository.
* **No sync conflicts:** each device only appends to its own log, so merging two devices never conflicts. In the rare cases git cannot merge by itself, `group.json` and the two repository
  files are resolved by fixed rules, and anything else aborts the merge and tells you which file, without touching your data.
* **Several IDEs on one machine** share one device id and take turns writing through a lock, so you can keep a review open in two IDEs.
* **Upgrading from 0.1.0:** when a group opens, its old reviews are imported automatically if the reviewed repository and its commits are on this machine (otherwise you are told, and it is retried later).
  The old files are left untouched. Hunk marks from 0.1.0 cannot be converted; file marks and all comments are kept. Upgrade all your devices: a 0.1.0 device does not see what 0.2.0 writes.
* **A documented format:** the layout and the rules for reading and writing it are specified in [spec/](spec/) (SPEC.md, ALGORITHMS.md, test vectors), so other tools can use the same reviews.

See [docs/DESIGN.md](docs/DESIGN.md#storage-layout).

## License and privacy

Apache License 2.0 (see [LICENSE](LICENSE)). The plugin collects no telemetry and makes no network connections of its own; the only network
traffic is `git` talking to a remote you configured for a review group.

## Publishing

See [docs/PUBLISHING.md](docs/PUBLISHING.md) (checklist and steps) and [docs/marketplace/listing.md](docs/marketplace/listing.md) (listing sheet).

## Build & run

The specification is a git submodule: clone with `git clone --recurse-submodules`, or run `git submodule update --init` in an existing clone.
Requires a JDK 21+ (the JBR bundled with PyCharm works). The `java` on `PATH` may be older, so point `JAVA_HOME` at it:

```
set JAVA_HOME=C:\Program Files\JetBrains\PyCharm 2025.1.1.1\jbr
gradlew.bat test                                  # spec vectors + example, unit tests, headless IDE tests
gradlew.bat runIde -PdemoProject=<path-to-repo>   # sandbox IDE with the plugin, opening that project
gradlew.bat buildPlugin                           # distributable zip in build/distributions
```

The IDE built against is `ideLocalPath` in `gradle.properties` (default: the local PyCharm install, build 262).
