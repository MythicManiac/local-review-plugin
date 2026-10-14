# Implementation Plan

Status legend: `[ ]` todo, `[x]` done. Each phase ends with a commit.

- [x] **P0 Plan** — design + plan docs, decisions recorded (`docs/DESIGN.md`).
- [x] **P1 Scaffold** — Gradle + IntelliJ Platform Gradle Plugin against local PyCharm, `plugin.xml`, empty tool window, `runIde` boots.
- [ ] **P2 Core model + store** — model, atomic writes, tombstones, corruption quarantine, auto-commit to review repo. Unit tested.
- [ ] **P3 Git service + review lifecycle** — resolve refs, merge-base, change list; create review dialog; Update review (re-anchor, invalidate marks). Tested with temp repos.
- [ ] **P4 Tool window** — review picker, changes tree with directory structure and status colours (A/D/R/M), viewed checkbox, comment counts, progress bar.
- [ ] **P5 Diff integration** — open IDE diff for a file, inline comment threads, add-comment gutter/selection action, edit/resolve/delete (tombstone) in place, hunk viewed marks, file/review-level comments.
- [ ] **P6 Comments tab** — list/filter (kind, status, file, deleted), navigate to diff, restore tombstoned.
- [ ] **P7 Export** — Markdown report, JSON, agent prompt, per-comment clipboard prompt, rule candidates, AGENTS.md proposal diff.
- [ ] **P8 Smoke + docs** — run the sandbox IDE end to end, README usage, changelog.

## Risks / open items

* The installed IDE is build 262 (very new); API shapes for `DiffExtension`, `ChangesTree`, collaborationTools are verified against the installed jars/sources, not memory.
* collaborationTools helpers are not a documented stable API → isolated in `ui.platformreuse`.
* Unified (inline) diff viewer needs line mapping; side-by-side is primary, unified supported if the mapping API is clean.
* GUI cannot be fully driven headlessly; smoke relies on `runIde` plus IDE-level tests for the diff extension.
