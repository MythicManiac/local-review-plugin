package com.mythic.localreview.store

import com.mythic.localreview.core.AtomicFiles
import com.mythic.localreview.core.GitCommitter
import com.mythic.localreview.format.CanonicalJson
import com.mythic.localreview.format.Event
import com.mythic.localreview.format.Fold
import com.mythic.localreview.format.GroupMeta
import com.mythic.localreview.format.GroupMetaCodec
import com.mythic.localreview.format.Logs
import com.mythic.localreview.format.MetaRead
import com.mythic.localreview.format.ReviewMeta
import com.mythic.localreview.format.ReviewMetaCodec
import com.mythic.localreview.format.ReviewState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** A review of a group as listed: its immutable facts and its folded state. */
data class ReviewSummary(val group: String, val dir: Path, val meta: ReviewMeta, val state: ReviewState) {
    val id get() = meta.id
    val title get() = state.header.title
    val status get() = state.header.status
}

/** A folder under `reviews/` that is not a readable version 2 review. Reported to the user, never modified (SPEC 2.2). */
data class SkippedReview(val folder: String, val reason: String)

data class GroupReviews(val reviews: List<ReviewSummary>, val skipped: List<SkippedReview>)

/** What opening a group did and found. */
data class PrepareReport(
    /** The group's repository files were created or completed and committed. */
    val repoFilesChanged: Boolean,
    /** Required repository-file rules git still does not honour (SPEC 2.1, step 3). Show the user. */
    val notHonoured: List<String>,
    /** The group could not be used (`group.json` written by a newer version of the format, or unreadable). */
    val refused: String?,
)

/**
 * One review group on disk: a git repository holding `group.json`, the repository files of SPEC 2.1 and `reviews/<id>/`. All access to one group
 * in a process goes through one instance, so commits, sync merges and event appends never interleave (see [GitCommitter.exclusive]).
 */
