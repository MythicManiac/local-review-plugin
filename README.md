# Local Review

JetBrains IDE plugin (built and smoke-tested on PyCharm 2026.2) for reviewing **local** git changes, such as
AI-authored branches, before they go to remote PR review. Pick two comparison points, review in the IDE's own diff viewer,
annotate code, track progress, export a report, and turn recurring findings into `AGENTS.md` rules.

* Design & decisions: [docs/DESIGN.md](docs/DESIGN.md)
* Plan & status: [docs/PLAN.md](docs/PLAN.md)
* Changes: [CHANGELOG.md](CHANGELOG.md)

## Using it

Open the **Local Review** tool window (right stripe, or *Ctrl+Shift+A* → "Local Review").

| Task | How |
|------|-----|
| Start a review | **New Review…**: pick base and head (branch/tag/commit). Default is PR-style: diff from the merge-base. SHAs are pinned. |
| See what changed | **Changes** tab: directory tree with A/M/D/R status, rename origins, per-file *reviewed* checkbox, hunk progress (`2/6 hunks`), open-comment counts. |
| Review code | Double-click a file: the IDE diff viewer opens (side-by-side or unified). Hover a line → **+** to comment (select lines first for a range). |
| Comment types | *Change request*, *Question*, *Note*; mark **Recurring pattern** to propose an AGENTS.md rule. Edit / Resolve / Reopen / Delete (tombstone) inline. File- and review-level comments via the toolbar. |
| Track progress | Checkbox per hunk (gutter), **Mark File as Reviewed** (diff toolbar), **Next Unreviewed File**. Marks invalidate themselves when the code changes ("changed since viewed"). |
| Agent pushed more commits | **Update Review**: re-pins refs, re-anchors comments (unmatched ones become *outdated*), keeps hunk marks whose content is unchanged. |
| Find things again | **Comments** tab: filter by status/kind/outdated/deleted, double-click to jump to the code, restore deleted. |
| Sync / groups | **Manage Review Groups…** (create groups, set remote, auto-sync on/off, sync now), **Sync Review Group**; the header shows the group and sync state. |
| Hand off | **Export Review…** → `report.md`, `report.json` (stable ids), `agent-prompt.md`. **Copy prompt** on a single comment. **Propose AGENTS.md Rules…** shows an editable diff before writing. |

Everything is saved the moment you press save; the header shows "✓ saved". Closing the IDE (or killing it) loses nothing, and the last
open review reopens automatically.

## Where data lives: review groups

Reviews live in **groups** under `~/.local-review/` (configurable: *Settings → Tools → Local Review*). A group is **one git repo of plain JSON files, auto-committed**
on every change, holding the reviews of as many projects as you like (`<group>/<project>/<review>/`).

* **Sync between devices:** *Manage Review Groups…* → select the group → *Set Remote…* (any git remote, e.g. a private GitHub repo). It then syncs automatically (after changes,
  when you open a review, every few minutes) or on demand (*Sync Review Group*). On another device, create a group with the same remote and everything appears; no per-review setup.
* **SSH keys per group:** *Manage Review Groups… → SSH Key…* (or when creating a group) picks the private key used to reach that group's remote. Keys found in `~/.ssh` are listed, or Browse. It is stored as
  `core.sshCommand = ssh -i <key> -o IdentitiesOnly=yes -o BatchMode=yes` in that group's *own* `.git/config` (this machine only, never synced). That is the same mechanism as an `includeIf` + `core.sshCommand` setup, so different
  groups can use different identities. "Default" leaves it to your own git/ssh configuration. Keys with a passphrase must be loaded in ssh-agent (`BatchMode` fails fast instead of hanging).
* **Isolation:** create separate groups (*New Review → New group…*) for work that must not share history or a remote, e.g. different organisations.
  Each group has its own history and remote; the New Review dialog remembers the group per project.
* **Conflicts:** per-comment files make them rare; if the same file was edited on two devices the newer edit wins and the other version is kept as `*.conflict-*` (you get a notification).
* Upgrading: reviews from the previous one-repo-per-review layout are imported into the `default` group automatically (old histories are kept under `.legacy/`).

See [docs/DESIGN.md](docs/DESIGN.md#storage-layout).

## Build & run

Requires a JDK 21+ (the JBR bundled with PyCharm works). The `java` on `PATH` may be older, so point `JAVA_HOME` at it:

```
set JAVA_HOME=C:\Program Files\JetBrains\PyCharm 2025.1.1.1\jbr
gradlew.bat test                                  # unit + headless IDE tests
gradlew.bat runIde -PdemoProject=<path-to-repo>   # sandbox IDE with the plugin, opening that project
gradlew.bat buildPlugin                           # distributable zip in build/distributions
```

The IDE built against is `ideLocalPath` in `gradle.properties` (default: the local PyCharm install, build 262).
