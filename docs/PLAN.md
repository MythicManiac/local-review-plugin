# Implementation Plan

Status legend: `[ ]` todo, `[x]` done. Each phase ends with a commit.

- [x] **P0 Plan** — design + plan docs, decisions recorded (`docs/DESIGN.md`).
- [x] **P1 Scaffold** — Gradle + IntelliJ Platform Gradle Plugin against local PyCharm, `plugin.xml`, empty tool window, `runIde` boots.
- [x] **P2 Core model + store** — model, atomic writes, tombstones, corruption quarantine, auto-commit to review repo. Unit tested.
- [x] **P3 Git service + review lifecycle** — (resolve, merge-base, diff, create, update; create dialog in P4) Update review (re-anchor, invalidate marks). Tested with temp repos.
- [x] **P4 Tool window** — review picker, changes tree with directory structure and status colours (A/D/R/M), viewed checkbox, comment counts, progress bar.
- [x] **P5 Diff integration** — open IDE diff for a file, inline comment threads, add-comment gutter/selection action, edit/resolve/delete (tombstone) in place, hunk viewed marks, file/review-level comments.
- [x] **P6 Comments tab** — list/filter (kind, status, file, deleted), navigate to diff, restore tombstoned.
- [x] **P7 Export** — Markdown report, JSON, agent prompt, per-comment clipboard prompt, rule candidates, AGENTS.md proposal diff.
- [x] **P8 Smoke + docs** — run the sandbox IDE end to end, README usage, changelog.

## Risks / open items

* The installed IDE is build 262 (very new); API shapes for `DiffExtension`, `ChangesTree`, collaborationTools are verified against the installed jars/sources, not memory.
* collaborationTools helpers are not a documented stable API → isolated in `ui.platformreuse`.
* Unified (inline) diff viewer needs line mapping; side-by-side is primary, unified supported if the mapping API is clean.
* GUI cannot be fully driven headlessly; smoke relies on `runIde` plus IDE-level tests for the diff extension.

## Follow-up: review groups (done)

- [x] Groups (one repo per group, many projects), per-project default group, isolation between groups
- [x] Optional remote per group, background auto-sync, conflict-safe merge, live reload after pull
- [x] Group UI (picker, manage dialog, sync action, header status), one-time migration of the old layout

## Verification done

* 46 automated tests (incl. two-device sync against a real bare remote, conflict convergence, migration)
* (earlier) 35 automated tests (core store/anchoring/lifecycle with real git, export, headless platform test that renders a real diff viewer and asserts inlays/markers).
* Manual smoke in the sandbox IDE (`runIde`): create review, tree statuses, open diff, add/edit comment, hunk + file marks, rule candidate, AGENTS.md proposal/write,
  export, Update Review re-anchoring, forced IDE kill then automatic resume with all data intact.

## Known limits / ideas not done

* Moving a review to a different group is not offered (copy by hand if needed).
* Nothing is pushed at IDE shutdown; unsynced commits go out on the next sync.
* Drafts being typed are not auto-saved until you press save (saved comments always are).
* Agent replies are not imported back; the JSON export carries stable ids so this can be added.
* Diff viewer tab is closed on Update Review (it shows a single revision).
* Hunk fingerprints come from the viewer's diff, so toggling whitespace-ignore can change hunk boundaries.
