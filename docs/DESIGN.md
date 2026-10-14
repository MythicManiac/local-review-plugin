# Local Review — Design

A JetBrains IDE plugin (developed against PyCharm, usable in any IntelliJ-platform IDE with the Git plugin)
for reviewing **local** git changes — typically AI-authored — before they are pushed for human review.

## Goals

1. Pick two git comparison points; see total code **and file-structure** changes (added / deleted / renamed / moved).
2. Visual diffs comparable to the IDE's regular diff tools (we reuse the platform diff viewer).
3. Annotate lines/regions/files (or the review as a whole) with: **change request**, **question**, **note**.
4. Track review progress per file **and per hunk**; resumable across IDE restarts.
5. Durable state: every saved comment is on disk before the UI reports it saved; removals are tombstones.
6. Export a report (Markdown, JSON, agent prompt) and a per-comment clipboard prompt.
7. Turn recurring findings into proposed `AGENTS.md` rules.

Non-goals: calling an LLM from the plugin, remote PR integration, multi-user real-time collaboration.

## Decisions (made with the user)

| # | Question | Decision |
|---|----------|----------|
| D1 | State storage | One folder per review **outside** the project, plain JSON files, each review folder is its **own git repo, auto-committed** on every change. Sync = add a remote / use any file sync. |
| D2 | Comparison semantics | Review **pins resolved SHAs**. Default diff is PR-style (merge-base of base and head → head). An explicit **Update review** action re-resolves the refs, re-anchors comments, marks unmatched ones *outdated*, and invalidates viewed-marks whose content changed. |
| D3 | Progress | **File-level and hunk-level** "viewed" marks. A mark is bound to a content fingerprint and auto-invalidates when the content changes. |
| D4 | Working tree | **Not** a comparison side. Commits / branches / tags only. Anchors can therefore be exact (line numbers valid for a pinned SHA). |
| D5 | AGENTS.md | Comments can carry a **rule candidate**. Report has a "Proposed AGENTS.md rules" section; an IDE action opens a diff of `AGENTS.md` with the rules appended for the user to edit/accept; an agent-prompt export wraps report + "update AGENTS.md" instruction. |
| D6 | Export | Markdown report; agent-oriented Markdown + machine-readable JSON (stable IDs); clipboard-ready per-comment prompt. |

Decisions made without asking (low-regret, easy to change):

* **Kotlin**, Gradle + IntelliJ Platform Gradle Plugin 2.x, built against the **locally installed PyCharm** (build 262, JBR 25). Depends only on `com.intellij.modules.platform` + `Git4Idea`, so it is not PyCharm-specific.
* **kotlinx.serialization** JSON (bundled in the platform) with `ignoreUnknownKeys` so newer files remain readable by older plugin versions.
* Store root defaults to `~/.local-review/`, configurable in Settings.
* Core logic (model, store, anchoring, export) has **no IDE dependency** so it is unit-testable with plain JUnit.

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
| Visual diff (side-by-side, unified, folding, nav, syntax highlighting) | `DiffManager`, `DiffRequestChain`/`DiffRequestProducer`, `SimpleDiffRequest`, `DiffContentFactoryEx` |
| Attach review UI to the viewer | `DiffExtension` EP (`onViewerCreated`), `DiffViewerListener.onAfterRediff`, `SimpleDiffViewer`/`UnifiedDiffViewer`/`OnesideTextDiffViewer`, `DiffUserDataKeys` (`CONTEXT_ACTIONS`, `SCROLL_TO_LINE`, `FORCE_READ_ONLY`) |
| Inline comment threads in the editor | `EditorEmbeddedComponentManager` (Swing component as block inlay), editor mouse listeners + `RangeHighlighter` gutter renderer |
| File-structure tree with viewed checkboxes | `CheckboxTree` |
| Tool window, settings, notifications | `ToolWindowFactory`, `Configurable`, `Notification` |
| Git | `git4idea` (`GitRepositoryManager`, `GitExecutableManager`); diff/merge-base/blob data via our own `GitCli` so core stays IDE-free |

