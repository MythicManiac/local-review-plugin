package com.mythic.localreview.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** The state in the shape of the spec's reference implementation (SPEC 7, "Reference state"), so it can be compared with `examples/expected`. */
object ReferenceShape {
    private fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
    private fun pin(p: Pin) = buildJsonObject {
        put("baseSha", p.baseSha)
        put("baseTipSha", p.baseTipSha)
        put("headSha", p.headSha)
    }

    private fun marks(ranges: List<MarkRange>) = JsonArray(ranges.map {
        buildJsonObject {
            put("side", it.side.wire)
            put("startLine", it.startLine)
            put("endLine", it.endLine)
            put("blob", it.blob)
            put("hash", it.hash)
        }
    })

    fun fold(s: ReviewState): JsonObject = buildJsonObject {
        val h = s.header
        put("review", buildJsonObject {
            put("id", h.id)
            put("title", h.title)
            put("status", h.status.wire)
            put("createdAt", h.createdAt)
            put("createdBy", h.createdBy)
            put("repo", buildJsonObject {
                put("displayName", h.repo.displayName)
                put("originUrl", str(h.repo.originUrl))
                put("rootCommit", str(h.repo.rootCommit))
            })
            put("baseRef", h.baseRef)
            put("headRef", h.headRef)
            put("mode", h.mode.wire)
            put("pin", pin(h.pin))
        })
        put("annotations", JsonArray(s.annotations.map { a ->
            buildJsonObject {
                put("id", a.id)
                put("kind", a.kind.wire)
                put("tags", JsonArray(a.tags.map(::JsonPrimitive)))
                put("body", a.body)
                put("ruleText", str(a.ruleText))
                put("status", a.status.wire)
                put("statusNote", str(a.statusNote))
                put("deleted", a.deleted)
                put("createdAt", a.createdAt)
                put("createdBy", a.createdBy)
                put("updatedAt", a.updatedAt)
                put("anchor", EventCodec.encodeAnchor(a.anchor))
                put("ruleDecision", a.ruleDecision?.let { buildJsonObject { put("decision", it.decision.wire); put("targetPath", it.targetPath) } } ?: JsonNull)
                put("replies", JsonArray(a.replies.map { r ->
                    buildJsonObject {
                        put("id", r.id)
                        put("body", r.body)
                        put("deleted", r.deleted)
                        put("createdAt", r.createdAt)
                        put("createdBy", r.createdBy)
                        put("updatedAt", r.updatedAt)
                    }
                }))
            }
        }))
        put("fileMarks", JsonArray(s.fileMarks.map { buildJsonObject { put("path", it.path); put("changeKey", it.changeKey) } }))
        put("regionEvents", JsonArray(s.regionEvents.map { e ->
            buildJsonObject {
                put("id", e.id)
                put("ts", e.ts)
                put("dev", e.dev)
                put("seq", e.seq)
                put("type", if (e.reviewed) "reviewed" else "unreviewed")
                put("path", e.path)
                put("ranges", marks(e.ranges))
            }
        }))
        val r = s.report
        put("report", buildJsonObject {
            put("events", r.events)
            put("duplicates", r.duplicates)
            put("unknown", r.unknown)
            put("orphans", r.orphans)
            put("invalidEnvelopes", r.invalidEnvelopes)
            put("tornTails", r.tornTails)
            put("corruptLines", JsonArray(r.corruptLines.map { buildJsonObject { put("file", it.file); put("line", it.line) } }))
        })
    }

    private fun intervals(l: List<Interval>) = JsonArray(l.map { JsonArray(listOf(JsonPrimitive(it.start), JsonPrimitive(it.end))) })

    fun evaluated(e: Evaluation): JsonObject = buildJsonObject {
        put("pin", pin(e.pin))
        put("annotations", buildJsonObject {
            for ((id, st) in e.annotations) put(id, buildJsonObject {
                when (st) {
                    CommentState.Current -> put("state", "current")
                    CommentState.Unchanged -> put("state", "unchanged")
                    is CommentState.Outdated -> {
                        put("state", "outdated")
                        st.suggestion?.let { sg ->
                            put("suggestion", buildJsonObject {
                                put("path", sg.path)
                                put("ranges", JsonArray(sg.ranges.map { buildJsonObject { put("side", it.side.wire); put("startLine", it.startLine); put("endLine", it.endLine) } }))
                            })
                        }
                    }
                }
            })
        })
        put("fileMarks", JsonArray(e.fileMarks.map { buildJsonObject { put("path", it.path); put("state", it.state.name.lowercase()) } }))
        put("regions", JsonArray(e.regions.map { r ->
            buildJsonObject {
                put("path", r.path)
                put("side", r.side.wire)
                put("reviewed", intervals(r.coverage.reviewed))
                put("stale", JsonArray(r.coverage.stale.map { buildJsonObject { put("blob", it.blob); put("startLine", it.startLine); put("endLine", it.endLine) } }))
            }
        }))
    }

    /** The content of the example repository (`examples/content`): change sets per pin, and the bytes of each blob. */
    class Content(private val dir: File) {
        private val changesets = SpecFiles.json("examples/content/changesets.json").jsonObject

        fun changeSet(pin: Pin): ChangeSet {
            val files = changesets["${pin.baseSha}..${pin.headSha}"]?.jsonArray ?: error("no change set for $pin")
            return ChangeSet(files.map {
                val o = it.jsonObject
                ChangedFile(
                    o.getValue("path").jsonPrimitive.content,
                    (o["oldPath"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content,
                    o.getValue("status").jsonPrimitive.content,
                    o.getValue("oldMode").jsonPrimitive.content,
                    o.getValue("newMode").jsonPrimitive.content,
                    o.getValue("oldBlob").jsonPrimitive.content,
                    o.getValue("newBlob").jsonPrimitive.content,
                )
            })
        }

        val blobs = BlobSource { id -> File(dir, "blobs/$id").takeIf { it.isFile }?.readBytes() }
    }
}
