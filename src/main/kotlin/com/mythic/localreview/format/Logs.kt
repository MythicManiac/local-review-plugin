package com.mythic.localreview.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** One line of a device log whose envelope is valid (SPEC 5); the event inside it is still raw JSON. */
data class RawEnvelope(val v: Long, val id: String, val ts: Long, val dev: String, val seq: Long, val ev: JsonObject) {
    /** The line as written to a log: compact JSON, no line terminator. */
    fun toLine(): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("v", v)
        put("id", id)
        put("ts", ts)
        put("dev", dev)
        put("seq", seq)
        put("ev", ev)
    })
}

data class CorruptLine(val file: String, val line: Int)

/** What the reader could not use (SPEC 5.3). A non-zero count is data the reader did not understand; it is never an error. */
data class ReadReport(
    val events: Int = 0,
    val duplicates: Int = 0,
    val unknown: Int = 0,
    val orphans: Int = 0,
    val invalidEnvelopes: Int = 0,
    val tornTails: Int = 0,
    val corruptLines: List<CorruptLine> = emptyList(),
) {
    /** Whether anything in the logs was skipped for a reason other than being a duplicate. */
    val lostData get() = unknown + orphans + invalidEnvelopes + tornTails + corruptLines.size > 0
}

internal class ReportBuilder {
    var events = 0
    var duplicates = 0
    var unknown = 0
    var orphans = 0
    var invalidEnvelopes = 0
    var tornTails = 0
    val corruptLines = ArrayList<CorruptLine>()
    fun build() = ReadReport(events, duplicates, unknown, orphans, invalidEnvelopes, tornTails, corruptLines.toList())
}

/** The bytes of one device log, by file name. */
class LogBytes(val name: String, val bytes: ByteArray)

object Logs {
    /** The envelope of one parsed line, or null if the JSON is not a valid envelope (SPEC 5). */
    internal fun envelopeOf(o: JsonObject): RawEnvelope? {
        val v = o["v"].long() ?: return null
        val id = o["id"].string()?.takeIf { Values.isId(it) } ?: return null
        val ts = o["ts"].long() ?: return null
        val dev = o["dev"].string()?.takeIf { it.isNotEmpty() } ?: return null
        val seq = o["seq"].long() ?: return null
        val ev = o["ev"].obj() ?: return null
        if (ev["type"].string() == null) return null
        return RawEnvelope(v, id, ts, dev, seq, ev)
    }

    /** Parses one device log. Never throws on bad data: every kind of damage is counted in [report] (SPEC 5.3). */
    internal fun parse(name: String, bytes: ByteArray, report: ReportBuilder): List<RawEnvelope> {
        val text = String(bytes, Charsets.UTF_8)
        val terminated = text.endsWith("\n")
        val rows = text.split("\n").toMutableList()
        if (terminated) rows.removeAt(rows.lastIndex)
        val out = ArrayList<RawEnvelope>()
        rows.forEachIndexed { i, raw ->
            val line = if (raw.endsWith("\r")) raw.dropLast(1) else raw
            if (line.isBlank()) return@forEachIndexed
            val parsed = try {
                Json.parseToJsonElement(line)
            } catch (_: Exception) {
                if (i == rows.lastIndex && !terminated) report.tornTails++ else report.corruptLines += CorruptLine(name, i + 1)
                return@forEachIndexed
            }
            val env = (parsed as? JsonObject)?.let(::envelopeOf)
            if (env == null) report.invalidEnvelopes++ else out += env
        }
        return out
    }

    /** The `.jsonl` log files in the `events` folder of a review, in name order. Other files are not logs and are ignored (SPEC 2). */
    fun readDir(reviewDir: File): List<LogBytes> =
        File(reviewDir, "events").listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }.map { LogBytes(it.name, it.readBytes()) }
}

/** Compares strings by their UTF-8 bytes (SPEC 7). */
internal object Utf8Order : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        if (a === b) return 0
        return java.util.Arrays.compareUnsigned(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
