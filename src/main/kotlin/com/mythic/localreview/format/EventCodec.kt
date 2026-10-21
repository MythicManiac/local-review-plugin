package com.mythic.localreview.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Values of SPEC 3. */
object Values {
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val SHA = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
    private val HASH = Regex("^[0-9a-f]{64}$")

    fun isId(s: String?) = s != null && ID.matches(s)
    fun isSha(s: String?) = s != null && SHA.matches(s)
    fun isHash(s: String?) = s != null && HASH.matches(s)
}

// ---- reading helpers: every one returns null for "absent or of the wrong type" ----

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/** An integral JSON number (`3` or `3.0`), not a string. */
internal fun JsonElement?.long(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    p.content.toLongOrNull()?.let { return it }
    val d = p.content.toDoubleOrNull() ?: return null
    return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9.0e15) d.toLong() else null
}

internal fun JsonElement?.strings(): List<String>? {
    val a = this as? JsonArray ?: return null
    return a.map { it.string() ?: return null }
}

internal fun JsonObject.has(key: String) = containsKey(key)

/** Decoding of events and anchors, and the reverse (SPEC 6). Decoding returns null for anything that is not a valid event of a known type. */
object EventCodec {
    fun decodePin(e: JsonElement?): Pin? {
        val o = e.obj() ?: return null
        val b = o["baseSha"].string()
        val t = o["baseTipSha"].string()
        val h = o["headSha"].string()
        return if (Values.isSha(b) && Values.isSha(t) && Values.isSha(h)) Pin(b!!, t!!, h!!) else null
    }

    private fun lineSpan(o: JsonObject): Triple<Side, Int, Int>? {
        val side = Side.fromWire(o["side"].string()) ?: return null
        val s = o["startLine"].long() ?: return null
        val e = o["endLine"].long() ?: return null
        if (s < 1 || e < s || e > Int.MAX_VALUE) return null
        return Triple(side, s.toInt(), e.toInt())
    }

    private fun anchorRange(e: JsonElement): AnchorRange? {
        val o = e.obj() ?: return null
        val (side, s, en) = lineSpan(o) ?: return null
        val snap = o["snapshot"].obj() ?: return null
        val lines = snap["lines"].strings() ?: return null
        val before = snap["before"].strings() ?: return null
        val after = snap["after"].strings() ?: return null
        return AnchorRange(side, s, en, Snapshot(lines, before, after))
    }

    private fun markRange(e: JsonElement): MarkRange? {
        val o = e.obj() ?: return null
        val (side, s, en) = lineSpan(o) ?: return null
        val blob = o["blob"].string()?.takeIf { it.isNotEmpty() } ?: return null
        val hash = o["hash"].string()?.takeIf { Values.isHash(it) } ?: return null
        return MarkRange(side, s, en, blob, hash)
    }

    private fun <T> ranges(e: JsonElement?, one: (JsonElement) -> T?, sideOf: (T) -> Side): List<T>? {
        val a = e as? JsonArray ?: return null
        if (a.size < 1 || a.size > 2) return null
        val out = a.map { one(it) ?: return null }
        return if (out.map(sideOf).toSet().size == out.size) out else null
    }

    fun decodeAnchor(e: JsonElement?): Anchor? {
        val o = e.obj() ?: return null
        when (o["scope"].string()) {
            "review" -> return Anchor.Review
            "file" -> {
                val path = o["path"].string()?.takeIf { it.isNotEmpty() } ?: return null
                val p = decodePin(o["pin"]) ?: return null
                return Anchor.File(path, o["oldPath"].string(), p)
            }
            "lines" -> {
                val path = o["path"].string()?.takeIf { it.isNotEmpty() } ?: return null
                val p = decodePin(o["pin"]) ?: return null
                val rs = ranges(o["ranges"], ::anchorRange) { it.side } ?: return null
                return Anchor.Lines(path, o["oldPath"].string(), p, rs)
            }
        }
        return null
    }

