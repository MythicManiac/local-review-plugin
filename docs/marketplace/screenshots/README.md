# Marketplace screenshots

Six images, all 1280x800 (16:10, the size JetBrains recommends), default dark IDE theme, only the IDE window (no desktop,
no browser), neutral demo data (`orders-service`, group `work`); no personal information.

Upload them in this order (Media section of the plugin page):

| # | File | Caption |
|---|------|---------|
| 1 | `01-inline-comments.png` | Annotate the diff: change requests, questions and notes, right where the code is. |
| 2 | `02-side-by-side-hunks.png` | Track progress per file and per hunk, in the IDE's own side-by-side diff. |
| 3 | `03-comments-tab.png` | Every comment of the review in one filterable list; double-click to jump to the code. |
| 4 | `04-new-review.png` | Start a review from any two branches, tags or commits and choose its review group. |
| 5 | `05-review-groups.png` | Review groups: one git repository per group, with an optional remote that syncs between devices. |
| 6 | `06-agents-md-proposal.png` | Turn recurring findings into proposed AGENTS.md rules, editable before anything is written. |

## Regenerating

Needs Windows, PyCharm (as configured in `gradle.properties`), Python 3 and git.

```
python tools/screenshots/make_demo.py            # builds C:/demo (project, review store, bare remote) and points the sandbox IDE at it
gradlew runIde -PdemoProject=C:/demo/orders-service
powershell -File tools/screenshots/ide-window.ps1 place 1280 800     # then top / click / keys / shot <file.png>
```

`ide-window.ps1` captures only the IDE's visible window frame and refuses to send any input unless a window of the IDE
process is in the foreground.
