package com.mythic.localreview.core

import kotlinx.serialization.Serializable

/** Bump when the persisted shape changes incompatibly. Readers ignore unknown keys, so additive changes need no bump. */
const val SCHEMA_VERSION = 1

@Serializable
data class RepoInfo(
    val name: String,
    val remoteUrl: String? = null,
    val lastKnownPath: String? = null,
)

/** One pinned resolution of the review's refs. Append-only; the last entry is the current one. */
@Serializable
data class Revision(
    val n: Int,
    val at: String,
    val baseSha: String,
    val headSha: String,
    /** The commit the diff is taken from: merge-base(baseSha, headSha) for PR-style reviews, else baseSha. */
    val effectiveBaseSha: String,
)

@Serializable
data class Review(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val title: String,
    val repo: RepoInfo,
    val baseRef: String,
    val headRef: String,
    val mergeBase: Boolean = true,
    val revisions: List<Revision>,
    val createdAt: String,
    val updatedAt: String,
) {
    val current: Revision get() = revisions.last()
}

@Serializable
enum class CommentKind(val label: String) {
    CHANGE_REQUEST("Change request"),
    QUESTION("Question"),
    NOTE("Note"),
}

@Serializable
enum class CommentStatus(val label: String) {
    OPEN("Open"),
    RESOLVED("Resolved"),
    WONT_FIX("Won't fix"),
}

@Serializable
enum class Side { BASE, HEAD }

@Serializable
enum class AnchorScope { REVIEW, FILE, LINES }

/**
 * Where a comment lives. Lines are **1-based and inclusive**; they are exact for [revision]'s pinned SHAs.
 * [snippet] and the context lines make re-anchoring after "Update review" possible.
 */
@Serializable
data class Anchor(
    val scope: AnchorScope = AnchorScope.REVIEW,
    val path: String? = null,
    val side: Side = Side.HEAD,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val snippet: List<String> = emptyList(),
    val before: List<String> = emptyList(),
    val after: List<String> = emptyList(),
    val revision: Int = 1,
) {
    init {
        if (scope == AnchorScope.LINES) {
            require(path != null && startLine != null && endLine != null && startLine >= 1 && endLine >= startLine) {
                "Invalid line anchor: $path:$startLine-$endLine"
            }
        }
        if (scope == AnchorScope.FILE) require(path != null) { "File anchor needs a path" }
    }
}

@Serializable
data class Comment(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val kind: CommentKind = CommentKind.NOTE,
    val status: CommentStatus = CommentStatus.OPEN,
    val body: String,
    val anchor: Anchor,
    /** True when the anchor could not be mapped onto the current revision; the original anchor is kept for display. */
    val outdated: Boolean = false,
    /** Non-null = this finding is a candidate AGENTS.md rule; the text is the proposed rule. */
    val rule: String? = null,
    val resolutionNote: String? = null,
    val createdAt: String,
    val updatedAt: String,
    /** Tombstone: non-null means deleted. The file is never removed from disk. */
    val deletedAt: String? = null,
) {
    val deleted: Boolean get() = deletedAt != null
}

/** Per-file review progress. Viewed state is bound to content fingerprints so it invalidates itself. */
@Serializable
data class FileProgress(
    val schemaVersion: Int = SCHEMA_VERSION,
    val path: String,
    /** File-diff fingerprint at the time the file was marked viewed; file counts as viewed only while it still matches. */
    val viewedFingerprint: String? = null,
    /** Fingerprints (see [Fingerprints.hunk]) of hunks marked viewed. */
    val viewedHunks: List<String> = emptyList(),
    /** Number of hunks the diff viewer last reported for this file's current fingerprint. */
    val hunkCount: Int? = null,
    val hunkCountFingerprint: String? = null,
    val updatedAt: String,
)
