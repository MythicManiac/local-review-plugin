package com.mythic.localreview.store

import com.mythic.localreview.core.AtomicFiles
import com.mythic.localreview.core.GitCli
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.AnchorRange
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.CanonicalJson
import com.mythic.localreview.format.ChangeSet
import com.mythic.localreview.format.ChangedFile
import com.mythic.localreview.format.Event
import com.mythic.localreview.format.Field
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.Pin
import com.mythic.localreview.format.RULE_CANDIDATE_TAG
import com.mythic.localreview.format.RepoRef
import com.mythic.localreview.format.ReviewMeta
import com.mythic.localreview.format.ReviewMetaCodec
import com.mythic.localreview.format.ReviewMode
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.Snapshot
import com.mythic.localreview.format.Values
import com.mythic.localreview.format.MetaRead
import com.mythic.localreview.format.linesOfBlob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** The result of one import run over a group. */
data class ImportReport(
    val imported: List<Imported>,
    /** Reviews that could not be imported yet: the reviewed repository, or a commit, is not here. Tried again at the next run; nothing was written. */
    val notYet: List<NotImported>,
    /** Things in old files that could not be read; the files are left as they are. */
    val problems: List<String>,
)

data class Imported(val folder: String, val reviewId: String, val title: String, val eventsAppended: Int)
data class NotImported(val folder: String, val title: String, val reason: String)

/**
 * Imports reviews written by plugin 0.1.0 (a folder per review under a folder per repository, with `review.json` and one JSON file per comment and per file's progress) into this format
 * (CONFORMANCE, "Plugin 0.1.0 files → this format"). A function of the old files and of the git objects they name, so every device writes the
 * same `review.json` bytes and the same event ids and contents; only `ts` may differ, to keep the log increasing. Old files are never changed.
 *
 * @param stateDir device-local folder that remembers which old files were already imported, so reopening a group costs nothing when nothing changed
 */
class LegacyImporter(private val group: ReviewGroup, private val stateDir: Path? = null) {

    /** Imports every old review of the group whose reviewed repository is among [clones] and has all the commits the review names. */
    fun run(clones: List<GitCli>): ImportReport {
        val imported = ArrayList<Imported>()
        val notYet = ArrayList<NotImported>()
        val problems = ArrayList<String>()
        val identities = HashMap<GitCli, LocalRepo>()
        for (old in discover(problems)) {
            val sourceHash = old.sourceHash()
            val marker = marker(old)
            val newDir = group.reviewsDir.resolve(importReviewId(old.review.id))
            if (marker != null && Files.isRegularFile(marker) && Files.readString(marker) == sourceHash && newDir.resolve(ReviewGroup.REVIEW_FILE).exists()) continue
            val ref = RepoRef(old.review.repoName, old.review.remoteUrl, null)
            val clone = clones.firstOrNull { c -> identities.getOrPut(c) { LocalRepo.of(c) }.matches(ref) && old.review.allCommits().all { hasCommit(c, it) } }
            if (clone == null) {
                notYet += NotImported(old.dir.parent.name + "/" + old.dir.name, old.review.title, "the reviewed repository, or one of its commits, is not available on this machine")
                continue
            }
            try {
                val appended = import(old, clone, problems)
                if (appended != null) {
                    imported += Imported(old.dir.parent.name + "/" + old.dir.name, importReviewId(old.review.id), old.review.title, appended)
                    if (marker != null) AtomicFiles.write(marker, sourceHash.toByteArray())
                }
            } catch (e: Exception) {
                problems += "${old.dir.name}: ${e.message ?: e}"
            }
        }
        return ImportReport(imported, notYet, problems)
    }

    private fun marker(old: OldReview): Path? = stateDir?.resolve("import")?.resolve(sha256Hex("${group.dir.toAbsolutePath().normalize()}|${old.dir.parent.name}|${old.dir.name}").take(24) + ".txt")

    private fun hasCommit(git: GitCli, sha: String) = Values.isSha(sha) && try {
        git.run("cat-file", "-e", "$sha^{commit}")
        true
    } catch (_: Exception) {
        false
    }

