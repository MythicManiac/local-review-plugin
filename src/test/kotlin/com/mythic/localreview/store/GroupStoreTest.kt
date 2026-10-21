package com.mythic.localreview.store

import com.mythic.localreview.core.AtomicFiles
import com.mythic.localreview.core.GitCli
import com.mythic.localreview.core.GitCommitter
import com.mythic.localreview.core.GitException
import com.mythic.localreview.core.SyncResult
import com.mythic.localreview.core.bareRemote
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.CanonicalJson
import com.mythic.localreview.format.Event
import com.mythic.localreview.format.GroupMetaCodec
import com.mythic.localreview.format.MetaRead
import com.mythic.localreview.format.ReviewMetaCodec
import com.mythic.localreview.format.ReviewStatus
import com.mythic.localreview.format.SpecFilesForStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroupStoreTest {
    // ---- canonical files -----------------------------------------------------------------------------------------

    @Test
    fun `review json and group json are written exactly as the spec's example has them`() {
        val reviewFile = SpecFilesForStore.file("examples/state/reviews/01M4XBCYR0AQJREYKYGS5WNERF/review.json")
        val meta = (ReviewMetaCodec.decode(SpecFilesForStore.parse(reviewFile)) as MetaRead.Ok).value
        assertEquals(reviewFile.readText(), String(CanonicalJson.write(ReviewMetaCodec.encode(meta))), "review.json bytes")

        val groupFile = SpecFilesForStore.file("examples/state/group.json")
        val group = (GroupMetaCodec.decode(SpecFilesForStore.parse(groupFile)) as MetaRead.Ok).value
        assertEquals(groupFile.readText(), String(CanonicalJson.write(GroupMetaCodec.encode(group))), "group.json bytes")
    }

    @Test
    fun `canonical json escapes like JSON stringify`() {
        val v = JsonObject(
            mapOf(
                "s" to JsonPrimitive("q\"b\\n\n\t\u0001\u001f é \uD83D\uDE00 \uD800 x\u2028"),
                "e" to JsonArray(emptyList()),
                "o" to JsonObject(emptyMap()),
                "n" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(true), kotlinx.serialization.json.JsonNull)),
            ),
        )
        val expected = "{\n  \"s\": \"q\\\"b\\\\n\\n\\t\\u0001\\u001f é \uD83D\uDE00 \\ud800 x\u2028\",\n  \"e\": [],\n  \"o\": {},\n  \"n\": [\n    1,\n    true,\n    null\n  ]\n}\n"
        assertEquals(expected, String(CanonicalJson.write(v)))
    }

    // ---- creating and opening groups -------------------------------------------------------------------------------

    @Test
    fun `a new group has the files of SPEC 2_1 and group json and is committed`() {
        val groups = machineGroups()
        val info = groups.create("work")
        assertEquals("* -text\n*.jsonl merge=union\n", info.dir.resolve(".gitattributes").readText())
        assertEquals("*.lock\n*.tmp\n/reviews/*/local/\n", info.dir.resolve(".gitignore").readText())
        val group = (GroupMetaCodec.decode(SpecFilesForStore.parse(info.dir.resolve("group.json"))) as MetaRead.Ok).value
        assertTrue(group.id.length == 26 && group.name == null)
        val git = GitCli(info.dir)
        assertEquals(setOf(".gitattributes", ".gitignore", "group.json"), git.run("ls-files").lines().filter { it.isNotBlank() }.toSet())
        assertEquals("", git.status(), "everything is committed")
    }

    @Test
    fun `a group made by 0_1_0 is completed and converted in place and the change is committed`() {
        val root = com.mythic.localreview.core.tempDir()
        val dir = root.resolve("old")
        Files.createDirectories(dir)
        val git = GitCli(dir)
        git.run("init", "-q")
        git.run("symbolic-ref", "HEAD", "refs/heads/main")
        git.run("config", "user.name", "T"); git.run("config", "user.email", "t@example.com"); git.run("config", "core.autocrlf", "false")
        Files.writeString(dir.resolve(".gitignore"), "*.tmp\n")
        Files.writeString(dir.resolve("group.json"), "{\n  \"schemaVersion\": 1,\n  \"id\": \"3f0c8a2e-1111-4222-8333-444455556666\",\n  \"createdAt\": \"2026-03-01T10:15:30.123Z\"\n}\n")
        Files.createDirectories(dir.resolve("proj/20260301-1015-old-review"))
        Files.writeString(dir.resolve("proj/20260301-1015-old-review/review.json"), "{\"schemaVersion\":1}")
        git.run("add", "-A"); git.run("commit", "-q", "-m", "0.1.0")

        val groups = machineGroups(root)
        val report = groups.reviewGroup(groups.group("old")!!).prepare()
        assertEquals(null, report.refused)
        assertTrue(report.repoFilesChanged)
        assertEquals("*.tmp\n*.lock\n/reviews/*/local/\n", dir.resolve(".gitignore").readText(), "missing lines appended, existing line untouched")
        assertEquals("* -text\n*.jsonl merge=union\n", dir.resolve(".gitattributes").readText())
        assertEquals(
            "{\n  \"schemaVersion\": 2,\n  \"id\": \"3f0c8a2e-1111-4222-8333-444455556666\",\n  \"createdAt\": 1772360130123\n}\n",
            dir.resolve("group.json").readText(), "converted in place, id kept, createdAt in ms",
        )
        assertEquals("", git.status())
        assertTrue(dir.resolve("proj/20260301-1015-old-review/review.json").exists(), "the old layout is left where it is")
        assertFalse(groups.reviewGroup(groups.group("old")!!).prepare().repoFilesChanged, "opening again changes nothing")
    }

    @Test
    fun `a group written by a newer format is refused and left alone`() {
        val groups = machineGroups()
        val info = groups.create("future")
        val file = info.dir.resolve("group.json")
        Files.writeString(file, "{\"schemaVersion\":3,\"id\":\"x\",\"createdAt\":1}")
        val report = groups.reviewGroup(info).prepare()
        assertNotNull(report.refused)
        assertEquals("{\"schemaVersion\":3,\"id\":\"x\",\"createdAt\":1}", file.readText())
    }

    @Test
    fun `a rule that a later line overrides is appended again and then honoured`() {
        val groups = machineGroups()
        val info = groups.create("work")
        // A template from a hosting provider, or another tool, adds a later line that turns text conversion back on.
        Files.writeString(info.dir.resolve(".gitattributes"), "* -text\n*.jsonl merge=union\n* text=auto\n")
        val report = groups.reviewGroup(info).prepare()
        assertEquals(emptyList(), report.notHonoured)
        assertEquals("* -text\n*.jsonl merge=union\n* text=auto\n* -text\n", info.dir.resolve(".gitattributes").readText(), "the overriding line is neither removed nor edited")
        assertTrue(GitCli(info.dir).run("check-attr", "text", "--", "reviews/probe/events/probe.jsonl").contains("unset"))
    }

    // ---- reviews and events -----------------------------------------------------------------------------------------

    @Test
    fun `a review is created, written to, and read back from disk`() {
        val groups = machineGroups()
        groups.create("work")
        val repo = project()
        val open = newReview(groups, "work", repo, "Add three")
        val pin = open.meta.pin
        assertEquals(26, open.id.length)
        assertEquals(repo.git.run("rev-parse", "master").trim(), pin.baseTipSha)
        assertEquals(repo.git.run("rev-parse", "feature").trim(), pin.headSha)

        open.append(commentOn(pin, "C1", "why three?"), "Comment")
        open.append(listOf(Event.ReplyAdded("C1", "R1", "because"), Event.AnnotationStatusChanged("C1", AnnotationStatus.RESOLVED, "answered")), "Reply")
        open.append(Event.ReviewStatusChanged(ReviewStatus.DONE), "Done")
        groups.reviewGroup(groups.group("work")!!).committer.flush()

        // A fresh process (a restarted IDE) sees the same through the disk alone.
        val again = machineGroups(groups.root).reviewGroup(groups.group("work")!!).open(open.id)!!
        val c = again.state.annotation("C1")!!
        assertEquals("why three?", c.body)
        assertEquals(AnnotationStatus.RESOLVED, c.status)
        assertEquals("answered", c.statusNote)
        assertEquals(listOf("because"), c.replies.map { it.body })
        assertEquals(ReviewStatus.DONE, again.state.header.status)
        assertEquals(setOf(groups.device.id()), Files.list(again.eventsDir).use { s -> s.map { it.fileName.toString() }.toList() }.filter { it.endsWith(".jsonl") }.map { it.removeSuffix(".jsonl") }.toSet())
        assertEquals("", GitCli(groups.group("work")!!.dir).status(), "committed, lock file ignored")
        assertEquals(listOf(open.id), groups.reviewGroup(groups.group("work")!!).reviews().reviews.map { it.id })
    }

    @Test
    fun `folders that are not version 2 reviews are reported and never touched`() {
        val groups = machineGroups()
        val info = groups.create("work")
        val g = groups.reviewGroup(info)
        Files.createDirectories(info.dir.resolve("reviews/old-style"))
        Files.writeString(info.dir.resolve("reviews/old-style/review.json"), "{\"schemaVersion\":1,\"id\":\"old-style\"}")
        Files.createDirectories(info.dir.resolve("reviews/broken"))
        Files.writeString(info.dir.resolve("reviews/broken/review.json"), "{ nope")
        Files.createDirectories(info.dir.resolve("reviews/empty"))
        val listed = g.reviews()
        assertEquals(emptyList(), listed.reviews)
        assertEquals(setOf("old-style", "broken", "empty"), listed.skipped.map { it.folder }.toSet())
        assertEquals("{ nope", info.dir.resolve("reviews/broken/review.json").readText())
    }

    // ---- two devices --------------------------------------------------------------------------------------------------

    @Test
    fun `two devices write their own logs and every sync merges without conflict`() {
        val remote = bareRemote()
        val one = machineGroups()
        one.create("work", remote.toString())
        val repo = project()
        val r1 = newReview(one, "work", repo, "Shared")
        r1.append(commentOn(r1.meta.pin, "C1", "from one"), "c")
        assertEquals(SyncResult.Ok(pulled = false, pushed = true), one.sync("work"))

        val two = machineGroups()
        two.create("work", remote.toString())
        val r2 = two.reviewGroup(two.group("work")!!).open(r1.id)!!
        assertEquals("from one", r2.state.annotation("C1")!!.body)
        r2.append(listOf(Event.ReplyAdded("C1", "R2", "from two"), Event.FileReviewed("a.txt", "100644:100644:x:y")), "r")
        // Both append concurrently before either syncs.
        r1.append(Event.AnnotationEdited("C1", body = "one edits"), "e")
        val s2 = two.sync("work") as SyncResult.Ok
        val s1 = one.sync("work") as SyncResult.Ok
        assertEquals(emptyList(), s1.conflicts + s2.conflicts)
        assertTrue(two.sync("work") is SyncResult.Ok)
        r1.refresh(); r2.refresh()

        assertEquals(r1.state.annotations, r2.state.annotations, "both devices fold to the same comments")
        assertEquals("one edits", r1.state.annotation("C1")!!.body)
        assertEquals(listOf("from two"), r1.state.annotation("C1")!!.replies.map { it.body })
        assertEquals(listOf("a.txt"), r1.state.fileMarks.map { it.path })
        assertEquals(2, Files.list(r1.eventsDir).use { s -> s.filter { it.fileName.toString().endsWith(".jsonl") }.count() }.toInt(), "one log per device")
        assertEquals(SyncResult.Ok(false, false), one.sync("work"), "converged")
    }

    @Test
    fun `groups created apart keep the one created first when joined`() {
        val remote = bareRemote()
        val a = machineGroups(clock = { 1_000L })
        a.create("work")
        val b = machineGroups(clock = { 2_000L })
        b.create("work")
        a.setRemote("work", remote.toString())
        b.setRemote("work", remote.toString())
        val keep = a.group("work")!!.dir.resolve("group.json").readText()
        assertTrue(a.sync("work") is SyncResult.Ok)

        val r = b.sync("work") as SyncResult.Ok
        assertEquals(listOf("group.json"), r.conflicts)
        assertEquals(keep, b.group("work")!!.dir.resolve("group.json").readText(), "the older group wins on the device that merged")
        assertTrue(a.sync("work") is SyncResult.Ok)
        assertEquals(keep, a.group("work")!!.dir.resolve("group.json").readText())
        assertEquals("", GitCli(b.group("work")!!.dir).status())
    }

    @Test
    fun `a conflict in a repository file keeps both sides' lines and the required lines last`() {
        val remote = bareRemote()
        val a = machineGroups()
        a.create("work", remote.toString())
        a.sync("work")
        val b = machineGroups()
        b.create("work", remote.toString())
        fun addLine(g: ReviewGroups, line: String) {
            val dir = g.group("work")!!.dir
            AtomicFiles.write(dir.resolve(".gitignore"), (dir.resolve(".gitignore").readText() + "$line\n").toByteArray())
            g.committer(dir).let { c -> c.exclusive { c.commitAllLocked("add $line") } }
        }
        addLine(a, "build/")
        addLine(b, "dist/")
        assertTrue(a.sync("work") is SyncResult.Ok)
        val r = b.sync("work") as SyncResult.Ok
        assertEquals(listOf(".gitignore"), r.conflicts)
        val expected = "build/\ndist/\n*.lock\n*.tmp\n/reviews/*/local/\n"
        assertEquals(expected, b.group("work")!!.dir.resolve(".gitignore").readText())
        assertTrue(a.sync("work") is SyncResult.Ok)
        assertEquals(expected, a.group("work")!!.dir.resolve(".gitignore").readText())
    }

    @Test
    fun `any other conflict aborts the merge, names the file, and changes nothing`() {
        val remote = bareRemote()
        val a = machineGroups()
        a.create("work", remote.toString())
        a.sync("work")
        val b = machineGroups()
        b.create("work", remote.toString())
        fun write(g: ReviewGroups, text: String) {
            val dir = g.group("work")!!.dir
            AtomicFiles.write(dir.resolve("notes.txt"), text.toByteArray())
            g.committer(dir).let { c -> c.exclusive { c.commitAllLocked("notes") } }
        }
        write(a, "from a\n")
        write(b, "from b\n")
        assertTrue(a.sync("work") is SyncResult.Ok)
        val dirB = b.group("work")!!.dir
        val headBefore = GitCli(dirB).run("rev-parse", "HEAD")
        val r = b.sync("work")
        assertTrue(r is SyncResult.Failed && "notes.txt" in r.message, r.toString())
        assertEquals("from b\n", dirB.resolve("notes.txt").readText(), "no winner chosen, nothing hidden")
        assertEquals(headBefore, GitCli(dirB).run("rev-parse", "HEAD"))
        assertEquals("", GitCli(dirB).status(), "the merge was aborted")
        assertFalse(dirB.resolve(".git/MERGE_HEAD").exists())
        assertEquals(setOf("notes.txt"), dirB.toFile().listFiles().orEmpty().map { it.name }.filter { it.startsWith("notes") }.toSet(), "no side file")
    }

    // ---- committing -----------------------------------------------------------------------------------------------------

    @Test
    fun `a commit waits for another tool's index lock and goes through when it is released`() {
        val groups = machineGroups()
        val info = groups.create("work")
        val c = groups.committer(info.dir)
        Files.writeString(info.dir.resolve("x.txt"), "x")
        val lock = info.dir.resolve(".git/index.lock")
        Files.writeString(lock, "")
        Thread { Thread.sleep(700); Files.deleteIfExists(lock) }.start()
        assertTrue(c.exclusive { c.commitAllLocked("x") })
        assertEquals("", GitCli(info.dir).status())
    }

    @Test
    fun `a lock that stays is reported with git's message`() {
        val groups = machineGroups()
        val info = groups.create("work")
        val c = groups.committer(info.dir)
        Files.writeString(info.dir.resolve("x.txt"), "x")
        Files.writeString(info.dir.resolve(".git/index.lock"), "")
        val started = System.nanoTime()
        val e = assertFailsWith<GitException> { c.exclusive { c.commitAllLocked("x") } }
        assertTrue("index.lock" in e.message.orEmpty(), e.message)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= GitCommitter.INDEX_LOCK_WAIT_MS - 200, "it retried for the full wait")
        assertEquals("x", info.dir.resolve("x.txt").readText(), "nothing was lost")
    }
}
