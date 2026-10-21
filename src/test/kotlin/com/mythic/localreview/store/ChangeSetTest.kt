package com.mythic.localreview.store

import com.mythic.localreview.core.GitCli
import com.mythic.localreview.core.TestRepo
import com.mythic.localreview.format.ChangedFile
import com.mythic.localreview.format.Pin
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.SideLines
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangeSetTest {
    private fun repoWithRenames(): Pair<TestRepo, Pin> {
        val r = TestRepo(Files.createTempDirectory("lr-cs"))
        for (i in 0 until 6) r.write("d/f$i.txt", (0 until 20).joinToString("\n", postfix = "\n") { "line $i-$it unique content" })
        r.write("keep.txt", "k\n")
        r.write("script.sh", "#!/bin/sh\n")
        val base = r.commit("base")
        r.branch("feature")
        for (i in 0 until 6) {
            r.mv("d/f$i.txt", "e/g$i.txt")
            Files.writeString(r.dir.resolve("e/g$i.txt"), Files.readString(r.dir.resolve("e/g$i.txt")) + "edited $i\n")
        }
        r.git.run("update-index", "--chmod=+x", "script.sh")
        r.delete("keep.txt")
        r.write("new.txt", "n\n")
        val head = r.commit("work")
        return r to Pin(base, base, head)
    }

    @Test
    fun `the pinned command reports renames with both paths, modes and blob ids, whatever the user's git configuration says`() {
        val (repo, pin) = repoWithRenames()
        // A user's configuration that would make plain `git diff -M` report different files.
        repo.git.run("config", "diff.renameLimit", "1")
        repo.git.run("config", "diff.renames", "false")
        repo.git.run("config", "diff.relative", "true")
        val set = ChangeSets.read(repo.git, pin)
        val byPath = set.files.associateBy { it.path }

        val renamed = byPath.getValue("e/g0.txt")
        assertEquals("d/f0.txt", renamed.oldPath)
        assertEquals("renamed", renamed.status)
        assertEquals(6, set.files.count { it.oldPath != null }, "all six renames are detected despite diff.renameLimit=1")
        assertTrue(renamed.changeKey.startsWith("100644:100644:") && renamed.oldBlob != renamed.newBlob)
        assertTrue(byPath.getValue("script.sh").changeKey.startsWith("100644:100755:"), "a mode change is part of the key")
        val added = byPath.getValue("new.txt")
        assertEquals("000000", added.oldMode)
        assertTrue(added.oldBlob.all { it == '0' } && added.blob(Side.OLD) == null && added.blob(Side.NEW) != null)
        val deleted = byPath.getValue("keep.txt")
        assertEquals("000000", deleted.newMode)
        assertNull(deleted.blob(Side.NEW))
        assertEquals(setOf("e/g0.txt", "e/g1.txt", "e/g2.txt", "e/g3.txt", "e/g4.txt", "e/g5.txt", "script.sh", "keep.txt", "new.txt"), byPath.keys)
    }

    @Test
    fun `a path with spaces and non-ASCII characters is read as it is`() {
        val r = TestRepo(Files.createTempDirectory("lr-cs"))
        r.write("start.txt", "s\n")
        val base = r.commit("base")
        r.write("dir with space/größe ✓.txt", "x\n")
        val head = r.commit("odd")
        val set = ChangeSets.read(r.git, Pin(base, base, head))
        assertEquals(listOf("dir with space/größe ✓.txt"), set.files.map { it.path })
    }

    @Test
    fun `blobs are read through one long-lived process, and missing objects and non-blobs give null`() {
        val (repo, pin) = repoWithRenames()
        val set = ChangeSets.read(repo.git, pin)
        GitBlobs(repo.dir).use { blobs ->
            val f: ChangedFile = set.file("new.txt")!!
            assertEquals("n\n", String(blobs.read(f.newBlob)!!))
            assertEquals("n\n", String(blobs.read(f.newBlob)!!), "again, on the same process")
            assertNull(blobs.read("0123456789abcdef0123456789abcdef01234567"), "missing")
            val tree = repo.git.run("rev-parse", "${pin.headSha}^{tree}").trim()
            assertNull(blobs.read(tree), "a tree is not a blob")
            assertNull(blobs.read("not a sha\nhead"), "anything that is not a commit id is refused before it reaches git")
            assertEquals("n\n", String(blobs.read(f.newBlob)!!), "and the process is still in step")
            val big = ByteArray(300_000) { ('a'.code + it % 26).toByte() }
            Files.write(repo.dir.resolve("big.bin.txt"), big)
            val id = repo.git.run("hash-object", "-w", "big.bin.txt").trim()
            assertTrue(big.contentEquals(blobs.read(id)), "a large blob arrives whole")
            val lines = SideLines(blobs).of(f, Side.NEW)
            assertEquals(listOf("n"), lines?.toList(), "lines come from the blob bytes through the line model")
        }
    }

    @Test
    fun `root commits and repository identity`() {
        val (repo, pin) = repoWithRenames()
        val root = ChangeSets.rootCommit(repo.git, pin.headSha)
        assertEquals(pin.baseSha, root)
        val local = LocalRepo.of(repo.git)
        assertEquals(setOf(pin.baseSha), local.rootCommits)
        repo.git.run("remote", "add", "origin", "https://Example.com/Acme/Calc.git/")
        val withUrl = LocalRepo.of(repo.git)
        val ref = withUrl.repoRef(pin.headSha, repo.git)
        assertEquals(pin.baseSha, ref.rootCommit)
        assertTrue(withUrl.matches(ref))
        // The root commit decides first: a different history is a different repository even with the same URL and name.
        assertTrue(!withUrl.matches(ref.copy(rootCommit = "f".repeat(40))))
        // Without a root commit on one side, the URL is compared after normalisation, then the name.
        assertTrue(withUrl.matches(ref.copy(rootCommit = null, originUrl = "https://example.com/acme/calc")))
        assertTrue(!withUrl.matches(ref.copy(rootCommit = null, originUrl = "https://example.com/acme/other")))
        assertTrue(LocalRepo(repo.dir.fileName.toString(), null, emptySet()).matches(ref.copy(rootCommit = null, originUrl = null, displayName = repo.dir.fileName.toString())))
    }
}
