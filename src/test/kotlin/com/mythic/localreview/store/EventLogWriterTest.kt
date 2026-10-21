package com.mythic.localreview.store

import com.mythic.localreview.format.Event
import com.mythic.localreview.format.Logs
import com.mythic.localreview.format.RawEnvelope
import com.mythic.localreview.format.ReportBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventLogWriterTest {
    private val dir: Path = Files.createTempDirectory("lrw-writer")
    private val events: Path = dir.resolve("reviews/r1/events")
    private val dev = "DEV1"

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun renamed(n: Int) = Event.ReviewRenamed("t$n")

    private fun read(device: String = dev): Pair<List<RawEnvelope>, ReportBuilder> {
        val report = ReportBuilder()
        val envs = Logs.parse("$device.jsonl", events.resolve("$device.jsonl").readBytes(), report)
        return envs to report
    }

    @Test
    fun `first event gets seq 1 and each next one more, with a rising ts`() {
        val w = EventLogWriter()
        val a = w.append(events, dev, renamed(1))
        val b = w.append(events, dev, listOf(renamed(2), renamed(3)))
        assertEquals(1, a.seq)
        assertEquals(listOf(2L, 3L), b.map { it.seq })
        val (envs, report) = read()
        assertEquals(listOf(1L, 2L, 3L), envs.map { it.seq })
        assertTrue(envs.zipWithNext().all { (x, y) -> y.ts > x.ts }, "ts strictly increasing")
        assertEquals(0, report.corruptLines.size + report.tornTails + report.invalidEnvelopes)
        assertEquals(setOf(dev), envs.map { it.dev }.toSet())
        assertEquals(envs.size, envs.map { it.id }.toSet().size, "ids are unique")
    }

    @Test
    fun `ts keeps rising when the clock goes backwards`() {
        var now = 10_000L
        val w = EventLogWriter(clock = { now })
        val a = w.append(events, dev, renamed(1))
        now = 5_000
        val b = w.append(events, dev, renamed(2))
        assertEquals(10_000, a.ts)
        assertEquals(10_001, b.ts)
        // A new instance (a restarted IDE, or another one) sees the same through the log, not through memory.
        val c = EventLogWriter(clock = { 1L }).append(events, dev, renamed(3))
        assertEquals(10_002, c.ts)
        assertEquals(3, c.seq)
    }

    @Test
    fun `a cut-off last line is closed first and the numbers continue from the last valid event`() {
        val w = EventLogWriter()
        w.append(events, dev, listOf(renamed(1), renamed(2)))
        val log = events.resolve("$dev.jsonl")
        log.writeBytes(log.readBytes() + """{"v":1,"id":"CUT","ts":999999""".toByteArray())
        val next = w.append(events, dev, renamed(3))
        assertEquals(3, next.seq)
        val (envs, report) = read()
        assertEquals(listOf(1L, 2L, 3L), envs.map { it.seq })
        assertEquals(1, report.corruptLines.size, "the fragment is now a complete invalid line and is reported, not hidden")
        assertEquals(0, report.tornTails)
    }

    @Test
    fun `a fragment closed by an earlier writer that then died is skipped when reading the tail`() {
        val w = EventLogWriter()
        w.append(events, dev, listOf(renamed(1), renamed(2)))
        val log = events.resolve("$dev.jsonl")
        // The state SPEC 5.2 step 2 is about: the fragment was closed with a newline, and nothing was appended after it.
        log.writeBytes(log.readBytes() + "{\"v\":1,\"id\":\"CUT\",\"ts\":9\n".toByteArray())
        val next = w.append(events, dev, renamed(3))
        assertEquals(3, next.seq)
        assertEquals(listOf(1L, 2L, 3L), read().first.map { it.seq })
    }

    @Test
    fun `a complete last line that only lacks its newline counts as the last event`() {
        val w = EventLogWriter()
        w.append(events, dev, listOf(renamed(1), renamed(2)))
        val log = events.resolve("$dev.jsonl")
        log.writeBytes(log.readBytes().dropLast(1).toByteArray())
        val next = w.append(events, dev, renamed(3))
        assertEquals(3, next.seq)
        assertEquals(listOf(1L, 2L, 3L), read().first.map { it.seq })
    }

    @Test
    fun `a long log is read from its end`() {
        val w = EventLogWriter()
        val big = "x".repeat(300)
        repeat(1500) { w.append(events, dev, Event.ReviewRenamed("$big$it")) }
        assertTrue(Files.size(events.resolve("$dev.jsonl")) > 400_000)
        // Damage near the end, longer than the first window, forces the reader to look further back.
        val log = events.resolve("$dev.jsonl")
        log.writeBytes(log.readBytes() + ("junk line\n".repeat(20_000)).toByteArray())
        val next = w.append(events, dev, renamed(0))
        assertEquals(1501, next.seq)
    }

    @Test
    fun `several instances in one process write one log without losing or interleaving lines`() {
        val threads = 6
        val each = 40
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until threads).map { t ->
            Thread {
                try {
                    val w = EventLogWriter() // each IDE window would have its own
                    start.await()
                    repeat(each) { i -> w.append(events, dev, renamed(t * 1000 + i)) }
                } catch (e: Throwable) {
                    errors += e
                }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertTrue(errors.isEmpty(), errors.toString())
        assertSerial(threads * each)
    }

    private fun assertSerial(count: Int) {
        val (envs, report) = read()
        assertEquals(0, report.corruptLines.size + report.tornTails + report.invalidEnvelopes)
        assertEquals((1L..count).toList(), envs.map { it.seq }, "seq is 1..n in file order")
        assertTrue(envs.zipWithNext().all { (x, y) -> y.ts > x.ts }, "ts strictly increasing in file order")
        assertEquals(count, envs.map { it.id }.toSet().size)
    }

    @Test
    fun `separate processes write one log serially through the lock file`() {
        Files.createDirectories(events)
        val procs = (1..3).map { w -> startJava("com.mythic.localreview.store.AppendMain", dir, events.toString(), dev, "30", "$w") }
        for (p in procs) {
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, p.waitFor(), out)
            assertTrue("DONE" in out, out)
        }
        assertSerial(90)
    }

    @Test
    fun `a lock held by another process makes the write fail without writing, and it works again once the holder is gone`() {
        Files.createDirectories(events)
        val holder = startJava("com.mythic.localreview.store.LockHolderMain", dir, events.resolve("$dev.lock").toString())
        try {
            assertEquals("LOCKED", holder.inputStream.bufferedReader().readLine())
            val w = EventLogWriter(lockWaitMillis = 300)
            assertFailsWith<LogBusyException> { w.append(events, dev, renamed(1)) }
            assertTrue(!Files.exists(events.resolve("$dev.jsonl")) || Files.size(events.resolve("$dev.jsonl")) == 0L, "nothing was written")
            holder.outputStream.close() // the holder releases its lock and exits
            assertEquals(0, holder.waitFor())
            assertEquals(1, w.append(events, dev, renamed(2)).seq)
        } finally {
            holder.destroyForcibly()
        }
    }
}
