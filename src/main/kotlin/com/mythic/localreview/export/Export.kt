package com.mythic.localreview.export

import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.Annotation
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.ChangedFile
import com.mythic.localreview.format.CommentState
import com.mythic.localreview.format.EventCodec
import com.mythic.localreview.format.Evaluation
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.Pin
import com.mythic.localreview.format.ReviewState
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.label
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Everything an export needs; build it from the live review so exports always reflect saved state. */
class ExportInput(
    val state: ReviewState,
    val changes: List<ChangedFile>,
    /** Comment states against the code; null if the code is not available here (then states are unknown, not "current"). */
    val evaluation: Evaluation?,
    /** Paths of the files counted as reviewed (a valid file mark, or every changed line covered). */
    val reviewed: Set<String>,
    val generatedAt: String,
) {
    val header get() = state.header

    /** Non-deleted comments only: tombstones are never exported. */
    val comments: List<Annotation> get() = state.annotations.filter { !it.deleted }

    fun isReviewed(f: ChangedFile) = f.path in reviewed

    fun stateOf(c: Annotation): CommentState? {
        val a = c.anchor
        val pin = (a as? Anchor.File)?.pin ?: (a as? Anchor.Lines)?.pin
        if (a is Anchor.Review || pin == header.pin) return CommentState.Current
        return evaluation?.annotations?.get(c.id)
    }

    fun isOutdated(c: Annotation) = stateOf(c) is CommentState.Outdated
}

private val Annotation.open get() = status == AnnotationStatus.OPEN

private fun langOf(path: String?): String = when (path?.substringAfterLast('.', "")?.lowercase()) {
    "kt", "kts" -> "kotlin"; "java" -> "java"; "py" -> "python"; "js", "mjs" -> "javascript"; "ts" -> "typescript"
    "tsx" -> "tsx"; "jsx" -> "jsx"; "go" -> "go"; "rs" -> "rust"; "rb" -> "ruby"; "c", "h" -> "c"
    "cpp", "cc", "hpp" -> "cpp"; "cs" -> "csharp"; "sh" -> "bash"; "json" -> "json"; "xml" -> "xml"; "yml", "yaml" -> "yaml"
    "md" -> "markdown"; "sql" -> "sql"; "html" -> "html"; "css" -> "css"; "toml" -> "toml"
    else -> ""
}

private fun anchorPath(c: Annotation): String? = when (val a = c.anchor) {
    Anchor.Review -> null
    is Anchor.File -> a.path
    is Anchor.Lines -> a.path
}

private fun range(start: Int, end: Int) = if (start == end) "$start" else "$start-$end"

private fun sideWord(s: Side) = if (s == Side.OLD) "base" else "head"

/** Human-readable location, e.g. `src/a.kt:12-14 (head)`. [outdated] adds a note. */
fun locationOf(c: Annotation, outdated: Boolean = false): String = when (val a = c.anchor) {
    Anchor.Review -> "(whole review)"
    is Anchor.File -> "`${a.path}` (file${if (outdated) ", outdated" else ""})"
    is Anchor.Lines -> {
        val parts = a.ranges.joinToString(", ") { "${sideWord(it.side)} ${range(it.startLine, it.endLine)}" }
        "`${a.path}` ($parts${if (outdated) ", outdated" else ""})"
    }
}

private fun fence(code: List<String>, lang: String): String {
    val body = code.joinToString("\n")
    val ticks = "`".repeat(maxOf(3, (Regex("`+").findAll(body).maxOfOrNull { it.value.length } ?: 0) + 1))
    return "$ticks$lang\n$body\n$ticks"
}

private fun quote(text: String) = text.trim().lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }

private fun snippets(c: Annotation): List<Pair<String?, List<String>>> {
    val a = c.anchor as? Anchor.Lines ?: return emptyList()
    return a.ranges.filter { it.snapshot.lines.isNotEmpty() }.map { (if (a.ranges.size > 1) sideWord(it.side) else null) to it.snapshot.lines }
}

object MarkdownReport {

