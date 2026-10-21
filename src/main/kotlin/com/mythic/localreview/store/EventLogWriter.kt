package com.mythic.localreview.store

import com.mythic.localreview.format.Event
import com.mythic.localreview.format.EventCodec
import com.mythic.localreview.format.Logs
import com.mythic.localreview.format.RawEnvelope
import com.mythic.localreview.format.ReportBuilder
import com.mythic.localreview.format.Ulid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** The device lock could not be taken in time: another instance of this device is writing, or holds the lock for a whole session. Nothing was written. */
class LogBusyException(message: String) : IOException(message)

/** An event that was written: its place in the log. */
data class Appended(val id: String, val ts: Long, val seq: Long)

/** The last valid event of a log, and whether the file ends in a line terminator (SPEC 5.2, step 2). */
internal data class LogTail(val lastTs: Long?, val lastSeq: Long?, val endsWithNewline: Boolean)

/**
 * Appends events to a device log (SPEC 5.2). Each call is one critical section: take the operating-system lock on `<device>.lock`
 * (never on the log: a lock on the log makes `git add -A` fail on Windows), read the last valid line of the log, append, `fsync`,
 * release. Nothing it remembers is trusted, because another instance of this device (another IDE) may have appended since.
 *
 * All writers of one process go through [processLocks], so that a second lock on the same file by the same process, which Java
 * refuses, never happens. [lockWaitMillis] is how long to wait for another instance before giving up with [LogBusyException].
 */
class EventLogWriter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = Ulid::next,
    private val lockWaitMillis: Long = 3000,
) {
    /** The time of the writer's own previous event per log, so that `ts` also rises when the clock steps back between calls. */
    private val ownLastTs = ConcurrentHashMap<Path, Long>()

    /**
     * Appends [events], in order, as the next lines of `<eventsDir>/<device>.jsonl`, under one acquisition of the lock, and returns where each
     * landed. When this returns, every line has been flushed to disk. If it throws, nothing may be assumed saved except what the caller read back.
     */
    fun append(eventsDir: Path, device: String, events: List<Event>): List<Appended> {
        require(events.isNotEmpty()) { "no events" }
        return appendPlanned(eventsDir, device, events.map { Planned(it) })
    }

    fun append(eventsDir: Path, device: String, event: Event): Appended = append(eventsDir, device, listOf(event)).single()

    /** An event to append, optionally with the time it should carry and the id it must have (an import keeps the source's time and a deterministic id). */
    class Planned(val event: Event, val ts: Long? = null, val id: String? = null)

    /**
     * Like [append], with each event's wished `ts` and `id`. A `ts` is raised to the previous line's plus 1 where it is not greater, because a log's
     * `ts` is strictly increasing (SPEC 5). With [skipIdsInLog], events whose id is already in the log are left out (done under the lock, so devices
     * importing at the same time cannot both append the same event). Returns only what was appended.
     */
    fun appendPlanned(eventsDir: Path, device: String, planned: List<Planned>, skipIdsInLog: Boolean = false): List<Appended> {
        Files.createDirectories(eventsDir)
        val log = eventsDir.resolve("$device.jsonl").toAbsolutePath().normalize()
        val lockFile = eventsDir.resolve("$device.lock").toAbsolutePath().normalize()
        return withDeviceLock(lockFile) {
            FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
                val tail = readTail(ch)
                val have: Set<String> = if (skipIdsInLog) {
                    val bytes = ByteBuffer.allocate(ch.size().toInt()).also { b -> ch.read(b, 0) }.array()
                    Logs.parse(log.fileName.toString(), bytes, ReportBuilder()).map { it.id }.toHashSet()
                } else emptySet()
                val todo = planned.filter { it.id == null || it.id !in have }
                if (todo.isEmpty()) return@use emptyList<Appended>()
                var prev = maxOf(tail.lastTs ?: Long.MIN_VALUE, ownLastTs[log] ?: Long.MIN_VALUE)
                var seq = (tail.lastSeq ?: 0) + 1
                val out = java.io.ByteArrayOutputStream()
                if (!tail.endsWithNewline) out.write('\n'.code) // a previous write was cut off: close its line first
                val result = ArrayList<Appended>(todo.size)
                for (p in todo) {
                    val ts = maxOf(p.ts ?: clock(), prev + 1)
                    val env = RawEnvelope(1, p.id ?: newId(), ts, device, seq, EventCodec.encode(p.event))
                    out.write(env.toLine().toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                    result += Appended(env.id, ts, seq)
                    prev = ts
                    seq++
                }
                val buf = ByteBuffer.wrap(out.toByteArray())
                ch.position(ch.size())
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
                ownLastTs[log] = prev
                result
            }
        }
    }

    private fun <T> withDeviceLock(lockFile: Path, body: () -> T): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockWaitMillis)
        val process = processLocks.computeIfAbsent(lockFile) { ReentrantLock() }
        if (!process.tryLock(lockWaitMillis, TimeUnit.MILLISECONDS)) throw LogBusyException("Another review window of this IDE is writing to the same review")
        try {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockCh ->
                var lock: FileLock? = null
                while (lock == null) {
                    lock = try {
                        lockCh.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (lock == null) {
                        if (System.nanoTime() > deadline) throw LogBusyException("Another instance of this device (another IDE) holds the lock of this review")
                        Thread.sleep(25)
                    }
                }
                try {
                    return body()
                } finally {
                    lock.release()
                }
            }
        } finally {
            process.unlock()
        }
    }

    companion object {
        private val processLocks = ConcurrentHashMap<Path, ReentrantLock>()

        /**
         * The `ts` and `seq` of the last line that is a valid event envelope, found by scanning back from the end (a cut-off fragment, or a
         * fragment a later writer closed with a newline, is skipped), and whether the file ends in `\n`.
         */
        internal fun readTail(ch: FileChannel): LogTail {
            val size = ch.size()
            if (size == 0L) return LogTail(null, null, true)
            val last = ByteBuffer.allocate(1).also { ch.read(it, size - 1) }
            val endsWithNewline = last.get(0) == '\n'.code.toByte()
            var window = 64L * 1024
            while (true) {
                val start = maxOf(0L, size - window)
                val buf = ByteArray((size - start).toInt())
                val bb = ByteBuffer.wrap(buf)
                var pos = start
                while (bb.hasRemaining()) {
                    val n = ch.read(bb, pos)
                    if (n < 0) break
                    pos += n
                }
                var end = buf.size
                var i = buf.size - 1
                while (i >= -1) {
                    if (i == -1 || buf[i] == '\n'.code.toByte()) {
                        val from = i + 1
                        // The first piece of a window that does not start at the beginning of the file may be a partial line.
                        val partial = i == -1 && start > 0
                        if (!partial && end > from) envelopeOfLine(String(buf, from, end - from, Charsets.UTF_8))?.let { return LogTail(it.ts, it.seq, endsWithNewline) }
                        end = i
                    }
                    i--
                }
                if (start == 0L) return LogTail(null, null, endsWithNewline)
                window *= 4
            }
        }

        private fun envelopeOfLine(raw: String): RawEnvelope? {
            val line = raw.removeSuffix("\r")
            if (line.isBlank()) return null
            val parsed = try {
                Json.parseToJsonElement(line)
            } catch (_: Exception) {
                return null
            }
            return (parsed as? JsonObject)?.let(Logs::envelopeOf)
        }
    }
}