class ReviewGroup internal constructor(
    val name: String,
    val dir: Path,
    val committer: GitCommitter,
    internal val device: DeviceIdentity,
    internal val writer: EventLogWriter,
    private val clock: () -> Long,
) {
    val reviewsDir: Path get() = dir.resolve("reviews")

    val deviceId: String get() = device.id()

    /**
     * Opens the group (SPEC 2.1): creates or completes the repository files, converts an older `group.json` to this format, and commits what it
     * changed. Idempotent. Run it when a group is first used in a session and after every sync that pulled.
     */
    fun prepare(commitMessage: String = "Open group"): PrepareReport {
        var refused: String? = null
        val report = committer.exclusive {
            committer.ensureRepo()
            val groupFile = dir.resolve(GROUP_FILE)
            var changed = false
            if (groupFile.exists()) {
                when (val read = readGroupFile(groupFile)) {
                    is GroupRead.Newer -> refused = "This group was written by a newer version of the review format (${read.version}); update the plugin."
                    is GroupRead.Legacy -> {
                        AtomicFiles.write(groupFile, CanonicalJson.write(GroupMetaCodec.encode(read.converted)))
                        changed = true
                    }
                    is GroupRead.Unreadable -> refused = "group.json cannot be read: ${read.reason}"
                    is GroupRead.Current -> Unit
                }
            } else {
                AtomicFiles.write(groupFile, CanonicalJson.write(GroupMetaCodec.encode(GroupMeta(com.mythic.localreview.format.Ulid.next(), null, clock()))))
                changed = true
            }
            if (refused != null) return@exclusive PrepareReport(false, emptyList(), refused)
            val files = GroupFiles.ensure(dir, committer.git)
            if (changed || files.changed) committer.commitAllLocked(commitMessage)
            PrepareReport(changed || files.changed, files.notHonoured, null)
        }
        return report
    }

    private sealed interface GroupRead {
        data object Current : GroupRead
        data class Legacy(val converted: GroupMeta) : GroupRead
        data class Newer(val version: Long) : GroupRead
        data class Unreadable(val reason: String) : GroupRead
    }

    /** Reads `group.json`: version 2, an older one that [prepare] converts (0.1.0 wrote `schemaVersion: 1` with an ISO-8601 `createdAt`), or neither. */
    private fun readGroupFile(file: Path): GroupRead {
        val o = try {
            Json.parseToJsonElement(Files.readString(file)) as? JsonObject ?: return GroupRead.Unreadable("not a JSON object")
        } catch (e: Exception) {
            return GroupRead.Unreadable(e.message ?: "invalid JSON")
        }
        return when (val r = GroupMetaCodec.decode(o)) {
            is MetaRead.Ok -> GroupRead.Current
            is MetaRead.Newer -> GroupRead.Newer(r.version)
            is MetaRead.Invalid -> GroupRead.Unreadable(r.reason)
            is MetaRead.Older -> {
                val id = (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                val iso = (o["createdAt"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                val ms = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                if (id == null || !com.mythic.localreview.format.Values.isId(id) || ms == null) GroupRead.Unreadable("an older group.json that cannot be converted")
                else GroupRead.Legacy(GroupMeta(id, null, ms))
            }
        }
    }

    /** The reviews of this group, newest first, and the folders skipped with the reason. */
    fun reviews(): GroupReviews {
        val ok = ArrayList<ReviewSummary>()
        val skipped = ArrayList<SkippedReview>()
        if (reviewsDir.isDirectory()) {
            Files.list(reviewsDir).use { it.toList() }.filter { it.isDirectory() && !it.name.startsWith(".") }.sortedBy { it.name }.forEach { d ->
                when (val r = readMeta(d)) {
                    is MetaRead.Ok -> {
                        if (r.value.id != d.name) skipped += SkippedReview(d.name, "the folder name differs from the review id '${r.value.id}'")
                        else ok += ReviewSummary(name, d, r.value, Fold.fold(r.value, Logs.readDir(d)))
                    }
                    is MetaRead.Older -> skipped += SkippedReview(d.name, "written by an older version of the plugin (0.1.0 layout)")
                    is MetaRead.Newer -> skipped += SkippedReview(d.name, "written by a newer version of the format (${r.version})")
                    is MetaRead.Invalid -> skipped += SkippedReview(d.name, "review.json is invalid: ${r.reason}")
                }
            }
        }
        return GroupReviews(ok.sortedByDescending { it.meta.createdAt }, skipped)
    }

    private fun readMeta(reviewDir: Path): MetaRead<ReviewMeta> {
        val f = reviewDir.resolve(REVIEW_FILE)
        if (!f.exists()) return MetaRead.Invalid("review.json is missing")
        val o = try {
            Json.parseToJsonElement(Files.readString(f)) as? JsonObject ?: return MetaRead.Invalid("not a JSON object")
        } catch (e: Exception) {
            return MetaRead.Invalid(e.message ?: "invalid JSON")
        }
        return ReviewMetaCodec.decode(o)
    }

    /** Opens a review of this group, or null if it is missing or is not a readable version 2 review. */
    fun open(id: String): OpenReview? {
        val d = reviewsDir.resolve(id)
        val meta = (readMeta(d) as? MetaRead.Ok)?.value ?: return null
        return if (meta.id == id) OpenReview(this, d, meta) else null
    }

    /**
     * Creates `reviews/<id>/review.json` from [meta] (canonical, durable) and commits it.
     * @throws IllegalStateException if a review with that id already exists.
     */
    fun create(meta: ReviewMeta): OpenReview = committer.exclusive {
        val d = reviewsDir.resolve(meta.id)
        check(!d.resolve(REVIEW_FILE).exists()) { "A review with id ${meta.id} already exists in group $name." }
        AtomicFiles.write(d.resolve(REVIEW_FILE), CanonicalJson.write(ReviewMetaCodec.encode(meta)))
        Files.createDirectories(d.resolve("events"))
        committer.request("Create review: ${meta.title}")
        OpenReview(this, d, meta)
    }

    companion object {
        const val GROUP_FILE = "group.json"
        const val REVIEW_FILE = "review.json"
    }
}

/** One review of a group, open for reading and writing. [state] is the fold of every log, as of the last [refresh]. */
class OpenReview internal constructor(val group: ReviewGroup, val dir: Path, val meta: ReviewMeta) {
    val id: String get() = meta.id
    val eventsDir: Path get() = dir.resolve("events")

    @Volatile
    var state: ReviewState = fold()
        private set

    private fun fold(): ReviewState = Fold.fold(meta, Logs.readDir(dir))

    /** Re-reads every log (another device's events arrived by sync, or another IDE appended) and returns the new state. */
    fun refresh(): ReviewState = fold().also { state = it }

    /** A number that changes whenever any log of this review changes on disk: poll it to learn that [refresh] is worth calling. */
    fun stamp(): Long {
        val files = eventsDir.toFile().listFiles { f -> f.name.endsWith(".jsonl") } ?: return 0
        return files.fold(17L) { acc, f -> acc * 31 + f.length() * 7 + f.lastModified() }
    }

    /**
     * Appends [events] to this device's log in one critical section (SPEC 5.2) and returns when they are on disk; then refreshes [state] and
     * schedules a commit. Throws [LogBusyException] if another instance of this device holds the lock; nothing is written then.
     */
    fun append(events: List<Event>, commitMessage: String): List<Appended> {
        val written = group.committer.exclusive { group.writer.append(eventsDir, group.device.id(), events) }
        refresh()
        group.committer.request(commitMessage)
        return written
    }

    fun append(event: Event, commitMessage: String): Appended = append(listOf(event), commitMessage).single()
}
