# Changelog

## Unreleased

* **Review groups:** reviews are stored in groups, one git repo per group holding many projects' reviews, instead of one repo per review.
  Groups can have a remote and **auto-sync between devices** (debounced after changes, on open, periodic, manual). Separate groups isolate
  projects/organisations (own history and remote).
* Conflict-safe merging: concurrent edits of the same file keep both versions (`*.conflict-*`) and converge on all devices.
* Reviews synced from another device open with a warning if their commits are not available locally.
* New: Manage Review Groups dialog, Sync Review Group action, group picker in New Review, group and sync state in the header.
* Automatic one-time migration of the previous per-review layout into the `default` group (old histories kept in `.legacy/`).

## 0.1.0

First complete version.

* Reviews pin base/head SHAs (PR-style merge-base diff by default); **Update Review** re-pins and re-anchors comments.
* Changes tree with file-structure status (added/modified/deleted/renamed), reviewed checkboxes, hunk progress, comment counts.
* IDE diff viewer integration: inline comment threads, hover "+" to comment on a line or selection, per-hunk reviewed markers.
* Comments: change request / question / note, resolve/reopen, tombstone delete + restore, file- and review-level comments, outdated detection.
* Durable storage: one auto-committed git repo of JSON files per review; atomic fsync'd writes with read-back verification; transient Windows file-lock retry; corrupt files quarantined.
* Crash-safe resume of the last open review.
* Export: Markdown, JSON, agent prompt, per-comment prompt; AGENTS.md rule proposal with editable diff.
