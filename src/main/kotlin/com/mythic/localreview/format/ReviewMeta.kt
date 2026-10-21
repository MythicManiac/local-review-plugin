package com.mythic.localreview.format

import kotlinx.serialization.json.JsonObject

/** The format version this implementation reads and writes (`schemaVersion` of `group.json` and `review.json`). */
const val SCHEMA_VERSION = 2L

enum class ReviewMode(val wire: String) {
    MERGE_BASE("mergeBase"),
    DIRECT("direct");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

/** What identifies the reviewed repository (SPEC 4.1). */
data class RepoRef(val displayName: String, val originUrl: String?, val rootCommit: String?)

/** `review.json` (SPEC 4): the facts of a review at creation. Title, status and pin are superseded by events (SPEC 7). */
data class ReviewMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val createdBy: String,
    val repo: RepoRef,
    val baseRef: String,
    val headRef: String,
    val mode: ReviewMode,
    val pin: Pin,
)

/** The outcome of reading a `review.json` or `group.json` (SPEC 2.2, 4). */
sealed interface MetaRead<out T> {
    data class Ok<T>(val value: T) : MetaRead<T>

    /** `schemaVersion` below 2: an older tool's layout. Reported, skipped, never modified. */
    data class Older(val version: Long?) : MetaRead<Nothing>

    /** `schemaVersion` above 2: a newer format wrote it. Refused, never modified. */
    data class Newer(val version: Long) : MetaRead<Nothing>

    data class Invalid(val reason: String) : MetaRead<Nothing>
}

private fun JsonObject.schemaCheck(): MetaRead<Nothing>? {
    val v = this["schemaVersion"].long() ?: return MetaRead.Older(null)
    return when {
        v < SCHEMA_VERSION -> MetaRead.Older(v)
        v > SCHEMA_VERSION -> MetaRead.Newer(v)
        else -> null
    }
}

object ReviewMetaCodec {
    fun decode(o: JsonObject): MetaRead<ReviewMeta> {
        o.schemaCheck()?.let { return it }
        val id = o["id"].string()?.takeIf { Values.isId(it) } ?: return MetaRead.Invalid("id is missing or not an id")
        val title = o["title"].string() ?: return MetaRead.Invalid("title is missing")
        val createdAt = o["createdAt"].long()?.takeIf { it >= 0 } ?: return MetaRead.Invalid("createdAt is missing")
        val createdBy = o["createdBy"].string()?.takeIf { Values.isId(it) } ?: return MetaRead.Invalid("createdBy is missing or not an id")
        val repo = o["repo"].obj() ?: return MetaRead.Invalid("repo is missing")
        val displayName = repo["displayName"].string()?.takeIf { it.isNotEmpty() } ?: return MetaRead.Invalid("repo.displayName is missing")
        val root = repo["rootCommit"].string()
        if (root != null && !Values.isSha(root)) return MetaRead.Invalid("repo.rootCommit is not a commit id")
        val baseRef = o["baseRef"].string()?.takeIf { it.isNotEmpty() } ?: return MetaRead.Invalid("baseRef is missing")
        val headRef = o["headRef"].string()?.takeIf { it.isNotEmpty() } ?: return MetaRead.Invalid("headRef is missing")
        val mode = ReviewMode.fromWire(o["mode"].string()) ?: return MetaRead.Invalid("mode is missing or unknown")
        val pin = EventCodec.decodePin(o["pin"]) ?: return MetaRead.Invalid("pin is missing or invalid")
        return MetaRead.Ok(ReviewMeta(id, title, createdAt, createdBy, RepoRef(displayName, repo["originUrl"].string(), root), baseRef, headRef, mode, pin))
    }
}

/** `group.json` (SPEC 4). `createdAt` is kept raw: an older tool wrote an ISO-8601 string (SPEC 9.3). */
data class GroupMeta(val id: String, val name: String?, val createdAt: Long)

object GroupMetaCodec {
    fun decode(o: JsonObject): MetaRead<GroupMeta> {
        o.schemaCheck()?.let { return it }
        val id = o["id"].string()?.takeIf { Values.isId(it) } ?: return MetaRead.Invalid("id is missing or not an id")
        val createdAt = o["createdAt"].long()?.takeIf { it >= 0 } ?: return MetaRead.Invalid("createdAt is missing")
        return MetaRead.Ok(GroupMeta(id, o["name"].string(), createdAt))
    }
}