    // ---- discovery ---------------------------------------------------------------------------------------------------

    private fun discover(problems: MutableList<String>): List<OldReview> {
        val out = ArrayList<OldReview>()
        if (!group.dir.isDirectory()) return out
        Files.list(group.dir).use { it.toList() }.filter { it.isDirectory() && !it.name.startsWith(".") && it.name != "reviews" }.sortedBy { it.name }.forEach { repoDir ->
            Files.list(repoDir).use { it.toList() }.filter { it.isDirectory() && it.resolve(ReviewGroup.REVIEW_FILE).exists() }.sortedBy { it.name }.forEach { d ->
                val bytes = Files.readAllBytes(d.resolve(ReviewGroup.REVIEW_FILE))
                val review = parseReview(bytes)
                if (review == null) {
                    if (!isOtherVersion(bytes)) problems += "${repoDir.name}/${d.name}: review.json cannot be read"
                    return@forEach
                }
                out += OldReview(d, review, bytes, files(d, "comments", problems), files(d, "progress", problems))
            }
        }
        return out
    }

    private fun isOtherVersion(bytes: ByteArray): Boolean =
        (parse(bytes)?.get("schemaVersion") as? JsonPrimitive)?.content?.toIntOrNull()?.let { it != 1 } ?: false

    private fun files(reviewDir: Path, sub: String, problems: MutableList<String>): List<Pair<String, ByteArray>> {
        val dir = reviewDir.resolve(sub)
        if (!dir.isDirectory()) return emptyList()
        return Files.list(dir).use { it.toList() }.filter { it.name.endsWith(".json") && Files.isRegularFile(it) }.sortedBy { it.name }.map { "$sub/${it.name}" to Files.readAllBytes(it) }
    }

    // ---- the import of one review -------------------------------------------------------------------------------------

    /** Writes the review's `review.json` (if absent) and appends the events not yet in `import-v1.jsonl`. Returns how many were appended, or null if skipped. */
    private fun import(old: OldReview, git: GitCli, problems: MutableList<String>): Int? {
        val id = importReviewId(old.review.id)
        val newDir = group.reviewsDir.resolve(id)
        val reviewFile = newDir.resolve(ReviewGroup.REVIEW_FILE)
        if (reviewFile.exists()) {
            val existing = parse(Files.readAllBytes(reviewFile))?.let { ReviewMetaCodec.decode(it) }
            if (existing !is MetaRead.Ok || existing.value.createdBy != IMPORT_DEVICE) {
                problems += "${old.dir.name}: reviews/$id already exists and was not imported from this review; not imported"
                return null
            }
        }
        val ctx = Context(git, old.review)
        val first = old.review.revisions.first()
        val meta = ReviewMeta(
            id, old.review.title, old.review.createdAt, IMPORT_DEVICE,
            RepoRef(old.review.repoName, old.review.remoteUrl, ChangeSets.rootCommit(git, first.headSha)),
            old.review.baseRef, old.review.headRef, if (old.review.mergeBase) ReviewMode.MERGE_BASE else ReviewMode.DIRECT, first.pin,
        )
        val planned = ArrayList<Item>()
        // Retargets: one per revision after the first, from review.json.
        for (rev in old.review.revisions.drop(1)) {
            planned += item("review.json", old.reviewBytes, Event.ReviewRetargeted(rev.pin), rev.at, RANK_RETARGET, rev.n.toString())
        }
        for ((rel, bytes) in old.comments) {
            val c = parseComment(bytes)
            if (c == null) {
                problems += "${old.dir.name}/$rel cannot be read"
                continue
            }
            val anchor = ctx.anchor(c.anchor)
            if (anchor == null) {
                problems += "${old.dir.name}/$rel has an anchor that cannot be converted"
                continue
            }
            planned += commentItems(rel, bytes, c, anchor)
        }
        val last = old.review.revisions.last()
        val lastSet = ctx.changeSet(last.pin)
        for ((rel, bytes) in old.progress) {
            val p = parseProgress(bytes)
            if (p == null) {
                problems += "${old.dir.name}/$rel cannot be read"
                continue
            }
            val file = lastSet.file(p.path)
            val event = if (file != null && p.viewedFingerprint != null && p.viewedFingerprint == fingerprint(file)) Event.FileReviewed(p.path, file.changeKey) else Event.FileUnreviewed(p.path)
            planned += item(rel, bytes, event, p.updatedAt, RANK_FILE, "")
        }
        planned.sortWith(compareBy<Item>({ it.ts }, { it.rank }, { it.id }))
        return group.committer.exclusive {
            if (!reviewFile.exists()) {
                AtomicFiles.write(reviewFile, CanonicalJson.write(ReviewMetaCodec.encode(meta)))
                Files.createDirectories(newDir.resolve("events"))
            }
            val appended = group.writer.appendPlanned(newDir.resolve("events"), IMPORT_DEVICE, planned.map { EventLogWriter.Planned(it.event, it.ts, it.id) }, skipIdsInLog = true)
            group.committer.request("Import review '${old.review.title}' from plugin 0.1.0")
            appended.size
        }
    }

