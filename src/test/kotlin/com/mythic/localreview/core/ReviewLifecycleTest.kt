package com.mythic.localreview.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewLifecycleTest {
    private fun body(n: Int) = lines(*(1..n).map { "line $it" }.toTypedArray())

    private fun setup(): Triple<TestRepo, ReviewRepository, ReviewStore> {
        val repo = TestRepo()
        repo.write("src/a.txt", body(10))
        repo.write("src/gone.txt", "bye\n")
        repo.write("src/old.txt", body(8))
        repo.commit("base")
        repo.branch("feature")
        repo.write("src/a.txt", body(10).replace("line 5\n", "line 5 changed\n"))
        repo.write("src/new.txt", "hello\n")
        repo.delete("src/gone.txt")
        repo.mv("src/old.txt", "moved/old.txt")
        repo.commit("feature work")
        val repository = ReviewRepository(tempDir())
        val store = repository.create(repo.git, "Feature A vs master", "master", "feature")
        return Triple(repo, repository, store)
    }

    @Test
    fun createPinsShasAndDiffsPrStyle() {
        val (repo, _, store) = setup()
        val rev = store.review().current
        assertEquals(repo.git.resolveCommit("feature"), rev.headSha)
        assertEquals(repo.git.resolveCommit("master"), rev.effectiveBaseSha)

        val changes = repo.git.diffRaw(rev.effectiveBaseSha, rev.headSha).associateBy { it.path }
        assertEquals(ChangeStatus.MODIFIED, changes["src/a.txt"]!!.status)
        assertEquals(ChangeStatus.ADDED, changes["src/new.txt"]!!.status)
        assertEquals(ChangeStatus.DELETED, changes["src/gone.txt"]!!.status)
        val moved = changes["moved/old.txt"]!!
        assertEquals(ChangeStatus.RENAMED, moved.status)
        assertEquals("src/old.txt", moved.oldPath)
    }

    @Test
    fun mergeBaseIgnoresLaterBaseCommits() {
        val (repo, repository, _) = setup()
        val baseTip = run {
            repo.checkout("master")
            repo.write("unrelated.txt", "x\n")
            repo.commit("master moved on")
        }
        val store = repository.create(repo.git, "mb", "master", "feature", mergeBase = true)
        val rev = store.review().current
        assertEquals(baseTip, rev.baseSha)
        assertNotEquals(baseTip, rev.effectiveBaseSha)
        val paths = repo.git.diffRaw(rev.effectiveBaseSha, rev.headSha).map { it.path }
        assertFalse("unrelated.txt" in paths, "master's later commits are not part of the review")
    }

    @Test
    fun fingerprintChangesWithContent() {
        val (repo, _, store) = setup()
        val rev = store.review().current
        val before = repo.git.diffRaw(rev.effectiveBaseSha, rev.headSha).first { it.path == "src/a.txt" }.fingerprint
        repo.write("src/a.txt", body(10).replace("line 5\n", "line 5 changed again\n"))
        repo.commit("more")
        val after = repo.git.diffRaw(rev.effectiveBaseSha, repo.git.resolveCommit("feature")).first { it.path == "src/a.txt" }.fingerprint
        assertNotEquals(before, after)
    }

    @Test
    fun updateReanchorsRenamesAndMarksOutdated() {
        val (repo, _, store) = setup()
        val head1 = store.review().current.headSha
        fun anchor(path: String, from: Int, to: Int, side: Side = Side.HEAD): Anchor {
            val sha = if (side == Side.HEAD) head1 else store.review().current.effectiveBaseSha
            val lines = Anchoring.splitLines(String(repo.git.showFile(sha, path)!!))
            val c = Anchoring.capture(lines, from, to)
            return Anchor(AnchorScope.LINES, path, side, from, to, c.snippet, c.before, c.after, 1)
        }
        val shifts = store.addComment(CommentKind.NOTE, "shifts", anchor("src/a.txt", 8, 8))
        val edited = store.addComment(CommentKind.CHANGE_REQUEST, "edited", anchor("src/a.txt", 5, 5))
        val renamed = store.addComment(CommentKind.QUESTION, "renamed", anchor("moved/old.txt", 3, 3))
        val untouched = store.addComment(CommentKind.NOTE, "untouched", anchor("src/new.txt", 1, 1))
        val deletedFile = store.addComment(CommentKind.NOTE, "base side", anchor("src/gone.txt", 1, 1, Side.BASE))
        val review = store.addComment(CommentKind.NOTE, "overall", Anchor(AnchorScope.REVIEW))

        // Agent continues: insert 2 lines at the top of a.txt, rewrite line 5, tweak the renamed file's header, delete new.txt? (no)
        repo.write("src/a.txt", lines("inserted 1", "inserted 2") + body(10).replace("line 5\n", "line 5 REWRITTEN\n"))
        repo.write("moved/old.txt", "new header\n" + body(8))
        val head2 = repo.commit("iterate")

        val result = ReviewUpdater.update(store, repo.git)
        assertEquals(head2, result.newRevision!!.headSha)
        assertEquals(2, store.review().current.n)

        fun c(id: String) = store.comment(id)!!
        assertEquals(10, c(shifts.id).anchor.startLine, "inserted 2 lines above")
        assertEquals(2, c(shifts.id).anchor.revision)
        assertFalse(c(shifts.id).outdated)

        assertTrue(c(edited.id).outdated, "snippet was rewritten")
        assertEquals(1, c(edited.id).anchor.revision, "outdated comments keep their original anchor")
        assertEquals(5, c(edited.id).anchor.startLine)

        assertEquals(4, c(renamed.id).anchor.startLine)
        assertFalse(c(renamed.id).outdated)

        assertEquals(1, c(untouched.id).anchor.startLine)
        assertEquals(2, c(untouched.id).anchor.revision, "unchanged file is retargeted, not outdated")

        // Base side: effective base did not move, file still absent on head but that is irrelevant to a base anchor.
        assertFalse(c(deletedFile.id).outdated)
        assertEquals(AnchorScope.REVIEW, c(review.id).anchor.scope)

        assertEquals(1, result.outdated)

        // Persisted: reopening shows same state.
        val reopened = ReviewStore.open(store.dir)
        assertEquals(2, reopened.review().revisions.size)
        assertTrue(reopened.comment(edited.id)!!.outdated)
        assertEquals(10, reopened.comment(shifts.id)!!.anchor.startLine)
    }

    @Test
    fun updateIsNoopWhenNothingMoved() {
        val (repo, _, store) = setup()
        assertNull(ReviewUpdater.update(store, repo.git).newRevision)
        assertEquals(1, store.review().revisions.size)
    }

    @Test
    fun listFindsReviewsByRepo() {
        val (repo, repository, store) = setup()
        val found = repository.list(store.review().repo)
        assertEquals(listOf(store.review().id), found.map { it.review.id })
        assertTrue(repository.list(RepoInfo("other-repo")).isEmpty())
    }

    @Test
    fun deletedHeadFileOutdatesHeadComment() {
        val (repo, _, store) = setup()
        val head1 = store.review().current.headSha
        val lines = Anchoring.splitLines(String(repo.git.showFile(head1, "src/new.txt")!!))
        val c = Anchoring.capture(lines, 1, 1)
        val cm = store.addComment(CommentKind.NOTE, "x", Anchor(AnchorScope.LINES, "src/new.txt", Side.HEAD, 1, 1, c.snippet, c.before, c.after, 1))
        repo.delete("src/new.txt")
        repo.commit("remove")
        ReviewUpdater.update(store, repo.git)
        assertTrue(store.comment(cm.id)!!.outdated)
    }

    @Test
    fun lastOpenedPointerSurvivesAndIgnoresMissingReviews() {
        val (_, repository, store) = setup()
        assertNull(repository.lastOpened("proj"))
        repository.rememberLastOpened("proj", store.dir)
        assertEquals(store.dir, repository.lastOpened("proj"))
        assertNull(repository.lastOpened("other-project"))
        repository.rememberLastOpened("proj", null)
        assertNull(repository.lastOpened("proj"), "explicit close clears the pointer")
        repository.rememberLastOpened("proj", store.dir.resolve("gone"))
        assertNull(repository.lastOpened("proj"), "pointer to a missing review is ignored")
        // The pointer folder must not be mistaken for a repo of reviews.
        assertEquals(1, repository.list(store.review().repo).size)
    }
}
