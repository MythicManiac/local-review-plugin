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

## Follow-up: the Local Review state format, 0.2.0 (done)

Adopted the frozen specification (`spec/` submodule, tag `baseline-1`) on branch `format-v2`:

- [x] P1 Core algorithms (line model, hashing, trim set, intervals, mark relocation, comment location, repository-file rules), every vector passing
- [x] P2 Event model, tolerant reader, fold and evaluation; `examples/state` folds and evaluates to `examples/expected`
- [x] P3 Event log writer (per-event device lock, tail read, fsync), per-machine device id with a machine fingerprint
- [x] P4 Group store, repository files with the effective-rules check, pinned change-set command, the three sync resolutions
- [x] P5 Import of 0.1.0 reviews (canonical `review.json`, deterministic event ids)
- [x] P6 UI: two-range comments, region marks, background progress, replies, review status, rule decisions, outdated + accept suggestion
- [x] P7 Docs, changelog, version 0.2.0

## Verification done

* Conformance: all spec vectors, the `examples/` fold and evaluation, and the submodule drift test.
* Automated tests with real git and bare remotes: two-device sync, the three conflict resolutions and abort on anything else, three JVMs appending to one log,
  a lock held by another process, the pinned change-set command under hostile git configuration, import against files written by 0.1.0's own serializers
  (byte-identical output from two devices), export, and headless platform tests of the diff viewer (comments, hunk marks, stale marks, accepting a suggestion).
* Plugin verifier: compatible with PyCharm and IntelliJ IDEA 2026.2.3, no deprecated or internal API usages.
* Manual smoke in the sandbox IDE (`runIde`) on a demo group in the 0.1.0 layout: automatic import with a notification, open, inline comments, hunk checkbox writing region and file marks,
  reply, review status.

## Known limits / ideas not done

* Moving a review to a different group is not offered (copy by hand if needed).
* Nothing is pushed at IDE shutdown; unsynced commits go out on the next sync.
* Drafts being typed are not auto-saved until you press save (saved comments always are).
* Agent replies are not imported back; the JSON export carries stable ids so this can be added.
* Diff viewer tab is closed on Update Review (it shows a single revision).
* Hunks come from the IDE's comparison, which differs from another tool's; only line ranges are stored, so two tools can show different hunks for one file.
* A mark on a single repeated line (a blank line, `}`) goes stale when the file changes, because a mark is only relocated when the lines occur exactly once.
* A review can be renamed (`reviewRenamed` is written by the service) but there is no action for it yet.
* Re-anchoring by picking lines yourself (`method: picked`) is not offered; accepting the suggestion is.
* A stale `.git/index.lock` left by a crashed git is reported with git's message; nothing removes it.