    private class Item(val ts: Long, val rank: Int, val id: String, val event: Event)

    private fun item(rel: String, bytes: ByteArray, event: Event, ts: Long, rank: Int, part: String) = Item(ts, rank, importEventId(rel, bytes, event.type, part), event)

    private fun commentItems(rel: String, bytes: ByteArray, c: OldComment, anchor: Anchor): List<Item> {
        val tags = if (c.rule != null) listOf(RULE_CANDIDATE_TAG) else emptyList()
        val out = ArrayList<Item>()
        out += item(rel, bytes, Event.AnnotationCreated(c.id, c.kind, tags, c.body, anchor, c.rule), c.createdAt, RANK_CREATED, "")
        if (c.updatedAt > c.createdAt) out += item(rel, bytes, Event.AnnotationEdited(c.id, c.body, c.kind, tags, Field(c.rule)), c.updatedAt, RANK_EDITED, "")
        if (c.updatedAt > c.createdAt || c.status != AnnotationStatus.OPEN) out += item(rel, bytes, Event.AnnotationStatusChanged(c.id, c.status, c.resolutionNote), c.updatedAt, RANK_STATUS, "")
        if (c.deletedAt != null) out += item(rel, bytes, Event.AnnotationDeleted(c.id), c.deletedAt, RANK_DELETED, "")
        else if (c.updatedAt > c.createdAt) out += item(rel, bytes, Event.AnnotationRestored(c.id), c.updatedAt, RANK_RESTORED, "")
        return out
    }