    /** The event inside an envelope, or null if it is not a valid event of a type this reader knows (SPEC 5.3: "unknown"). */
    fun decode(e: JsonObject): Event? {
        val type = e["type"].string() ?: return null
        val annotationId = e["annotationId"].string()?.takeIf { Values.isId(it) }
        val replyId = e["replyId"].string()?.takeIf { Values.isId(it) }
        val body = e["body"].string()
        return when (type) {
            "reviewRenamed" -> e["title"].string()?.let { Event.ReviewRenamed(it) }
            "reviewStatusChanged" -> ReviewStatus.fromWire(e["status"].string())?.let { Event.ReviewStatusChanged(it) }
            "reviewRetargeted" -> decodePin(e["pin"])?.let { Event.ReviewRetargeted(it) }
            "annotationCreated" -> {
                val kind = Kind.fromWire(e["kind"].string())
                val tags = e["tags"].strings()
                val anchor = decodeAnchor(e["anchor"])
                val rule = e["ruleText"]
                if (annotationId == null || kind == null || tags == null || body == null || anchor == null) null
                else if (rule != null && rule.string() == null) null
                else Event.AnnotationCreated(annotationId, kind, tags, body, anchor, rule.string())
            }
            "annotationEdited" -> {
                if (annotationId == null) return null
                if (e.has("body") && body == null) return null
                val kind = if (e.has("kind")) Kind.fromWire(e["kind"].string()) ?: return null else null
                val tags = if (e.has("tags")) e["tags"].strings() ?: return null else null
                val rule = e["ruleText"]
                val ruleText = when {
                    !e.has("ruleText") -> null
                    rule is JsonNull -> Field(null)
                    rule.string() != null -> Field(rule.string())
                    else -> return null
                }
                Event.AnnotationEdited(annotationId, body, kind, tags, ruleText)
            }
            "annotationStatusChanged" -> {
                val status = AnnotationStatus.fromWire(e["status"].string())
                if (annotationId == null || status == null) return null
                if (e.has("note") && e["note"].string() == null) return null
                Event.AnnotationStatusChanged(annotationId, status, e["note"].string())
            }
            "annotationReanchored" -> {
                val anchor = decodeAnchor(e["anchor"])
                if (annotationId == null || anchor == null) return null
                val method = if (e.has("method")) ReanchorMethod.fromWire(e["method"].string()) ?: return null else null
                Event.AnnotationReanchored(annotationId, anchor, method)
            }
            "annotationDeleted" -> annotationId?.let { Event.AnnotationDeleted(it) }
            "annotationRestored" -> annotationId?.let { Event.AnnotationRestored(it) }
            "replyAdded" -> if (annotationId != null && replyId != null && body != null) Event.ReplyAdded(annotationId, replyId, body) else null
            "replyEdited" -> if (annotationId != null && replyId != null && body != null) Event.ReplyEdited(annotationId, replyId, body) else null
            "replyDeleted" -> if (annotationId != null && replyId != null) Event.ReplyDeleted(annotationId, replyId) else null
            "replyRestored" -> if (annotationId != null && replyId != null) Event.ReplyRestored(annotationId, replyId) else null
            "fileReviewed" -> {
                val path = e["path"].string()?.takeIf { it.isNotEmpty() }
                val key = e["changeKey"].string()?.takeIf { it.isNotEmpty() }
                if (path != null && key != null) Event.FileReviewed(path, key) else null
            }
            "fileUnreviewed" -> e["path"].string()?.takeIf { it.isNotEmpty() }?.let { Event.FileUnreviewed(it) }
            "regionReviewed", "regionUnreviewed" -> {
                val path = e["path"].string()?.takeIf { it.isNotEmpty() } ?: return null
                val rs = ranges(e["ranges"], ::markRange) { it.side } ?: return null
                if (type == "regionReviewed") Event.RegionReviewed(path, rs) else Event.RegionUnreviewed(path, rs)
            }
            "ruleDecided" -> {
                val ids = e["annotationIds"].strings()?.takeIf { l -> l.all { Values.isId(it) } }
                val target = e["targetPath"].string()
                val decision = Decision.fromWire(e["decision"].string())
                if (ids != null && target != null && decision != null) Event.RuleDecided(ids, target, decision) else null
            }
            else -> null
        }
    }

    // ---- encoding ----

    private fun encodePin(p: Pin) = buildJsonObject {
        put("baseSha", p.baseSha)
        put("baseTipSha", p.baseTipSha)
        put("headSha", p.headSha)
    }

