# Changelog

All notable changes to this project are documented here. The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

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
