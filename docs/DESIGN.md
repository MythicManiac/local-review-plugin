# Local Diff Review — Design

A JetBrains IDE plugin (developed against PyCharm, usable in any IntelliJ-platform IDE with the Git plugin)
for reviewing **local** git changes — typically AI-authored — before they are pushed for human review.

## Goals

1. Pick two git comparison points; see total code **and file-structure** changes (added / deleted / renamed / moved).
2. Visual diffs comparable to the IDE's regular diff tools (we reuse the platform diff viewer).
3. Annotate lines/regions/files (or the review as a whole) with: **change request**, **question**, **note**; reply to comments.
4. Track review progress per file **and per region of lines**; resumable across IDE restarts.
5. Durable state: every saved comment is on disk (fsync'd) before the UI reports it saved; removals are tombstones.
6. Export a report (Markdown, JSON, agent prompt) and a per-comment clipboard prompt.
7. Turn recurring findings into proposed `AGENTS.md` rules.
8. Share a review between devices, and with other tools, through git, in a documented format.

Non-goals: calling an LLM from the plugin, remote PR integration, multi-user real-time collaboration.

## The state format

Since 0.2.0 the plugin stores reviews in the **Local Review state format**, a specification shared with a second tool
(a desktop app). The frozen baseline is the `spec/` git submodule (tag `baseline-1`): `SPEC.md` (layout, events, fold,
evaluation, sync), `ALGORITHMS.md` (line model, hashing, marks, comment location), `schema/`, `vectors/` (hand-derived
test cases) and `examples/` (a state directory with the expected folded result). This document describes how the plugin
implements it; the specification is the authority on the format. Where the plugin differs from, or adds to, the format,
it is said here.

The implementation is tested against the specification, not against itself: `SpecVectorsTest` runs every vector,
`ExampleTest` folds and evaluates `examples/state` to `examples/expected`, and a drift test fails if the submodule differs
from its `MANIFEST.sha256`. Problems found while implementing go to `spec/FINDINGS.md`, never into a silent deviation.

## Decisions (made with the user)

| # | Question | Decision |
|---|----------|----------|
| D1 | State storage | **Event logs** in **review groups**: one group = one git repo, auto-committed, holding the reviews of any number of projects (D7). A device only appends to its own log, so merges do not conflict. |
| D2 | Comparison semantics | Review **pins resolved SHAs**. Default diff is PR-style (merge-base of base and head → head). **Update review** re-resolves the refs and writes a retarget event; comments are not rewritten, their state against the new code is computed (D9). |
| D3 | Progress | A **file mark** (valid while the file's change key is unchanged) and **region marks** (lines plus a hash of their content, which follow the lines when the file changes). Hunks are only a convenient way to choose a range. |
| D4 | Working tree | **Not** a comparison side. Commits / branches / tags only. Anchors can therefore be exact (line numbers valid for a pinned SHA). |
| D5 | AGENTS.md | Comments can be **rule candidates** (a tag, with optional rule text; a tag without text is listed as "needs wording"). Writing rules to `AGENTS.md` records `ruleDecided`, which takes the comments out of the inbox. |
| D6 | Export | Markdown report; agent-oriented Markdown + machine-readable JSON (stable IDs); clipboard-ready per-comment prompt. Replies are included. |
| D7 | Grouping & sync | Reviews belong to a **group**; a group is one git repo and may have a **remote** for auto-syncing between devices. |
| D8 | Device identity | One device id **per machine**, shared by every IDE that runs the plugin, in `~/.local-review/.state/device.json` with a machine fingerprint: a copied id (file sync, backup restore) is replaced. All IDEs write through a per-event lock. |
| D9 | Outdated comments | Comment state (`current`, `unchanged`, `outdated`) is computed, never stored. An outdated comment gets a suggested new place; **it never moves by itself**: the user accepts it (`annotationReanchored`). |
| D10 | Old data | Reviews written by 0.1.0 are imported automatically when a group opens, if the reviewed repository and its commits are on the machine. The old files are never changed. |

Decisions made without asking (low-regret, easy to change):

* **Kotlin**, Gradle + IntelliJ Platform Gradle Plugin 2.x, built against the **locally installed PyCharm** (build 262, JBR 25). Depends only on `com.intellij.modules.platform` + `Git4Idea`, so it is not PyCharm-specific.
* **kotlinx.serialization** JSON (bundled in the platform), used as a tree (`JsonElement`): readers must tolerate unknown events and fields, so data classes with a fixed shape would be the wrong tool. `review.json` is written by a small canonical writer that matches `JSON.stringify(v, null, 2)` byte for byte, so two devices that import the same review write the same bytes.
* Store root defaults to `~/.local-review/`, configurable in Settings.
* The `format` and `store` packages have **no IDE dependency** and are unit-tested with plain JUnit.

## Reuse of IDE review/diff machinery

Investigated in the installed distribution and the `intellij-community` sources
(`platform/collaboration-tools`, ~800 classes under `com.intellij.collaboration.ui.codereview.*`), the toolkit the
GitHub/GitLab plugins build on. Findings:

* The editor-comment helpers (`EditorComponentInlaysManager`, `EditorRangesController`, `DiffEditorGutterIconRendererFactory`)
  are annotated `@ApiStatus.ScheduledForRemoval` / `@Deprecated` and absent from the module's stable `api-dump.txt`;
  the surviving pieces are view-model based (Kotlin flows, server-backed `CodeReviewChangeListViewModel`, `RefComparisonChange`)
  and listed only in `api-dump-experimental.txt`.
* **Decision: no dependency on collaboration-tools.** Adopting it would mean mangling a remote-PR view-model stack
  that is being removed/reworked.

What we reuse instead (all public, stable-or-widely-used platform APIs):

| Need | Platform API |
|------|--------------|
| Visual diff (side-by-side, unified, folding, nav, syntax highlighting) | `DiffManager`, `DiffRequestChain`/`DiffRequestProducer`, `SimpleDiffRequest`, `DiffContentFactory` |
| Attach review UI to the viewer | `DiffExtension` EP (`onViewerCreated`), `DiffViewerListener.onAfterRediff`, `SimpleDiffViewer`/`UnifiedDiffViewer`/`OnesideTextDiffViewer`, `DiffUserDataKeys` (`CONTEXT_ACTIONS`, `SCROLL_TO_LINE`, `FORCE_READ_ONLY`, `DO_NOT_IGNORE_WHITESPACES`) |
| Changed lines for progress | `ComparisonManager.compareLines` (the engine the viewer uses, so tree and viewer agree) |
| Inline comment threads in the editor | `EditorEmbeddedComponentManager` (Swing component as block inlay), editor mouse listeners + `RangeHighlighter` gutter renderer |
| File-structure tree with reviewed checkboxes | `CheckboxTree` |
| Tool window, settings, notifications | `ToolWindowFactory`, `Configurable`, `Notification` |
| Git | `git4idea` (`GitRepositoryManager`, `GitExecutableManager`); everything else via our own `GitCli` so the store stays IDE-free |

## Storage layout

```
<store-root>/                                   default ~/.local-review
  <group>/                                      ONE git repo per group (branch: main), optional `origin` remote
    group.json                                  schemaVersion 2, id, createdAt (name optional)
    .gitattributes                              * -text  /  *.jsonl merge=union
    .gitignore                                  *.lock  /  *.tmp  /  /reviews/*/local/
    reviews/<review-id>/
      review.json                               immutable: title, repo identity, refs, mode, pin at creation
      events/<device-id>.jsonl                  one append-only log per device
      events/<device-id>.lock                   the device lock (never committed)
      exports/<timestamp>/{report.md,report.json,agent-prompt.md}
  .state/                                       per-machine data, NEVER synced, outside every group repo
    device.json                                 this machine's device id + machine fingerprint
    last-opened/<key>.txt                       last open review per IDE project (crash-safe resume)
    repo-groups/<key>.txt                       which group a repository's reviews default to
    import/<key>.txt                            what was already imported from 0.1.0 files
```

* **Events**: `reviewRenamed`, `reviewStatusChanged`, `reviewRetargeted`, `annotationCreated/Edited/StatusChanged/Reanchored/Deleted/Restored`,
  `replyAdded/Edited/Deleted/Restored`, `fileReviewed/Unreviewed`, `regionReviewed/Unreviewed`, `ruleDecided`. Each line is
  `{v, id, ts, dev, seq, ev}`. The state of a review is the **fold** of all events of all logs, ordered by `(ts, dev, seq, id)`;
  duplicates by id are dropped; unknown or damaged events are skipped, counted and left in the file untouched.
* **Anchors** are line ranges (one per side, at most two), each with a snapshot of the lines and 3 lines of context, valid for
  the three commits (`pin`) stored on the comment. Nothing is bound to hunks.
* **Lines come from the blobs.** Line numbers, snapshots and hashes are computed from the git blob bytes by the format's line
  model (UTF-8, BOM dropped, split on LF only, one CR removed), never from the editor's document. The viewer is fed that decoded
  text so its numbering agrees; a file that is not text by those rules (a NUL, invalid UTF-8, a lone CR) gets file comments and
  file marks only, and a side whose editor shows other line numbers gets no line features.
* Projects are matched to their reviews by root commit, then normalised remote URL, then folder name, so a review made on one
  device is found on another where the checkout lives elsewhere. No machine path is ever written to a synced file.

### Groups

* **Group = repo.** Each group has its own history, its own remote and its own sync state; nothing is shared between groups,
  which is the isolation boundary. The remote URL is the repo's `origin`, so plain `git` works on it; the per-machine
  *auto-sync* switch is `git config localreview.autosync` (default on once a remote exists; never synced).
* **SSH identity per group** (`localreview.sshkey` + `core.sshCommand` in the group's `.git/config`): lets setups where the
  user's git config only selects a key by project path (`includeIf gitdir:` + `core.sshCommand`) reach each group's remote with the
  right key, since groups live under the store folder that no such include matches. Set before the first contact with the
  remote (also at group creation), applied by plain git as well, per machine, never synced. Clearing removes only what the
  plugin wrote; a hand-written `core.sshCommand` is left alone. Auth/host-key failures name the setting to fix.
* A new review asks for its group (default: the one last used for that repository, else `default`). Creating a group with
  a remote that already holds a group (made on another device) downloads it.
* **Opening a group** appends any missing required lines to `.gitignore` and `.gitattributes`, asks git (`check-attr`,
  `check-ignore`) whether each rule is actually honoured, and appends a line again where a later line overrides it. It never
  removes or rewrites another line. A group made by 0.1.0 has its `group.json` converted in place and its files completed.
* **Import from 0.1.0**: `LegacyImporter` turns each old review (`<repo-name>/<review-id>/` with `comments/` and `progress/`)
  into a version 2 review: canonical `review.json`, and events in `events/import-v1.jsonl` with deterministic ids, so two devices
  importing before they sync write the same bytes. Anchors are mapped through the change set; line anchors on files that are
  not text become file comments; a file mark survives only if 0.1.0's own fingerprint still matches. Needs the reviewed
  repository and every commit; otherwise it reports "not imported yet" and writes nothing. Hunk marks cannot be converted and
  are dropped.

### Sync

`GroupSync` per group, run in the background: commit pending changes → `fetch --prune` → `merge --no-edit --allow-unrelated-histories`
→ `push origin HEAD:refs/heads/main`, retried (at most 3 times) if the remote moved.

* Triggers: debounced (15 s) after local commits, on opening a review in the group, every 5 min while a group is in use,
  and manually (**Sync Review Group**, **Manage Review Groups → Sync Now**). Only groups with a remote and auto-sync on
  sync automatically. Nothing is pushed on IDE shutdown (commits are local-durable and go out on the next sync).
* Devices write only their own logs, and `*.jsonl` uses git's `union` merge driver, so merges do not conflict. A merge can still
  conflict in three cases, resolved as the format says: `group.json` keeps the group created first; `.gitignore` and
  `.gitattributes` keep every other line, sorted, with the required lines last; **anything else aborts the merge**, names the
  path, and leaves the user's data exactly as it was. No winner is chosen and no loser is kept aside.
* A sync merge holds the group's repository lock, and so does every event append, so a half-merged tree is never written into.
  After a pull, the open review is re-read and the UI updates live. Another IDE's appends are noticed by polling the log sizes.
* Failures (offline, auth) never touch local data. They show in the header/Manage dialog and as one notification until
  the next success. Credentials come from the user's normal git setup (SSH agent/credential manager); prompts are disabled.
* A synced review can reference commits that do not exist locally (the other device's unpushed branch). The review opens
  with a visible warning, and marks and comment states are shown as **unverified** (never as valid or stale) until the commits
  are fetched.

### Durability rules

1. Each event is appended in one critical section: take the OS lock on `<device-id>.lock` (**never** on the log: a lock on
   the log makes `git add -A` fail on Windows), read the last valid line of the log for `ts` and `seq`, append, `fsync`,
   release. The UI only shows "saved" after the `fsync` returned; if the lock cannot be taken in about 3 s, the change is
   reported as not saved and the editor keeps the draft open.
2. A device never rewrites, truncates or reorders a log; corrections are new events. A write cut off by a crash is closed
   with a newline by the next writer, and the fragment is reported, not hidden.
3. `review.json` and `group.json` are written once (atomic: temp file, `fsync`, rename, read back).
4. **Nothing is ever deleted.** Removing a comment or a reply writes a tombstone; the UI offers *Show deleted* / *Restore*.
5. After each write an asynchronous, coalesced `git add -A && git commit` runs in the group repo (identity falls back to
   `Local Review <local-review@localhost>` if none configured; a brief retry while another tool holds `index.lock`). Commit
   failure never loses data (events are already durable) and is reported in the tool window.
6. Unreadable files are reported and left where they are; nothing is renamed or moved.

## Marks, anchors and update

* **Marking a hunk** writes a `regionReviewed` with one range per side that has changed lines (blob id + hash of those lines).
  Marking a whole file writes `fileReviewed` plus a region per hunk, so the diff and the tree agree; when the last hunk of a file
  is marked, the file mark is written too. Unmarking a hunk of a file that is only marked as a whole first turns that mark into
  region marks of the other hunks.
* **Evaluation** (off the UI thread, cached per blob): a region follows its lines if they are still where they were, or occur
  exactly once elsewhere in the file; ambiguous or edited lines are *stale*, never guessed. A file mark is *valid* while the
  change key (`oldMode:newMode:oldBlob:newBlob`) is unchanged, *stale* otherwise, *gone* if the file left the change set. The
  Changes tree shows "calculating…" until the analysis arrives and "unverified" when the code is not on the machine.
* **Update review** re-resolves `baseRef`/`headRef` and, if the pin moved, writes `reviewRetargeted`. Each comment is then
  `current` (same pin), `unchanged` (older pin, same lines at the same numbers) or `outdated`; for an outdated one the plugin
  computes a suggestion (exact after trailing-whitespace trim, then trimmed, then ≥ 70 % similar lines, ranked by context
  and distance) and offers **Accept Suggested Position**.
* Comparison for hunks uses the IDE's engine with whitespace-sensitive settings forced on the review viewer, because a
  whitespace-only change is part of the change set and a hunk that hid it could never be marked.

## Components

```
format/  the specification in code: line model, hashing, marks, comment location, events and codec, fold, evaluation,
         canonical JSON, repository-file rules                                                        [no IDE deps]
store/   EventLogWriter, DeviceIdentity, ReviewGroups/ReviewGroup/OpenReview, GroupFiles, ChangeSets/GitBlobs,
         LegacyImporter                                                                               [no IDE deps]
core/    GitCli, GitCommitter, GroupSync, SshKeys, AtomicFiles                                        [no IDE deps]
export/  Markdown / JSON / agent prompt / AGENTS.md proposal                                          [no IDE deps]
ui/      ReviewService (lifecycle, analysis, writes), tool window (changes tree, comments tab), actions, dialogs,
         diff/ (DiffExtension: inline comments, add-comment gutter, hunk marks), settings
         notes/ (the review notes tab: comments with `scope: review`, an editor tab on a virtual file so it can be split beside a diff)
spec/    git submodule: the frozen specification, its vectors and example
```

## Verification strategy

* Conformance: every vector of `spec/vectors`, the `examples/` fold and evaluation, and the manifest drift test.
* Unit tests (JUnit, no IDE): the event log writer (torn tails, clock going backwards, several JVMs appending to one log, a lock
  held by another process), device identity, groups and sync against real git with bare remotes (union merges, the three
  conflict resolutions, abort on anything else), the pinned change-set command, the importer against files written by 0.1.0's
  own serializers, export golden checks.
* Platform tests (headless IDE): a real diff viewer with comments and hunk checkboxes, hunk marks completing a file, marks going
  stale on a new commit, accepting a suggested position.
* IDE smoke: launch the sandbox IDE via `runIde` on a 0.1.0 demo group: import, open, inline comments, hunk checkbox, reply, status.