    fun encodeAnchor(a: Anchor): JsonObject = buildJsonObject {
        when (a) {
            Anchor.Review -> put("scope", "review")
            is Anchor.File -> {
                put("scope", "file")
                put("path", a.path)
                a.oldPath?.let { put("oldPath", it) }
                put("pin", encodePin(a.pin))
            }
            is Anchor.Lines -> {
                put("scope", "lines")
                put("path", a.path)
                a.oldPath?.let { put("oldPath", it) }
                put("pin", encodePin(a.pin))
                put("ranges", JsonArray(a.ranges.map { r ->
                    buildJsonObject {
                        put("side", r.side.wire)
                        put("startLine", r.startLine)
                        put("endLine", r.endLine)
                        put("snapshot", buildJsonObject {
                            putJsonArray("lines") { r.snapshot.lines.forEach { add(JsonPrimitive(it)) } }
                            putJsonArray("before") { r.snapshot.before.forEach { add(JsonPrimitive(it)) } }
                            putJsonArray("after") { r.snapshot.after.forEach { add(JsonPrimitive(it)) } }
                        })
                    }
                }))
            }
        }
    }

    private fun encodeMarks(ranges: List<MarkRange>) = JsonArray(ranges.map { r ->
        buildJsonObject {
            put("side", r.side.wire)
            put("startLine", r.startLine)
            put("endLine", r.endLine)
            put("blob", r.blob)
            put("hash", r.hash)
        }
    })

    fun encode(e: Event): JsonObject = buildJsonObject {
        put("type", e.type)
        when (e) {
            is Event.ReviewRenamed -> put("title", e.title)
            is Event.ReviewStatusChanged -> put("status", e.status.wire)
            is Event.ReviewRetargeted -> put("pin", encodePin(e.pin))
            is Event.AnnotationCreated -> {
                put("annotationId", e.annotationId)
                put("kind", e.kind.wire)
                putJsonArray("tags") { e.tags.forEach { add(JsonPrimitive(it)) } }
                put("body", e.body)
                e.ruleText?.let { put("ruleText", it) }
                put("anchor", encodeAnchor(e.anchor))
            }
            is Event.AnnotationEdited -> {
                put("annotationId", e.annotationId)
                e.body?.let { put("body", it) }
                e.kind?.let { put("kind", it.wire) }
                e.tags?.let { tags -> putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } } }
                e.ruleText?.let { put("ruleText", it.value?.let(::JsonPrimitive) ?: JsonNull) }
            }
            is Event.AnnotationStatusChanged -> {
                put("annotationId", e.annotationId)
                put("status", e.status.wire)
                e.note?.let { put("note", it) }
            }
            is Event.AnnotationReanchored -> {
                put("annotationId", e.annotationId)
                put("anchor", encodeAnchor(e.anchor))
                e.method?.let { put("method", it.wire) }
            }
            is Event.AnnotationDeleted -> put("annotationId", e.annotationId)
            is Event.AnnotationRestored -> put("annotationId", e.annotationId)
            is Event.ReplyAdded -> {
                put("annotationId", e.annotationId)
                put("replyId", e.replyId)
                put("body", e.body)
            }
            is Event.ReplyEdited -> {
                put("annotationId", e.annotationId)
                put("replyId", e.replyId)
                put("body", e.body)
            }
            is Event.ReplyDeleted -> {
                put("annotationId", e.annotationId)
                put("replyId", e.replyId)
            }
            is Event.ReplyRestored -> {
                put("annotationId", e.annotationId)
                put("replyId", e.replyId)
            }
            is Event.FileReviewed -> {
                put("path", e.path)
                put("changeKey", e.changeKey)
            }
            is Event.FileUnreviewed -> put("path", e.path)
            is Event.RegionReviewed -> {
                put("path", e.path)
                put("ranges", encodeMarks(e.ranges))
            }
            is Event.RegionUnreviewed -> {
                put("path", e.path)
                put("ranges", encodeMarks(e.ranges))
            }
            is Event.RuleDecided -> {
                putJsonArray("annotationIds") { e.annotationIds.forEach { add(JsonPrimitive(it)) } }
                put("targetPath", e.targetPath)
                put("decision", e.decision.wire)
            }
        }
    }
}
