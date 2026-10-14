package com.mythic.localreview.core

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewStoreTest {
    private fun review() = Review(
        id = "r1", title = "T", repo = RepoInfo("repo"), baseRef = "master", headRef = "feature",
        revisions = listOf(Revision(1, "2026-01-01T00:00:00Z", "b", "h", "b")),
        createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun lineAnchor(path: String = "a.kt", start: Int = 3, end: Int = 4) =
        Anchor(AnchorScope.LINES, path, Side.HEAD, start, end, listOf("x", "y"))

    @Test
    fun commentSurvivesReopen() {
        val dir = tempDir().resolve("rev")
        val s = ReviewStore.create(dir, review())
        val c = s.addComment(CommentKind.CHANGE_REQUEST, "please rename", lineAnchor(), rule = "Prefer descriptive names")
        assertTrue(dir.resolve("comments/${c.id}.json").exists())

        val reopened = ReviewStore.open(dir)
        val back = reopened.comment(c.id)
        assertNotNull(back)
        assertEquals("please rename", back.body)
        assertEquals(CommentKind.CHANGE_REQUEST, back.kind)
        assertEquals("Prefer descriptive names", back.rule)
        assertEquals(lineAnchor(), back.anchor)
        assertTrue(reopened.problems.isEmpty())
    }

    @Test
    fun deleteIsTombstoneAndRestorable() {
        val dir = tempDir().resolve("rev")
        val s = ReviewStore.create(dir, review())
        val c = s.addComment(CommentKind.NOTE, "hmm", lineAnchor())
        s.deleteComment(c.id)

        assertTrue(s.comments().isEmpty(), "hidden from normal listing")
        assertEquals(1, s.comments(includeDeleted = true).size)
        assertTrue(dir.resolve("comments/${c.id}.json").exists(), "file must never be removed")
        // Persisted tombstone survives reopen.
        assertTrue(ReviewStore.open(dir).comment(c.id)!!.deleted)

        s.restoreComment(c.id)
        assertEquals(1, s.comments().size)
        assertNull(ReviewStore.open(dir).comment(c.id)!!.deletedAt)
    }

    @Test
    fun corruptFileIsQuarantinedNotDropped() {
        val dir = tempDir().resolve("rev")
        val s = ReviewStore.create(dir, review())
        val good = s.addComment(CommentKind.NOTE, "good", lineAnchor())
        val bad = dir.resolve("comments/bad.json")
        Files.writeString(bad, "{ not json")

        val reopened = ReviewStore.open(dir)
        assertEquals(listOf(good.id), reopened.comments().map { it.id })
        assertEquals(1, reopened.problems.size)
        assertFalse(bad.exists())
        assertEquals("{ not json", Files.readString(dir.resolve("comments/bad.json.corrupt")))
    }

    @Test
    fun unknownKeysAndEnumValuesAreTolerated() {
        val dir = tempDir().resolve("rev")
        val s = ReviewStore.create(dir, review())
        val c = s.addComment(CommentKind.QUESTION, "q", lineAnchor())
        val f = dir.resolve("comments/${c.id}.json")
        val tweaked = Files.readString(f)
            .replace("\"QUESTION\"", "\"FUTURE_KIND\"")
            .replaceFirst("{", "{\n  \"someFutureField\": 42,")
        Files.writeString(f, tweaked)

        val back = ReviewStore.open(dir).comment(c.id)
        assertNotNull(back)
        assertEquals(CommentKind.NOTE, back.kind, "unknown enum coerces to the default")
    }

    @Test
    fun noTempFilesLeftBehind() {
        val dir = tempDir().resolve("rev")
        val s = ReviewStore.create(dir, review())
        repeat(5) { s.addComment(CommentKind.NOTE, "n$it", lineAnchor()) }
        assertTrue(dir.resolve("comments").listDirectoryEntries().none { it.fileName.toString().endsWith(".tmp") })
    }

    @Test
    fun editKeepsIdAndCreatedAt() {
        val dir = tempDir().resolve("rev")
        var t = 0
        val s = ReviewStore.create(dir, review(), clock = { java.time.Instant.ofEpochSecond(1000L + t++) })
        val c = s.addComment(CommentKind.NOTE, "old", lineAnchor())
        val e = s.updateComment(c.id) { it.copy(body = "new", status = CommentStatus.RESOLVED, id = "hijack", createdAt = "x") }!!
        assertEquals(c.id, e.id)
        assertEquals(c.createdAt, e.createdAt)
        assertTrue(e.updatedAt > c.updatedAt)
        assertEquals("new", ReviewStore.open(dir).comment(c.id)!!.body)
    }

    @Test
    fun hunkAndFileViewedSemantics() {
        val dir = tempDir().resolve("rev")
        val s = ReviewStore.create(dir, review())
        val all = listOf("h1", "h2")

        s.setHunkViewed("a.kt", "fp1", "h1", true, all)
        assertNull(s.progress("a.kt")!!.viewedFingerprint, "one of two hunks does not make the file viewed")

        s.setHunkViewed("a.kt", "fp1", "h2", true, all)
        assertEquals("fp1", s.progress("a.kt")!!.viewedFingerprint, "all hunks viewed -> file viewed")

        s.setHunkViewed("a.kt", "fp1", "h1", false, all)
        assertNull(s.progress("a.kt")!!.viewedFingerprint, "unviewing a hunk drops the file mark")
        assertEquals(listOf("h2"), s.progress("a.kt")!!.viewedHunks)

        s.setFileViewed("a.kt", "fp1", true, all)
        assertEquals(all.toSet(), s.progress("a.kt")!!.viewedHunks.toSet())

        val back = ReviewStore.open(dir).progress("a.kt")!!
        assertEquals("fp1", back.viewedFingerprint)
        assertEquals(2, back.hunkCount)
    }

    @Test
    fun autoCommitRecordsHistory() {
        val dir = tempDir().resolve("rev")
        val committer = GitCommitter(dir, debounceMs = 10)
        val s = ReviewStore.create(dir, review(), committer)
        val c = s.addComment(CommentKind.NOTE, "hi", lineAnchor())
        s.deleteComment(c.id)
        s.flush()
        assertNull(s.commitError)

        val git = GitCli(dir)
        val log = git.run("log", "--format=%s").lines().filter { it.isNotBlank() }
        assertTrue(log.isNotEmpty())
        assertEquals("", git.run("status", "--porcelain").trim(), "everything committed")
        // The comment (including its tombstone) is in history.
        val tracked = git.run("ls-files").lines()
        assertTrue(tracked.contains("comments/${c.id}.json"))
        assertTrue(git.run("show", "HEAD:comments/${c.id}.json").contains("deletedAt"))
        committer.close()
    }
}
