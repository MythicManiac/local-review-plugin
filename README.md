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
| Hand off | **Export Review…** → `report.md`, `report.json` (stable ids), `agent-prompt.md`. **Copy prompt** on a single comment. **Propose AGENTS.md Rules…** shows an editable diff before writing. |

Everything is saved the moment you press save; the header shows "✓ saved". Closing the IDE (or killing it) loses nothing, and the last
open review reopens automatically.

## Where data lives

`~/.local-review/<repo>/<review-id>/` (configurable: *Settings → Tools → Local Review*). Each review folder is a **git repo of plain JSON files, auto-committed**
on every change; add a remote or use any file sync to move it between devices. See [docs/DESIGN.md](docs/DESIGN.md#storage-layout).

## Build & run

Requires a JDK 21+ (the JBR bundled with PyCharm works). The `java` on `PATH` may be older, so point `JAVA_HOME` at it:

```
set JAVA_HOME=C:\Program Files\JetBrains\PyCharm 2025.1.1.1\jbr
gradlew.bat test                                  # unit + headless IDE tests
gradlew.bat runIde -PdemoProject=<path-to-repo>   # sandbox IDE with the plugin, opening that project
gradlew.bat buildPlugin                           # distributable zip in build/distributions
```

The IDE built against is `ideLocalPath` in `gradle.properties` (default: the local PyCharm install, build 262).
