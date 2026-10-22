# Changelog

All notable changes to this project are documented here. The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added

- The lines a comment is pinned to are highlighted in the diff, in a colour of their own (not the selection colour): saved comments tint their lines by kind, and while you hover the `+` the lines a new comment would get are shown, so it is visible that the selection decides the range. They stay highlighted while the comment is being written.

### Changed

- The Repository dropdown of the New Review dialog shows the folder name first, then where it is (relative to the project, or with `~`), at full width, instead of a truncated absolute path.

### Fixed

## [0.2.0] - 2026-10-21

Reviews are now stored in a documented, shareable format. **Upgrade all your devices together**: a 0.1.0 device does not see what 0.2.0 writes. Reviews made with 0.1.0 are imported automatically (see below).

### Added

- A documented state format (the specification is the `spec/` submodule): each review is a small `review.json` plus one append-only event log per device. Merging two devices never conflicts, and other tools can read and write the same reviews.
- Replies on comments (inline and from the Comments tab), including edit and delete with undo.
- Review status: mark a review *Done* or *Archived*; archived reviews are left out of **Open Review**, which lists them under "Archived reviews…".
- Outdated comments get a suggested new place that you can accept (right-click → **Accept Suggested Position**). A comment never moves by itself.
- A comment can cover a selection across removed and added lines in a unified diff: one range per side.
- Hunk and file marks are stored as marks on lines plus a hash of their content, so they follow the lines when the file changes and show as stale where the lines changed.
- The Changes tree shows progress by changed lines, computed in the background ("calculating…"), and shows **unverified** instead of valid or stale when the reviewed commits are not on the machine.
- Comments flagged as a recurring pattern no longer need rule text; those without it are listed as "needs wording" in the proposal and the exports. Writing rules to `AGENTS.md` records the decision, which takes the comments out of the inbox. **Dismiss Rule Candidate** records a decline.
- Several IDEs on one machine can write to the same review at the same time (one device id per machine, a per-event lock).
- Opening a group completes its repository files (`.gitignore`, `.gitattributes`) and checks that git honours them.
- Reviews made with 0.1.0 are imported automatically when a group opens, if the reviewed repository and its commits are on the machine; otherwise you are told and it is retried. The old files are left untouched. Hunk marks cannot be converted.

### Changed

- Line numbers, snapshots and hashes come from the git blob bytes by the line model of the format, not from the editor's document. A file that is not text (a NUL byte, invalid UTF-8, a lone CR) gets file comments and file marks only.
- The review diff viewer always compares whitespace-sensitively, so a whitespace-only change can be marked reviewed.
- *Update Review* writes a retarget event and no longer rewrites comments.
- A sync that git cannot merge by itself now follows fixed rules: `group.json` keeps the group created first, the repository files are merged line by line, and anything else aborts the merge and names the file. The `*.conflict-*` side files are gone.
- The commit author falls back to `Local Review <local-review@localhost>`; a commit waits briefly while another tool holds `index.lock`.
- Unreadable files are reported and left where they are instead of being renamed to `*.corrupt`.
- The Markdown and JSON reports include replies and the review status; the JSON report has schema version 2.

### Removed

- Migration of the layout from before review groups (one git repository per review).

## [0.1.0] - 2026-10-14

First public release.

### Added

- Review two git comparison points (branch, tag or commit) in a pull-request style diff; commits are pinned so the review stays stable.
- File tree with added, modified, deleted and renamed files, per-file and per-hunk "reviewed" marks that clear themselves when the code changes, and open-comment counts.
- Comments in the IDE diff viewer on lines, ranges, files or the whole review: change requests, questions and notes, with resolve, reopen, edit and restorable delete.
- Update Review: follow a moving branch, re-anchor comments to the new code and flag the ones whose code was rewritten as outdated.
- Export a Markdown report, JSON and an AI agent prompt; copy a single comment as a prompt; propose AGENTS.md rules from comments marked as recurring patterns.
- Review groups: one git repository per group holding reviews of many projects, with an optional remote for syncing between devices, per-group SSH key and conflict-safe merging; separate groups keep unrelated work isolated.
- Crash-safe storage: every save is written atomically and verified, history is auto-committed, and the last open review reopens after a restart.

[Unreleased]: https://github.com/MythicManiac/local-review-plugin/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/MythicManiac/local-review-plugin/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/MythicManiac/local-review-plugin/commits/v0.1.0
