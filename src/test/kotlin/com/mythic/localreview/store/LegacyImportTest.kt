package com.mythic.localreview.store

import com.mythic.localreview.core.GitCli
import com.mythic.localreview.core.tempDir
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.CanonicalJson
import com.mythic.localreview.format.CommentState
import com.mythic.localreview.format.Evaluate
import com.mythic.localreview.format.FileMarkState
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.MetaRead
import com.mythic.localreview.format.Pin
import com.mythic.localreview.format.ReviewMetaCodec
import com.mythic.localreview.format.ReviewMode
import com.mythic.localreview.format.ReviewStatus
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.SideLines
import com.mythic.localreview.format.SpecFilesForStore
import com.mythic.localreview.format.Values
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyImportTest {
    private val id = "20261010-0900-add-calc-module"
    private val base = "f2fa38f399e8b0bc8edab7af5e8d0e1d7271af42"
    private val r1 = "cb9a8793d52c586a03d8b43ed24b56c291c2085a"
    private val r2 = "302769e6a12380145e2c9f68835de42c89cc7439"

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** The spec's example repository, cloned: master, and feature/calc at two rounds. */
    private fun clone(): GitCli {
        val parent = Files.createTempDirectory("lr-imp-clone")
        GitCli(parent).run("clone", "-q", SpecFilesForStore.file("examples/content/fixture.bundle").toString(), "calc-demo")
        return GitCli(parent.resolve("calc-demo")).also { it.run("remote", "set-url", "origin", "https://example.com/acme/calc-demo.git") }
    }

    /** A group as 0.1.0 left it: the fixture files (written by 0.1.0's own serializers) under `<repo-name>/<review-id>/`. */
    private fun legacyGroup(): Pair<ReviewGroups, ReviewGroup> {
        val groups = machineGroups()
        val info = groups.create("work")
        val src = Path.of(javaClass.getResource("/legacy-0.1.0")!!.toURI())
        src.toFile().copyRecursively(info.dir.toFile())
        return groups to groups.reviewGroup(info)
    }

    private fun importer(g: ReviewGroup, state: Path? = null) = LegacyImporter(g, state)

    @Test
    fun `a 0_1_0 review becomes a version 2 review with its comments, marks and revisions`() {
        val (groups, group) = legacyGroup()
        val git = clone()
        val oldBytes = group.dir.resolve("calc-demo/$id").toFile().walkTopDown().filter { it.isFile }.associate { it.relativeTo(group.dir.toFile()).path to it.readBytes().toList() }

        val report = importer(group).run(listOf(git))
        assertEquals(emptyList(), report.problems)
        assertEquals(emptyList(), report.notYet)
        val imported = report.imported.single()
        assertEquals(id, imported.reviewId)
        assertEquals(22, imported.eventsAppended)

        val open = group.open(id)!!
        val m = open.meta
        assertEquals("import-v1", m.createdBy)
        assertEquals(ms("2026-10-10T09:00:00Z"), m.createdAt)
        assertEquals("calc-demo", m.repo.displayName)
        assertEquals("https://example.com/acme/calc-demo.git", m.repo.originUrl)
        assertEquals(base, m.repo.rootCommit)
        assertEquals(ReviewMode.MERGE_BASE, m.mode)
        assertEquals(Pin(base, base, r1), m.pin, "revision 1 is the pin: effective base is the left side, base the tip")
        assertEquals(CanonicalJson.write(ReviewMetaCodec.encode(m)).toList(), open.dir.resolve("review.json").readBytes().toList(), "canonical bytes")

        val s = open.state
        assertEquals(Pin(base, base, r2), s.header.pin, "revision 2 is a retarget event")
        assertEquals(ReviewStatus.OPEN, s.header.status)
        assertEquals(0, s.report.unknown + s.report.orphans + s.report.invalidEnvelopes + s.report.duplicates)
        fun c(n: Int) = s.annotation("c0000001-0000-4000-8000-00000000000$n")!!

        // 1: lines anchor kept, rule becomes the tag and the text, the resolution survives, a restore does not undelete anything wrongly
        c(1).let {
            assertEquals(Kind.CHANGE_REQUEST, it.kind)
            assertEquals(listOf("rule_candidate"), it.tags)
            assertEquals("Guard against division by zero in arithmetic helpers.", it.ruleText)
            assertEquals(AnnotationStatus.RESOLVED, it.status)
            assertEquals("fixed in round 2", it.statusNote)
            val a = it.anchor as Anchor.Lines
            assertEquals("src/calc.ts", a.path)
            assertEquals(Pin(base, base, r1), a.pin)
            assertEquals(listOf(Triple(Side.NEW, 13, 15)), a.ranges.map { r -> Triple(r.side, r.startLine, r.endLine) })
            assertEquals(3, a.ranges.single().snapshot.lines.size)
            assertTrue(!it.deleted)
        }
        assertEquals(Anchor.Review, c(2).anchor)
        // 3: a note on the old path of a renamed file: identity path is the new one, oldPath the old
        assertEquals(Anchor.File("src/util.ts", "src/util_old.ts", Pin(base, base, r1)), c(3).anchor)
        // 4 and 5: lines that cannot be addressed by the line model become file comments
        assertEquals(Anchor.File("assets/logo.bin", null, Pin(base, base, r1)), c(4).anchor, "a binary file has no lines")
        assertEquals(Anchor.File("src/calc.ts", null, Pin(base, base, r1)), c(5).anchor, "a line after the final newline does not exist")
        assertTrue(c(6).deleted)
        assertEquals(Side.OLD, ((c(6).anchor) as Anchor.Lines).ranges.single().side)
        assertEquals(Pin(base, base, r2), (c(7).anchor as Anchor.Lines).pin)
        // Three events of this comment share the source time 09:20:00; a log's ts is strictly increasing, so they land 1 ms apart (CONFORMANCE, "Ids and times").
        assertEquals(ms("2026-10-10T09:20:00Z") + 2, c(8).updatedAt)
        assertEquals(ms("2026-10-10T09:10:00Z"), c(1).createdAt)
        assertEquals("import-v1", c(1).createdBy)

        // Marks: valid fingerprint at the last revision becomes a file mark with the new changeKey; stale or cleared becomes no mark.
        assertEquals(listOf("assets/logo.bin", "src/util.ts"), s.fileMarks.map { it.path })
        val set = ChangeSets.read(git, s.header.pin)
        assertEquals(set.file("src/util.ts")!!.changeKey, s.fileMarks.single { it.path == "src/util.ts" }.changeKey)

        // The imported state evaluates against the real repository.
        GitBlobs(git.toplevel()).use { blobs ->
            val ev = Evaluate.evaluate(s, set, SideLines(blobs))
            assertEquals(CommentState.Current, ev.annotations[c(7).id])
            assertTrue(ev.annotations.getValue(c(1).id) is CommentState.Outdated, "the header shifted the division code")
            assertEquals(CommentState.Unchanged, ev.annotations[c(3).id])
            assertEquals(setOf(FileMarkState.VALID), ev.fileMarks.map { it.state }.toSet())
        }

        // The old files are exactly as they were.
        assertEquals(oldBytes, group.dir.resolve("calc-demo/$id").toFile().walkTopDown().filter { it.isFile }.associate { it.relativeTo(group.dir.toFile()).path to it.readBytes().toList() })
        group.committer.flush()
        assertEquals("", GitCli(group.dir).status())
        assertTrue(groups.reviewGroup(groups.group("work")!!).reviews().skipped.isEmpty())
    }

    @Test
    fun `two devices import the same bytes, and importing again appends only what changed`() {
        val (_, g1) = legacyGroup()
        val (_, g2) = legacyGroup()
        val git = clone()
        assertEquals(22, importer(g1).run(listOf(git)).imported.single().eventsAppended)
        assertEquals(22, importer(g2).run(listOf(git)).imported.single().eventsAppended)
        val log = "reviews/$id/events/import-v1.jsonl"
        assertEquals(g1.dir.resolve(log).readText(), g2.dir.resolve(log).readText(), "same event ids, contents and times on both devices")
        assertEquals(g1.dir.resolve("reviews/$id/review.json").readText(), g2.dir.resolve("reviews/$id/review.json").readText())
        assertTrue(g1.dir.resolve(log).readText().lines().filter { it.isNotBlank() }.all { Regex("\"id\":\"imp-[0-9a-f]{32}\"").containsMatchIn(it) })

        // Unchanged files add nothing.
        assertEquals(0, importer(g1).run(listOf(git)).imported.single().eventsAppended)

        // A file 0.1.0 changed afterwards gives new events, which apply as edits.
        val f = g1.dir.resolve("calc-demo/$id/comments/c0000001-0000-4000-8000-000000000002.json")
        f.toFile().writeText(f.readText().replace("Is this meant for release 2?", "Is this meant for release 3?").replace("\"updatedAt\": \"2026-10-10T09:11:00Z\"", "\"updatedAt\": \"2026-10-10T09:50:00Z\""))
        val again = importer(g1).run(listOf(git)).imported.single()
        assertEquals(4, again.eventsAppended, "created (as an edit), edited, status, restored")
        assertEquals("Is this meant for release 3?", g1.open(id)!!.state.annotation("c0000001-0000-4000-8000-000000000002")!!.body)
        assertEquals(0, g1.open(id)!!.state.report.duplicates)
    }

    @Test
    fun `nothing is written for a review whose repository or commits are not here, and it is tried again later`() {
        val (_, group) = legacyGroup()
        val noClone = importer(group).run(emptyList())
        assertEquals(1, noClone.notYet.size)
        assertTrue(noClone.imported.isEmpty())
        assertTrue(!group.reviewsDir.resolve(id).exists(), "nothing written")

        // A clone with the right URL but another history has none of the commits.
        val other = project("calc-demo")
        other.git.run("remote", "add", "origin", "https://example.com/acme/calc-demo.git")
        assertEquals(1, importer(group).run(listOf(other.git)).notYet.size)
        assertTrue(!group.reviewsDir.resolve(id).exists())

        assertEquals(1, importer(group).run(listOf(other.git, clone())).imported.size, "the right clone among several is found")
    }

    @Test
    fun `the state folder remembers what was imported so reopening a group costs nothing`() {
        val (_, group) = legacyGroup()
        val git = clone()
        val state = tempDir()
        assertEquals(1, importer(group, state).run(listOf(git)).imported.size)
        assertEquals(emptyList(), importer(group, state).run(listOf(git)).imported, "skipped, not even asked git")
        // A change to an old file is noticed.
        val f = group.dir.resolve("calc-demo/$id/comments/c0000001-0000-4000-8000-000000000003.json")
        f.toFile().writeText(f.readText().replace("Why rename this file?", "Why rename it?"))
        assertEquals(1, importer(group, state).run(listOf(git)).imported.size)
    }

    @Test
    fun `an existing review at the target id that was not imported is never touched`() {
        val (_, group) = legacyGroup()
        val git = clone()
        val target = group.reviewsDir.resolve(id)
        Files.createDirectories(target)
        Files.writeString(target.resolve("review.json"), "{\"schemaVersion\":1,\"id\":\"$id\"}")
        val report = importer(group).run(listOf(git))
        assertTrue(report.imported.isEmpty())
        assertTrue(report.problems.single().contains("already exists"), report.problems.toString())
        assertEquals("{\"schemaVersion\":1,\"id\":\"$id\"}", target.resolve("review.json").readText())
        assertTrue(!target.resolve("events").exists())
    }

    @Test
    fun `review ids that do not fit the id rule are made to`() {
        assertEquals("20260301-1015-fix-login", LegacyImporter.importReviewId("20260301-1015-fix-login"))
        assertEquals("a_b_c", LegacyImporter.importReviewId("a b/c"))
        val long = "20260301-1015-" + "x".repeat(53)
        assertEquals(67, long.length)
        val made = LegacyImporter.importReviewId(long)
        assertEquals(64, made.length)
        assertTrue(Values.isId(made))
        assertEquals(long.take(55) + "-", made.take(56))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(long.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(digest.take(8), made.takeLast(8))
        assertEquals("caf_", LegacyImporter.importReviewId("caf\u00e9"))
    }

    @Test
    fun `event ids follow the rule of the conformance notes`() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("comments/x.json".toByteArray()); digest.update(0)
        digest.update("{}".toByteArray()); digest.update(0)
        digest.update("annotationCreated".toByteArray()); digest.update(0)
        val expected = "imp-" + digest.digest().joinToString("") { "%02x".format(it) }.take(32)
        assertEquals(expected, LegacyImporter.importEventId("comments/x.json", "{}".toByteArray(), "annotationCreated", ""))
        assertTrue(LegacyImporter.importEventId("review.json", "{}".toByteArray(), "reviewRetargeted", "2") != LegacyImporter.importEventId("review.json", "{}".toByteArray(), "reviewRetargeted", "3"))
    }
}
