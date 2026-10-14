package com.mythic.localreview.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupsTest {
    private fun lineAnchor() = Anchor(AnchorScope.LINES, "a.txt", Side.HEAD, 1, 1, listOf("x"))

    /** A project repo with a feature branch, as a source for reviews. */
    private fun project(name: String = "proj"): TestRepo {
        val parent = Files.createTempDirectory("lr-proj")
        val repo = TestRepo(parent.resolve(name).also { Files.createDirectories(it) })
        repo.write("a.txt", "one\ntwo\n")
        repo.commit("base")
        repo.branch("feature")
        repo.write("a.txt", "one\ntwo\nthree\n")
        repo.commit("work")
        return repo
    }

    private fun review(groups: ReviewGroups, group: String, repo: TestRepo, title: String = "R"): ReviewStore =
        groups.repository(groups.group(group)!!).create(repo.git, title, "master", "feature")

    private fun clockAt(sec: Long): () -> Instant = { Instant.ofEpochSecond(1_800_000_000L + sec) }

    // ---- grouping & isolation ----------------------------------------------------------------------------------

    @Test
    fun oneGroupHoldsManyProjectsAndGroupsAreIsolated() {
        val groups = ReviewGroups(tempDir())
        groups.create("work")
        groups.create("oss")
        val p1 = project("alpha")
        val p2 = project("beta")

        val a1 = review(groups, "work", p1, "A1")
        val b1 = review(groups, "work", p2, "B1")
        val a2 = review(groups, "oss", p1, "A2")

        val work = groups.group("work")!!
        val oss = groups.group("oss")!!
        assertEquals(setOf("A1", "B1"), groups.repository(work).list(null).map { it.review.title }.toSet())
        assertEquals(listOf("A2"), groups.repository(oss).list(null).map { it.review.title })

        // One repo per group: the project folders live inside the group repo, and groups share no history.
        assertTrue(a1.dir.startsWith(work.dir) && b1.dir.startsWith(work.dir) && a2.dir.startsWith(oss.dir))
        assertEquals(work.dir, a1.dir.parent.parent)
        a1.flush(); a2.flush()
        val workFiles = GitCli(work.dir).run("ls-files")
        assertTrue(workFiles.contains("alpha/") && workFiles.contains("beta/") && !workFiles.contains("A2"))
        assertFalse(GitCli(oss.dir).run("ls-files").contains("beta/"))

        // Looking up the reviews of a project spans groups, tagged by group.
        val both = groups.listReviews(a1.review().repo)
        assertEquals(setOf("work" to "A1", "oss" to "A2"), both.map { it.group to it.review.title }.toSet())
        assertEquals("work", groups.groupOf(a1.dir))
    }

    @Test
    fun groupNamesAreValidatedAndUnique() {
        val groups = ReviewGroups(tempDir())
        groups.create("Acme Corp")
        assertFailsWith<IllegalArgumentException> { groups.create("acme corp") }
        for (bad in listOf("", ".hidden", "a/b", "..", "trailing.", "x".repeat(65))) {
            assertFailsWith<IllegalArgumentException>("'$bad' must be rejected") { groups.create(bad) }
        }
    }

    @Test
    fun repoGroupPreferenceAndLastOpenedPointersStayOutsideGroups() {
        val root = tempDir()
        val groups = ReviewGroups(root)
        groups.create("work")
        val repo = RepoInfo("proj", "git@example.com:org/proj.git")
        assertNull(groups.groupFor(repo))
        groups.rememberGroup(repo, "work")
        assertEquals("work", groups.groupFor(repo))
        // Same project identified through a differently spelled remote URL.
        assertEquals("work", groups.groupFor(RepoInfo("renamed-checkout", "git@example.com:org/proj")))

        val store = review(groups, "work", project())
        groups.rememberLastOpened("p", store.dir)
        assertEquals(store.dir, groups.lastOpened("p"))
        groups.rememberLastOpened("p", null)
        assertNull(groups.lastOpened("p"))
        groups.rememberLastOpened("p", store.dir.resolve("gone"))
        assertNull(groups.lastOpened("p"), "pointer to a missing review is ignored")

        store.flush()
        assertTrue(GitCli(groups.group("work")!!.dir).run("ls-files").lines().none { it.startsWith(".state") }, "per-machine state is never synced")
        assertEquals(listOf("work"), groups.list().map { it.name }, ".state is not a group")
    }

    // ---- sync ----------------------------------------------------------------------------------------------------

    @Test
    fun syncPublishesToRemoteAndSecondDeviceReceivesEverything() {
        val remote = bareRemote()
        val dev1 = ReviewGroups(tempDir())
        dev1.create("work", remote.toString())
        val store1 = review(dev1, "work", project())
        val c = store1.addComment(CommentKind.QUESTION, "why?", lineAnchor())
        store1.setFileViewed("a.txt", "fp", true)

        assertEquals(SyncResult.Ok(pulled = false, pushed = true), dev1.sync("work"))
        assertEquals(SyncResult.Ok(pulled = false, pushed = false), dev1.sync("work"), "nothing to do the second time")

        // Device 2 joins by creating a group with the same remote: no per-review setup.
        val dev2 = ReviewGroups(tempDir())
        val g2 = dev2.create("work", remote.toString())
        val summary = dev2.repository(g2).list(null).single()
        val store2 = ReviewStore.open(summary.dir, dev2.committer(g2.dir))
        assertEquals("why?", store2.comment(c.id)!!.body)
        assertEquals("fp", store2.progress("a.txt")!!.viewedFingerprint)

        // And it flows back.
        val c2 = store2.addComment(CommentKind.NOTE, "from device 2", lineAnchor())
        assertEquals(SyncResult.Ok(pulled = false, pushed = true), dev2.sync("work"))
        val r1 = dev1.sync("work") as SyncResult.Ok
        assertTrue(r1.pulled && r1.conflicts.isEmpty())
        store1.reload()
        assertEquals("from device 2", store1.comment(c2.id)!!.body)
    }

    @Test
    fun editingTheSameCommentOnTwoDevicesKeepsBothVersionsAndConverges() {
        val remote = bareRemote()
        val dev1 = ReviewGroups(tempDir())
        val g1 = dev1.create("work", remote.toString())
        val store1d = review(dev1, "work", project())
        val c = store1d.addComment(CommentKind.NOTE, "original", lineAnchor())
        dev1.sync("work")

        val dev2 = ReviewGroups(tempDir())
        val g2 = dev2.create("work", remote.toString())
        val dir2 = dev2.repository(g2).list(null).single().dir
        val dir1 = store1d.dir

        val s1 = ReviewStore.open(dir1, dev1.committer(g1.dir), clockAt(10))
        val s2 = ReviewStore.open(dir2, dev2.committer(g2.dir), clockAt(20))
        s1.updateComment(c.id) { it.copy(body = "edited on device 1") }
        s2.updateComment(c.id) { it.copy(body = "edited on device 2") }

        assertTrue(dev1.sync("work") is SyncResult.Ok)
        val r2 = dev2.sync("work") as SyncResult.Ok
        assertEquals(1, r2.conflicts.size, "device 2 hit the conflict")
        val r1 = dev1.sync("work") as SyncResult.Ok
        assertTrue(r1.conflicts.isEmpty(), "device 1 just receives the resolved state")

        s1.reload(); s2.reload()
        // The newer edit wins everywhere; the older text is not lost.
        assertEquals("edited on device 2", s1.comment(c.id)!!.body)
        assertEquals("edited on device 2", s2.comment(c.id)!!.body)
        for ((dir, store) in listOf(dir1 to s1, dir2 to s2)) {
            val kept = Files.list(dir.resolve("comments")).use { it.toList() }.filter { ".conflict-" in it.name }
            assertEquals(1, kept.size, "loser kept next to the winner on $dir")
            assertTrue(Files.readString(kept.single()).contains("edited on device 1"))
            assertTrue(store.problems.any { it.startsWith("Sync conflict") })
        }
        // Converged: nothing left to exchange.
        assertEquals(SyncResult.Ok(false, false), dev1.sync("work"))
        assertEquals(SyncResult.Ok(false, false), dev2.sync("work"))
    }

    @Test
    fun connectingExistingLocalGroupsWithDifferentHistoriesMergesThem() {
        val remote = bareRemote()
        val dev1 = ReviewGroups(tempDir())
        dev1.create("notes")
        review(dev1, "notes", project("alpha"), "From device 1")
        val dev2 = ReviewGroups(tempDir())
        dev2.create("notes")
        review(dev2, "notes", project("beta"), "From device 2")

        dev1.setRemote("notes", remote.toString())
        assertTrue(dev1.sync("notes") is SyncResult.Ok)
        dev2.setRemote("notes", remote.toString())
        val r = dev2.sync("notes") as SyncResult.Ok
        assertTrue(r.pulled && r.pushed)
        dev1.sync("notes")

        for (g in listOf(dev1, dev2)) {
            assertEquals(setOf("From device 1", "From device 2"), g.repository(g.group("notes")!!).list(null).map { it.review.title }.toSet())
        }
    }

    @Test
    fun unreachableRemoteFailsCleanlyAndLocalDataIsUntouched() {
        val groups = ReviewGroups(tempDir())
        groups.create("work")
        val store = review(groups, "work", project())
        val c = store.addComment(CommentKind.NOTE, "keep me", lineAnchor())
        groups.setRemote("work", tempDir().resolve("does-not-exist").toString())

        val r = groups.sync("work")
        assertTrue(r is SyncResult.Failed, "$r")
        assertEquals("keep me", ReviewStore.open(store.dir).comment(c.id)!!.body)
        // Local commit history was still recorded.
        val tracked = GitCli(groups.group("work")!!.dir).run("ls-files")
        assertTrue(tracked.contains("${c.id}.json"), "comment committed locally despite the failed sync: $tracked")

        groups.setRemote("work", null)
        assertEquals(SyncResult.NoRemote, groups.sync("work"))
        assertNull(groups.group("work")!!.remoteUrl)
    }

    @Test
    fun creatingGroupWithUnreachableRemoteLeavesNoFolderBehind() {
        val root = tempDir()
        val groups = ReviewGroups(root)
        assertFailsWith<GitException> { groups.create("work", root.resolve("nope").toString()) }
        assertFalse(root.resolve("work").exists())
        assertTrue(groups.list().isEmpty())
        // A retry with a good remote works.
        assertEquals("work", groups.create("work", bareRemote().toString()).name)
    }

    @Test
    fun autoSyncFlagIsPerMachineConfig() {
        val groups = ReviewGroups(tempDir())
        groups.create("work", bareRemote().toString())
        assertTrue(groups.group("work")!!.autoSync)
        groups.setAutoSync("work", false)
        assertFalse(groups.group("work")!!.autoSync)
        assertTrue(GitCli(groups.group("work")!!.dir).run("ls-files").lines().none { "autosync" in it })
    }

    @Test
    fun commitHookReportsTheGroup() {
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        val groups = ReviewGroups(tempDir(), onCommitted = { seen.add(it) })
        groups.create("work")
        seen.clear()
        val store = review(groups, "work", project())
        store.addComment(CommentKind.NOTE, "x", lineAnchor())
        store.flush()
        assertEquals("work", seen.poll(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    // ---- migration ---------------------------------------------------------------------------------------------------

    @Test
    fun legacyPerReviewReposMoveIntoTheDefaultGroupKeepingTheirHistory() {
        val root = tempDir()
        // Build the old layout: <root>/<repo>/<id>/ each its own git repo.
        val legacyDir = root.resolve("proj").resolve("20260101-1200-old-review")
        val rev = Revision(1, "t", "b".repeat(40), "h".repeat(40), "b".repeat(40))
        val old = ReviewStore.create(
            legacyDir,
            Review(id = "20260101-1200-old-review", title = "Old", repo = RepoInfo("proj"), baseRef = "master", headRef = "f", revisions = listOf(rev), createdAt = "2026-01-01T00:00:00Z", updatedAt = "t"),
            GitCommitter(legacyDir),
        )
        val c = old.addComment(CommentKind.NOTE, "precious", lineAnchor())
        old.flush()
        assertTrue(legacyDir.resolve(".git").exists())

        val groups = ReviewGroups(root)
        groups.rememberLastOpened("some-project", legacyDir)
        assertEquals(1, groups.migrateLegacy())
        assertEquals(0, groups.migrateLegacy(), "idempotent")

        val moved = root.resolve("default").resolve("proj").resolve("20260101-1200-old-review")
        assertTrue(moved.resolve("review.json").exists())
        assertEquals(moved, groups.lastOpened("some-project"), "resume pointer follows the migrated review")
        assertFalse(moved.resolve(".git").exists(), "no nested repo inside the group repo")
        assertFalse(root.resolve("proj").exists(), "emptied legacy folder removed")
        assertTrue(root.resolve(".legacy/proj/20260101-1200-old-review.git").exists(), "old history preserved")
        assertEquals("precious", ReviewStore.open(moved, groups.committer(root.resolve("default"))).comment(c.id)!!.body)
        assertEquals("", GitCli(root.resolve("default")).run("status", "--porcelain").trim(), "migration is committed")
        assertEquals(listOf("default"), groups.list().map { it.name })
        assertNotNull(groups.listReviews(null).singleOrNull())
    }
}