## Storage layout

```
<store-root>/<repo-name>/<review-id>/        # a git repo, auto-committed
  review.json                                 # metadata + pinned revisions
  comments/<comment-uuid>.json                # one file per comment (merge-friendly)
  progress/<sha1(path)[0..16]>.json           # one file per reviewed file (file + hunk marks)
  exports/<timestamp>/{report.md,report.json,agent-prompt.md}
```

Per-project "last opened review" pointers live beside the reviews in `<store-root>/.state/last-opened/` (atomic writes), not in IDE state,
so resume survives crashes and forced kills.

* `review.json`: `schemaVersion`, `id`, `title`, `repo{name, remoteUrl?, lastKnownPath}`, `baseRef`, `headRef`,
  `mergeBase` (bool), `revisions[]` (append-only `{n, at, baseSha, headSha, effectiveBaseSha}`), `createdAt`.
* Comment: `id`, `kind` (`CHANGE_REQUEST|QUESTION|NOTE`), `status` (`OPEN|RESOLVED|WONT_FIX`), `body`,
  `anchor{scope FILE|LINES|REVIEW, path, side BASE|HEAD, startLine, endLine, snippet, before[], after[], revision}`,
  `outdated`, `rule?` (AGENTS.md candidate), `createdAt`, `updatedAt`, `deletedAt?` (**tombstone**).
* Progress file: `path`, `fileFingerprint`, `viewed`, `hunks[{fingerprint, viewed}]`, `updatedAt`.

### Durability rules

1. Write to `<name>.tmp` in the same directory, `fsync`, atomic rename over the target. A crash leaves either the old or new file.
2. The UI only shows "saved" after the rename succeeded; failures are surfaced as an error notification and the editor
   keeps the draft open.
3. **Nothing is ever deleted.** Removing a comment sets `deletedAt`; the UI offers *Show deleted* / *Restore*.
4. After each write an asynchronous, coalesced `git add -A && git commit` runs in the review repo (identity falls back to
   `Local Review <local-review@localhost>` if none configured). Commit failure never loses data (files are already
   durable) and is reported in the status bar widget / tool window.
5. On load, unreadable/corrupt files are quarantined (renamed `*.corrupt`) and reported — never silently dropped.
6. IDs are random UUIDs so independent devices never collide; per-entity files keep git merges trivial.

## Anchoring & update

Because revisions are pinned, `(side, path, line range)` is exact for that revision. We additionally store the selected
`snippet` and 3 lines of context before/after. **Update review**:

1. Re-resolve `baseRef`/`headRef`; append a revision.
2. Map paths across revisions by `git diff -M --name-status old new` (renames), then locate the snippet near the old line
   (exact multi-line match nearest to old line → context-scored fuzzy match). Success → move anchor to the new revision.
   Failure → `outdated = true`, original anchor/snippet retained for display.
3. File/hunk fingerprints are recomputed; marks whose fingerprint changed become un-viewed (and flagged *changed since
   viewed* so the user knows why).

## Components

```
core/    model (Review, Comment, Anchor, Progress), ReviewStore (atomic IO + tombstones), GitCommitter,
         Anchoring (re-anchor), Fingerprints, Export (markdown/json/agent prompt/AGENTS proposal)      [no IDE deps]
git/     GitService: resolve refs, merge-base, change list (Change objects for the diff viewer)
ui/      ReviewToolWindow (review picker, changes tree w/ viewed state + comment counts, comments tab, progress),
         DiffExtension (inline comments, add-comment gutter, hunk viewed), dialogs, actions, Settings
```

## Verification strategy

* Unit tests (JUnit, no IDE): store durability/tombstones/corruption quarantine, anchoring, fingerprints, export golden files, real-git integration tests in temp repos.
* IDE smoke: launch the sandbox IDE via `runIde`, exercise create review → open diff → add comment → restart → state persists → export.