    /** 0.1.0's fingerprint of a file's change: `sha1("STATUS|oldPath|newPath|oldBlob|newBlob")`, an absent path as the text `null`, an absent blob as empty text. */
    private fun fingerprint(f: ChangedFile): String {
        val status = when (f.status) {
            "added" -> "ADDED"
            "deleted" -> "DELETED"
            "renamed" -> "RENAMED"
            "copied" -> "COPIED"
            "typeChanged" -> "TYPE_CHANGED"
            else -> "MODIFIED"
        }
        val oldPath = if (status == "ADDED") null else f.oldPath ?: f.path
        val newPath = if (status == "DELETED") null else f.path
        fun blob(b: String) = if (b.all { it == '0' }) "" else b
        val text = "$status|$oldPath|$newPath|${blob(f.oldBlob)}|${blob(f.newBlob)}"
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** What the importer needs from the reviewed repository: the change set of each pin, and blob bytes. Read-only. */
    private class Context(val git: GitCli, val review: OldReviewData) {
        private val sets = HashMap<Pin, ChangeSet>()

        fun changeSet(pin: Pin): ChangeSet = sets.getOrPut(pin) {
            try {
                ChangeSets.read(git, pin)
            } catch (_: Exception) {
                ChangeSet(emptyList())
            }
        }

        private fun oldPathOf(f: ChangedFile) = if (f.status == "added") null else f.oldPath ?: f.path
        private fun newPathOf(f: ChangedFile) = if (f.status == "deleted") null else f.path

        private fun blob(id: String): ByteArray? = try {
            git.runBytes("cat-file", "blob", id)
        } catch (_: Exception) {
            null
        }

        /** The anchor of this format for an old one, or null if the old one is not valid (CONFORMANCE, the anchor table). */
        fun anchor(a: OldAnchor): Anchor? {
            if (a.scope == "REVIEW") return Anchor.Review
            val path = a.path ?: return null
            val rev = review.revisions.firstOrNull { it.n == a.revision } ?: review.revisions.last()
            val pin = rev.pin
            val side = if (a.side == "BASE") Side.OLD else Side.NEW
            val match = changeSet(pin).files.firstOrNull { f -> (if (side == Side.OLD) oldPathOf(f) else newPathOf(f)) == path }
            val identity = match?.path ?: path
            val oldPath = match?.oldPath
            if (a.scope == "FILE") return Anchor.File(identity, oldPath, pin)
            val start = a.startLine ?: return null
            val end = a.endLine ?: return null
            if (start < 1 || end < start) return null
            val bytes = if (match != null) match.blob(side)?.let(::blob) else git.showFile(if (side == Side.OLD) pin.baseSha else pin.headSha, path)
            val lines = bytes?.let(::linesOfBlob)
            // 0.1.0's viewer could show a line after a final newline, and a file may not be text: then the comment is about the file.
            if (lines == null || end > lines.size) return Anchor.File(identity, oldPath, pin)
            return Anchor.Lines(identity, oldPath, pin, listOf(AnchorRange(side, start, end, Snapshot(a.snippet, a.before, a.after))))
        }
    }

    companion object {
        /** The device that imported events are attributed to; one log that several devices may append to. */
        const val IMPORT_DEVICE = "import-v1"

        private const val RANK_RETARGET = 0
        private const val RANK_CREATED = 1
        private const val RANK_EDITED = 2
        private const val RANK_STATUS = 3
        private const val RANK_DELETED = 4
        private const val RANK_RESTORED = 5
        private const val RANK_FILE = 6

        /** The id of an old review in this format: kept if it fits the id rule, else made to (CONFORMANCE, "review id"). */
        fun importReviewId(old: String): String {
            if (Values.isId(old)) return old
            val sb = StringBuilder()
            old.codePoints().forEach { cp -> if (cp < 128 && (Character.isLetterOrDigit(cp) || cp == '_'.code || cp == '-'.code)) sb.appendCodePoint(cp) else sb.append('_') }
            val s = sb.toString()
            return if (s.length > 64) s.take(55) + "-" + sha256Hex(old).take(8) else s
        }

        /** `imp-` + the first 32 hex characters of SHA-256 over `<file path> NUL <file bytes> NUL <event type> NUL <part>`. */
        fun importEventId(relPath: String, fileBytes: ByteArray, type: String, part: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(relPath.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(fileBytes); md.update(0)
            md.update(type.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(part.toByteArray(Charsets.UTF_8))
            return "imp-" + md.digest().joinToString("") { "%02x".format(it) }.take(32)
        }

        private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        // ---- reading 0.1.0 files ------------------------------------------------------------------------------------

        private fun parse(bytes: ByteArray): JsonObject? = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }

        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { it.str() }.orEmpty()

        /** An ISO-8601 instant as milliseconds since the epoch, truncated (not rounded). */
        private fun ms(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        private fun parseReview(bytes: ByteArray): OldReviewData? {
            val o = parse(bytes) ?: return null
            if ((o["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull() != 1) return null
            val repo = o["repo"] as? JsonObject ?: return null
            val revisions = (o["revisions"] as? JsonArray)?.map { r ->
                val ro = r as? JsonObject ?: return null
                OldRevision(ro["n"].int() ?: return null, ms(ro["at"].str()) ?: return null, ro["baseSha"].str() ?: return null, ro["headSha"].str() ?: return null, ro["effectiveBaseSha"].str() ?: return null)
            }.orEmpty()
            if (revisions.isEmpty()) return null
            return OldReviewData(
                o["id"].str() ?: return null, o["title"].str() ?: return null, repo["name"].str() ?: return null, repo["remoteUrl"].str(),
                o["baseRef"].str() ?: return null, o["headRef"].str() ?: return null, (o["mergeBase"] as? JsonPrimitive)?.content != "false",
                revisions.sortedBy { it.n }, ms(o["createdAt"].str()) ?: return null,
            )
        }

        private fun parseComment(bytes: ByteArray): OldComment? {
            val o = parse(bytes) ?: return null
            val id = o["id"].str()?.takeIf { Values.isId(it) } ?: return null
            val kind = when (o["kind"].str() ?: "NOTE") {
                "CHANGE_REQUEST" -> Kind.CHANGE_REQUEST
                "QUESTION" -> Kind.QUESTION
                "NOTE" -> Kind.COMMENT
                else -> return null
            }
            val status = when (o["status"].str() ?: "OPEN") {
                "OPEN" -> AnnotationStatus.OPEN
                "RESOLVED" -> AnnotationStatus.RESOLVED
                "WONT_FIX" -> AnnotationStatus.WONT_FIX
                else -> return null
            }
            val a = o["anchor"] as? JsonObject
            val anchor = OldAnchor(
                a?.get("scope").str() ?: "REVIEW", a?.get("path").str(), a?.get("side").str() ?: "HEAD", a?.get("startLine").int(), a?.get("endLine").int(),
                a?.get("snippet").strings(), a?.get("before").strings(), a?.get("after").strings(), a?.get("revision").int() ?: 1,
            )
            val created = ms(o["createdAt"].str()) ?: return null
            return OldComment(id, kind, status, o["body"].str() ?: return null, anchor, o["rule"].str(), o["resolutionNote"].str(), created, ms(o["updatedAt"].str()) ?: created, ms(o["deletedAt"].str()))
        }

        private fun parseProgress(bytes: ByteArray): OldProgress? {
            val o = parse(bytes) ?: return null
            return OldProgress(o["path"].str()?.takeIf { it.isNotEmpty() } ?: return null, o["viewedFingerprint"].str(), ms(o["updatedAt"].str()) ?: return null)
        }
    }
}

private data class OldRevision(val n: Int, val at: Long, val baseSha: String, val headSha: String, val effectiveBaseSha: String) {
    /** 0.1.0's `baseSha` is what the base ref resolved to, `effectiveBaseSha` the left side of the diff (CONFORMANCE, `revisions[0]`). */
    val pin get() = Pin(effectiveBaseSha, baseSha, headSha)
}

private data class OldReviewData(
    val id: String,
    val title: String,
    val repoName: String,
    val remoteUrl: String?,
    val baseRef: String,
    val headRef: String,
    val mergeBase: Boolean,
    val revisions: List<OldRevision>,
    val createdAt: Long,
) {
    fun allCommits() = revisions.flatMap { listOf(it.baseSha, it.effectiveBaseSha, it.headSha) }
}

private class OldAnchor(
    val scope: String,
    val path: String?,
    val side: String,
    val startLine: Int?,
    val endLine: Int?,
    val snippet: List<String>,
    val before: List<String>,
    val after: List<String>,
    val revision: Int,
)

private class OldComment(
    val id: String,
    val kind: Kind,
    val status: AnnotationStatus,
    val body: String,
    val anchor: OldAnchor,
    val rule: String?,
    val resolutionNote: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

private class OldProgress(val path: String, val viewedFingerprint: String?, val updatedAt: Long)

private class OldReview(val dir: Path, val review: OldReviewData, val reviewBytes: ByteArray, val comments: List<Pair<String, ByteArray>>, val progress: List<Pair<String, ByteArray>>) {
    /** A hash of every old file of this review, to recognise that nothing changed since the last import. */
    fun sourceHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((name, bytes) in listOf("review.json" to reviewBytes) + comments + progress) {
            md.update(name.toByteArray(Charsets.UTF_8)); md.update(0); md.update(bytes); md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
