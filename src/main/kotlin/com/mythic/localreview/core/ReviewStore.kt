package com.mythic.localreview.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name

class ReviewCorruptException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * In-memory view + durable persistence of one review folder.
 *
 * Every mutation first persists (atomic write + read-back verification) and only then updates memory and notifies
 * listeners, so anything a caller observes as saved is on disk. Nothing is ever deleted: comment removal is a
 * tombstone (`deletedAt`). Corrupt files found on load are quarantined to `*.corrupt` and reported in [problems].
 */
class ReviewStore private constructor(
    val dir: Path,
    review: Review,
    private val committer: GitCommitter?,
    private val clock: () -> Instant,
) {
    private val lock = Any()
    private var review: Review = review
    private val comments = LinkedHashMap<String, Comment>()
    private val progress = LinkedHashMap<String, FileProgress>() // keyed by path
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val _problems = ArrayList<String>()
    val problems: List<String> get() = synchronized(lock) { _problems.toList() }

    /** Last git auto-commit failure, if any (data is still safely on disk). */
    val commitError: String? get() = committer?.lastError

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun fire() = listeners.forEach { it() }

    private fun now(): String = clock().toString()

    // ---- reads --------------------------------------------------------------------------------------------

    fun review(): Review = synchronized(lock) { review }

    fun comments(includeDeleted: Boolean = false): List<Comment> = synchronized(lock) {
        comments.values.filter { includeDeleted || !it.deleted }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    fun comment(id: String): Comment? = synchronized(lock) { comments[id] }

    fun progress(path: String): FileProgress? = synchronized(lock) { progress[path] }

    fun allProgress(): List<FileProgress> = synchronized(lock) { progress.values.toList() }

    // ---- review -------------------------------------------------------------------------------------------

    fun saveReview(updated: Review) {
        synchronized(lock) {
            val r = updated.copy(updatedAt = now())
            writeJson(REVIEW_FILE, StoreJson.encodeToString(Review.serializer(), r))
            review = r
        }
        committed("Update review metadata")
    }

    // ---- comments -----------------------------------------------------------------------------------------

    fun addComment(
        kind: CommentKind,
        body: String,
        anchor: Anchor,
        rule: String? = null,
    ): Comment {
        val t = now()
        val c = Comment(
            id = UUID.randomUUID().toString(), kind = kind, body = body, anchor = anchor, rule = rule,
            createdAt = t, updatedAt = t,
        )
        putComment(c, "Add ${kind.name.lowercase()} comment ${c.id.take(8)}")
        return c
    }

    /** Applies [change] to the comment, persists, and returns the result. Unknown id -> null. */
    fun updateComment(id: String, commitMessage: String = "Edit comment ${id.take(8)}", change: (Comment) -> Comment): Comment? {
        val updated = synchronized(lock) {
            val old = comments[id] ?: return null
            val new = change(old).copy(id = old.id, createdAt = old.createdAt, updatedAt = now())
            writeComment(new)
            comments[id] = new
            new
        }
        committed(commitMessage)
        return updated
    }

    /** Tombstones the comment (kept on disk, hidden in the UI, restorable). */
    fun deleteComment(id: String): Comment? {
        val t = now()
        return updateComment(id, "Delete comment ${id.take(8)}") { it.copy(deletedAt = t) }
    }

    fun restoreComment(id: String): Comment? =
        updateComment(id, "Restore comment ${id.take(8)}") { it.copy(deletedAt = null) }

    private fun putComment(c: Comment, msg: String) {
        synchronized(lock) {
            writeComment(c)
            comments[c.id] = c
        }
        committed(msg)
    }

    /** Re-anchors many comments in one go (used by Update review); each comment file is written individually. */
    fun replaceComments(updated: List<Comment>, message: String) {
        if (updated.isEmpty()) return
        synchronized(lock) {
            for (c in updated) {
                writeComment(c)
                comments[c.id] = c
            }
        }
        committed(message)
    }

    private fun writeComment(c: Comment) =
        writeJson("$COMMENTS_DIR/${c.id}.json", StoreJson.encodeToString(Comment.serializer(), c))

    // ---- progress -----------------------------------------------------------------------------------------

    /** Marks the file viewed at [fileFingerprint], or clears the mark. */
    fun setFileViewed(path: String, fileFingerprint: String, viewed: Boolean, allHunks: List<String>? = null) {
        mutateProgress(path, if (viewed) "Mark $path viewed" else "Unmark $path viewed") { p ->
            p.copy(
                viewedFingerprint = if (viewed) fileFingerprint else null,
                // Marking a whole file viewed marks every hunk; unmarking clears them.
                viewedHunks = when {
                    !viewed -> emptyList()
                    allHunks != null -> (p.viewedHunks + allHunks).distinct()
                    else -> p.viewedHunks
                },
            )
        }
    }

    /**
     * Records that the diff viewer shows [allHunks] for the file at [fileFingerprint] (so the tree can show n/m).
     * Persisted only if it changes something.
     */
    fun reportHunks(path: String, fileFingerprint: String, allHunks: Int) {
        val cur = progress(path)
        if (cur?.hunkCount == allHunks && cur.hunkCountFingerprint == fileFingerprint) return
        mutateProgress(path, "Record hunk count for $path") {
            it.copy(hunkCount = allHunks, hunkCountFingerprint = fileFingerprint)
        }
    }

    /**
     * Marks/unmarks one hunk. When, as a result, every hunk in [allHunks] is viewed the file itself becomes viewed;
     * when a hunk is un-viewed a viewed file mark is dropped.
     */
    fun setHunkViewed(path: String, fileFingerprint: String, hunk: String, viewed: Boolean, allHunks: List<String>) {
        mutateProgress(path, "${if (viewed) "Mark" else "Unmark"} hunk in $path") { p ->
            val hunks = if (viewed) (p.viewedHunks + hunk).distinct() else p.viewedHunks - hunk
            val allViewed = allHunks.isNotEmpty() && hunks.containsAll(allHunks)
            p.copy(
                viewedHunks = hunks,
                viewedFingerprint = if (allViewed) fileFingerprint else if (!viewed) null else p.viewedFingerprint,
                hunkCount = allHunks.size,
                hunkCountFingerprint = fileFingerprint,
            )
        }
    }

    private fun mutateProgress(path: String, msg: String, change: (FileProgress) -> FileProgress) {
        synchronized(lock) {
            val old = progress[path] ?: FileProgress(path = path, updatedAt = now())
            val new = change(old).copy(path = path, updatedAt = now())
            writeJson("$PROGRESS_DIR/${Fingerprints.pathKey(path)}.json", StoreJson.encodeToString(FileProgress.serializer(), new))
            progress[path] = new
        }
        committed(msg)
    }

    // ---- io -----------------------------------------------------------------------------------------------

    private fun writeJson(relative: String, json: String) {
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        val target = dir.resolve(relative)
        // Held against sync merges so a half-merged tree is never written into.
        if (committer != null) committer.exclusive { AtomicFiles.write(target, bytes) } else AtomicFiles.write(target, bytes)
    }

    private fun committed(message: String) {
        committer?.request(message)
        fire()
    }

    /** Forces any pending auto-commit to run now. */
    fun flush() { committer?.flush() }

    /** Records files written into the review folder outside the store's own mutations (e.g. exports) in git history. */
    fun commitExternalChanges(message: String) { committer?.request(message) }

    /** Flushes pending history. The committer is shared by the whole group and stays alive. */
    fun close() { committer?.flush() }

    private fun load() {
        loadDir(COMMENTS_DIR, Comment.serializer()) { comments[it.id] = it }
        loadDir(PROGRESS_DIR, FileProgress.serializer()) { progress[it.path] = it }
        // Sync conflicts keep the losing version next to the winner as `<file>.conflict-<device>-<time>`; surface them.
        for (d in listOf(dir.resolve(COMMENTS_DIR), dir.resolve(PROGRESS_DIR), dir)) {
            if (!d.exists()) continue
            Files.list(d).use { s ->
                s.filter { it.name.contains(".conflict-") }.sorted().forEach {
                    _problems += "Sync conflict: both versions were kept; the other one is ${it.name}"
                }
            }
        }
    }

    /** Re-reads everything from disk, e.g. after a sync pulled changes from another device. */
    fun reload() {
        synchronized(lock) {
            val file = dir.resolve(REVIEW_FILE)
            review = try {
                StoreJson.decodeFromString(Review.serializer(), Files.readString(file))
            } catch (e: Exception) {
                throw ReviewCorruptException("Cannot read $file: ${e.message}", e)
            }
            comments.clear()
            progress.clear()
            _problems.clear()
            load()
        }
        fire()
    }

    private fun <T> loadDir(sub: String, ser: kotlinx.serialization.KSerializer<T>, sink: (T) -> Unit) {
        val d = dir.resolve(sub)
        if (!d.exists()) return
        Files.list(d).use { s -> s.filter { it.extension == "json" }.sorted().forEach { f ->
            try {
                sink(StoreJson.decodeFromString(ser, Files.readString(f)))
            } catch (e: Exception) {
                quarantine(f, e)
            }
        } }
    }

    private fun quarantine(file: Path, cause: Exception) {
        var target = file.resolveSibling(file.name + ".corrupt")
        var n = 1
        while (target.exists()) target = file.resolveSibling(file.name + ".corrupt" + n++)
        try {
            Files.move(file, target)
            _problems += "Unreadable file ${file.name} was quarantined as ${target.name}: ${cause.message}"
        } catch (e: IOException) {
            _problems += "Unreadable file ${file.name} could not be quarantined: ${e.message} (${cause.message})"
        }
    }

    companion object {
        const val REVIEW_FILE = "review.json"
        const val COMMENTS_DIR = "comments"
        const val PROGRESS_DIR = "progress"

        /** Creates the review folder, initialises its git repo, and persists the first state. */
        fun create(dir: Path, review: Review, committer: GitCommitter? = null, clock: () -> Instant = Instant::now): ReviewStore {
            Files.createDirectories(dir)
            check(!dir.resolve(REVIEW_FILE).exists()) { "Review already exists at $dir" }
            committer?.ensureRepo()
            val store = ReviewStore(dir, review, committer, clock)
            store.writeJson(REVIEW_FILE, StoreJson.encodeToString(Review.serializer(), review))
            store.committed("Create review '${review.title}'")
            return store
        }

        fun open(dir: Path, committer: GitCommitter? = null, clock: () -> Instant = Instant::now): ReviewStore {
            val file = dir.resolve(REVIEW_FILE)
            val review = try {
                StoreJson.decodeFromString(Review.serializer(), Files.readString(file))
            } catch (e: Exception) {
                throw ReviewCorruptException("Cannot read $file: ${e.message}", e)
            }
            committer?.ensureRepo()
            return ReviewStore(dir, review, committer, clock).also { it.load() }
        }

        fun readReviewOrNull(dir: Path): Review? = try {
            StoreJson.decodeFromString(Review.serializer(), Files.readString(dir.resolve(REVIEW_FILE)))
        } catch (e: Exception) {
            null
        }
    }
}