    fun render(input: ExportInput): String {
        val h = input.header
        val live = input.comments
        val sb = StringBuilder()
        val viewed = input.changes.count { input.isReviewed(it) }
        val open = live.filter { it.open }

        sb.append("# Code review: ${h.title}\n\n")
        sb.append("- Repository: ${h.repo.displayName}\n")
        sb.append("- Compared: `${h.baseRef}` (${h.pin.baseTipSha.take(8)}) → `${h.headRef}` (${h.pin.headSha.take(8)})")
        if (h.pin.baseSha != h.pin.baseTipSha) sb.append(", diffed from merge-base ${h.pin.baseSha.take(8)}")
        sb.append("\n- Status: ${h.status.label} · generated ${input.generatedAt}\n")
        sb.append("- Files reviewed: $viewed of ${input.changes.size}\n")
        sb.append(
            "- Open: ${open.count { it.kind == Kind.CHANGE_REQUEST }} change requests, " +
                "${open.count { it.kind == Kind.QUESTION }} questions, ${open.count { it.kind == Kind.COMMENT }} notes\n\n",
        )

        val questions = open.filter { it.kind == Kind.QUESTION && !input.isOutdated(it) }
        if (questions.isNotEmpty()) {
            sb.append("## Open questions\n\n")
            questions.sortedWith(order()).forEachIndexed { i, c -> sb.append(entry(input, "Q${i + 1}", c, withKind = false)) }
        }

        val findings = open.filter { it.kind != Kind.QUESTION && !input.isOutdated(it) }
        if (findings.isNotEmpty()) {
            sb.append("## Findings by file\n\n")
            val general = findings.filter { it.anchor is Anchor.Review }
            if (general.isNotEmpty()) {
                sb.append("### General\n\n")
                general.forEach { sb.append(entry(input, null, it, withKind = true)) }
            }
            val byFile = findings.filter { it.anchor !is Anchor.Review }.groupBy { anchorPath(it)!! }.toSortedMap()
            for ((path, cs) in byFile) {
                sb.append("### `$path`\n\n")
                cs.sortedWith(order()).forEach { sb.append(entry(input, null, it, withKind = true)) }
            }
        }

        val outdated = open.filter { input.isOutdated(it) }
        if (outdated.isNotEmpty()) {
            sb.append("## Outdated (code changed since the comment was made)\n\n")
            outdated.sortedWith(order()).forEach { sb.append(entry(input, null, it, withKind = true)) }
        }

        val rules = AgentsMd.candidates(live)
        if (rules.rules.isNotEmpty() || rules.needsWording.isNotEmpty()) {
            sb.append("## Proposed AGENTS.md rules\n\n")
            rules.rules.forEach { sb.append("- ${it.text}  \n  _from ${it.sources.joinToString(", ") { s -> locationOf(s) }}_\n") }
            for (c in rules.needsWording) {
                sb.append("- _Needs wording:_ ${c.body.trim().lineSequence().first().take(160)}  \n  _from ${locationOf(c)}, id ${c.id}_\n")
            }
            sb.append('\n')
        }

        val closed = live.filter { !it.open }
        if (closed.isNotEmpty()) {
            sb.append("## Resolved / won't fix\n\n")
            closed.sortedWith(order()).forEach {
                sb.append("- [${it.status.label}] ${it.kind.label} at ${locationOf(it)}: ${it.body.trim().lineSequence().first()}\n")
            }
            sb.append('\n')
        }

        val unviewed = input.changes.filter { !input.isReviewed(it) }
        if (unviewed.isNotEmpty()) {
            sb.append("## Files not marked as reviewed\n\n")
            unviewed.forEach { sb.append("- `${it.path}`\n") }
            sb.append('\n')
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun order() = compareBy<Annotation>({ anchorPath(it) ?: "" }, { (it.anchor as? Anchor.Lines)?.ranges?.minOf { r -> r.startLine } ?: 0 }, { it.createdAt }, { it.id })

    private fun entry(input: ExportInput, label: String?, c: Annotation, withKind: Boolean): String {
        val sb = StringBuilder()
        val title = buildString {
            if (label != null) append("**$label.** ")
            if (withKind) append("**${c.kind.label}** ")
            append(locationOf(c, input.isOutdated(c)))
        }
        sb.append("$title  \n")
        sb.append("_id: ${c.id}_\n\n")
        for ((side, lines) in snippets(c)) {
            if (side != null) sb.append("_${side}:_\n\n")
            sb.append(fence(lines, langOf(anchorPath(c)))).append("\n\n")
        }
        sb.append(quote(c.body)).append("\n\n")
        for (r in c.replies.filter { !it.deleted }) sb.append("Reply: ").append(quote(r.body).removePrefix("> ")).append("\n\n")
        if (c.isRuleCandidate) sb.append(if (!c.ruleText.isNullOrBlank()) "Rule candidate: ${c.ruleText}\n\n" else "Rule candidate (needs wording)\n\n")
        return sb.toString()
    }
}

object JsonReport {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private fun pin(p: Pin) = buildJsonObject {
        put("baseSha", p.baseSha)
        put("baseTipSha", p.baseTipSha)
        put("headSha", p.headSha)
    }

    fun render(input: ExportInput): String {
        val h = input.header
        val rules = AgentsMd.candidates(input.comments)
        val report = buildJsonObject {
            put("schemaVersion", 2)
            put("generatedAt", input.generatedAt)
            put("review", buildJsonObject {
                put("id", h.id)
                put("title", h.title)
                put("status", h.status.wire)
                put("repo", h.repo.displayName)
                put("baseRef", h.baseRef)
                put("headRef", h.headRef)
                put("mode", h.mode.wire)
                put("pin", pin(h.pin))
            })
            put("files", JsonArray(input.changes.map { f ->
                buildJsonObject {
                    put("path", f.path)
                    f.oldPath?.let { put("oldPath", it) }
                    put("status", f.status)
                    put("reviewed", input.isReviewed(f))
                }
            }))
            put("comments", JsonArray(input.comments.map { c ->
                buildJsonObject {
                    put("id", c.id)
                    put("kind", c.kind.wire)
                    put("status", c.status.wire)
                    c.statusNote?.let { put("statusNote", it) }
                    put("body", c.body)
                    putJsonArray("tags") { c.tags.forEach { add(JsonPrimitive(it)) } }
                    c.ruleText?.let { put("ruleText", it) }
                    put("anchor", EventCodec.encodeAnchor(c.anchor))
                    put("state", when (val s = input.stateOf(c)) {
                        CommentState.Current -> "current"
                        CommentState.Unchanged -> "unchanged"
                        is CommentState.Outdated -> "outdated"
                        null -> "unverified"
                    })
                    put("createdAt", c.createdAt)
                    put("replies", JsonArray(c.replies.filter { !it.deleted }.map { r ->
                        buildJsonObject {
                            put("id", r.id)
                            put("body", r.body)
                            put("createdAt", r.createdAt)
                        }
                    }))
                }
            }))
            putJsonArray("proposedRules") { rules.rules.forEach { add(JsonPrimitive(it.text)) } }
            putJsonArray("rulesNeedingWording") { rules.needsWording.forEach { add(JsonPrimitive(it.id)) } }
        }
        return json.encodeToString(JsonObject.serializer(), report) + "\n"
    }
}

object AgentPrompts {
    /** A prompt for the whole review: instructions + the open items of the Markdown report. */
    fun forReview(input: ExportInput): String {
        val h = input.header
        val rules = AgentsMd.candidates(input.comments)
        return buildString {
            append("You are working on branch `${h.headRef}` (compared to `${h.baseRef}`). A human reviewed your changes locally and ")
            append("left the feedback below. Address it as follows.\n\n")
            append("1. **Change requests**: make the requested change. If you disagree or it is infeasible, say why instead of silently skipping it.\n")
            append("2. **Questions**: answer each one explicitly. Only change code if your answer reveals a defect.\n")
            append("3. **Notes**: consider them; act only where they clearly apply.\n")
            append("4. Refer to feedback by its `id` when you report back. List what you changed, what you answered, and what you declined.\n")
            if (rules.rules.isNotEmpty() || rules.needsWording.isNotEmpty()) {
                append("5. **AGENTS.md**: the \"Proposed AGENTS.md rules\" section lists recurring issues the reviewer wants prevented in future work. ")
                append("Update the repository's AGENTS.md with concise, general rules covering them (merge with existing rules, ")
                append("avoid duplicates, do not copy review-specific details).")
                if (rules.needsWording.isNotEmpty()) append(" Items marked \"needs wording\" have no rule text yet: draft one from the comment.")
                append('\n')
            }
            append("\nDo not touch code that no feedback item refers to.\n\n---\n\n")
            append(MarkdownReport.render(input))
        }
    }

    fun forComment(input: ExportInput, c: Annotation): String = buildString {
        append("Code review feedback on branch `${input.header.headRef}`")
        if (c.anchor !is Anchor.Review) append(" at ${locationOf(c)}")
        append(" (${c.kind.label.lowercase()}, id ${c.id}):\n\n")
        for ((side, lines) in snippets(c)) {
            if (side != null) append("_${side}:_\n\n")
            append(fence(lines, langOf(anchorPath(c)))).append("\n\n")
        }
        append(c.body.trim()).append("\n\n")
        for (r in c.replies.filter { !it.deleted }) append("Reply: ").append(r.body.trim()).append("\n\n")
        append(
            when (c.kind) {
                Kind.CHANGE_REQUEST -> "Please make this change (or explain why it should not be made)."
                Kind.QUESTION -> "Please answer this question. Only change code if the answer reveals a defect."
                Kind.COMMENT -> "Please take this note into account."
            },
        )
        if (c.isRuleCandidate) {
            append(if (!c.ruleText.isNullOrBlank()) "\n\nAlso consider adding this rule to AGENTS.md: ${c.ruleText}" else "\n\nThe reviewer flagged this as a recurring pattern: consider adding a concise rule to AGENTS.md.")
        }
        append('\n')
    }
}

data class RuleCandidate(val text: String, val sources: List<Annotation>)

/** The rule inbox (SPEC 6.2): comments tagged `rule_candidate` and not yet decided. [needsWording] are tagged ones with no rule text. */
data class RuleCandidates(val rules: List<RuleCandidate>, val needsWording: List<Annotation>)

object AgentsMd {
    const val HEADING = "## Rules from local code review"

    /**
     * Distinct (whitespace/case-insensitive) rule texts from live comments that carry the rule tag, are not "won't fix" and have no decision,
     * in first-seen order; tagged comments without a rule text are listed apart as needing wording.
     */
    fun candidates(comments: List<Annotation>): RuleCandidates {
        val waiting = comments.filter { it.isRuleCandidate && it.status != AnnotationStatus.WONT_FIX && !it.deleted && it.ruleDecision == null }.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val byKey = LinkedHashMap<String, MutableList<Annotation>>()
        waiting.filter { !it.ruleText.isNullOrBlank() }.forEach { byKey.getOrPut(normalize(it.ruleText!!)) { mutableListOf() } += it }
        return RuleCandidates(byKey.values.map { RuleCandidate(it.first().ruleText!!.trim(), it) }, waiting.filter { it.ruleText.isNullOrBlank() })
    }

    private fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * [existing] AGENTS.md text with the rules appended under [HEADING] (created if missing). Rules already present
     * anywhere in the file (normalised) are skipped, so re-running after a re-review is idempotent.
     */
    fun propose(existing: String, rules: List<RuleCandidate>): String {
        val have = normalize(existing)
        val fresh = rules.filter { !have.contains(normalize(it.text)) }
        if (fresh.isEmpty()) return existing
        val lines = fresh.joinToString("\n") { "- ${it.text}" }
        val nl = if (existing.contains("\r\n")) "\r\n" else "\n"
        val bullets = lines.replace("\n", nl)
        val idx = existing.indexOf(HEADING)
        if (idx < 0) {
            val base = existing.trimEnd()
            val prefix = if (base.isEmpty()) "" else base + nl + nl
            return prefix + HEADING + nl + nl + bullets + nl
        }
        // Append to the end of the existing section (until the next heading or EOF).
        val afterHeading = existing.indexOf('\n', idx).let { if (it < 0) existing.length else it + 1 }
        val next = Regex("(?m)^#{1,6} ").find(existing, afterHeading)?.range?.first ?: existing.length
        val section = existing.substring(afterHeading, next).trimEnd()
        val rest = existing.substring(next)
        return existing.substring(0, afterHeading) + section + nl + bullets + nl + (if (rest.isNotEmpty()) nl + rest else "")
    }
}
