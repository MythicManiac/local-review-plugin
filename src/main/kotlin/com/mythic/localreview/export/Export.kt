package com.mythic.localreview.export

import com.mythic.localreview.core.AnchorScope
import com.mythic.localreview.core.Comment
import com.mythic.localreview.core.CommentKind
import com.mythic.localreview.core.CommentStatus
import com.mythic.localreview.core.FileChange
import com.mythic.localreview.core.FileProgress
import com.mythic.localreview.core.Review
import com.mythic.localreview.core.Side
import com.mythic.localreview.core.StoreJson
import kotlinx.serialization.Serializable

/** Everything an export needs; build it from the live store so exports always reflect saved state. */
class ExportInput(
    val review: Review,
    /** Non-deleted comments only (tombstones are never exported). */
    val comments: List<Comment>,
    val changes: List<FileChange>,
    val progress: Map<String, FileProgress>,
    val generatedAt: String,
) {
    fun isViewed(c: FileChange) = progress[c.path]?.viewedFingerprint.let { it != null && it == c.fingerprint }
}

private val Comment.open get() = status == CommentStatus.OPEN

private fun langOf(path: String?): String = when (path?.substringAfterLast('.', "")?.lowercase()) {
    "kt", "kts" -> "kotlin"; "java" -> "java"; "py" -> "python"; "js", "mjs" -> "javascript"; "ts" -> "typescript"
    "tsx" -> "tsx"; "jsx" -> "jsx"; "go" -> "go"; "rs" -> "rust"; "rb" -> "ruby"; "c", "h" -> "c"
    "cpp", "cc", "hpp" -> "cpp"; "cs" -> "csharp"; "sh" -> "bash"; "json" -> "json"; "xml" -> "xml"; "yml", "yaml" -> "yaml"
    "md" -> "markdown"; "sql" -> "sql"; "html" -> "html"; "css" -> "css"; "toml" -> "toml"
    else -> ""
}

/** Human-readable location, e.g. `src/a.kt:12-14 (head)`. */
fun locationOf(c: Comment): String {
    val a = c.anchor
    return when (a.scope) {
        AnchorScope.REVIEW -> "(whole review)"
        AnchorScope.FILE -> "`${a.path}` (file)"
        AnchorScope.LINES -> {
            val range = if (a.startLine == a.endLine) "${a.startLine}" else "${a.startLine}-${a.endLine}"
            "`${a.path}:$range` (${if (a.side == Side.BASE) "base" else "head"}${if (c.outdated) ", outdated" else ""})"
        }
    }
}

private fun fence(code: List<String>, lang: String): String {
    val body = code.joinToString("\n")
    val ticks = "`".repeat(maxOf(3, (Regex("`+").findAll(body).maxOfOrNull { it.value.length } ?: 0) + 1))
    return "$ticks$lang\n$body\n$ticks"
}

private fun quote(text: String) = text.trim().lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }

object MarkdownReport {

    fun render(input: ExportInput): String {
        val r = input.review
        val rev = r.current
        val live = input.comments
        val sb = StringBuilder()
        val viewed = input.changes.count { input.isViewed(it) }
        val open = live.filter { it.open }

        sb.append("# Code review: ${r.title}\n\n")
        sb.append("- Repository: ${r.repo.name}\n")
        sb.append("- Compared: `${r.baseRef}` (${rev.baseSha.take(8)}) → `${r.headRef}` (${rev.headSha.take(8)})")
        if (rev.effectiveBaseSha != rev.baseSha) sb.append(", diffed from merge-base ${rev.effectiveBaseSha.take(8)}")
        sb.append("\n- Review revision: ${rev.n} · generated ${input.generatedAt}\n")
        sb.append("- Files reviewed: $viewed of ${input.changes.size}\n")
        sb.append(
            "- Open: ${open.count { it.kind == CommentKind.CHANGE_REQUEST }} change requests, " +
                "${open.count { it.kind == CommentKind.QUESTION }} questions, ${open.count { it.kind == CommentKind.NOTE }} notes\n\n",
        )

        val questions = open.filter { it.kind == CommentKind.QUESTION && !it.outdated }
        if (questions.isNotEmpty()) {
            sb.append("## Open questions\n\n")
            questions.sortedWith(order()).forEachIndexed { i, c -> sb.append(entry("Q${i + 1}", c, withKind = false)) }
        }

        val findings = open.filter { it.kind != CommentKind.QUESTION && !it.outdated }
        if (findings.isNotEmpty()) {
            sb.append("## Findings by file\n\n")
            val general = findings.filter { it.anchor.scope == AnchorScope.REVIEW }
            if (general.isNotEmpty()) {
                sb.append("### General\n\n")
                general.forEach { sb.append(entry(null, it, withKind = true)) }
            }
            val byFile = findings.filter { it.anchor.scope != AnchorScope.REVIEW }.groupBy { it.anchor.path!! }.toSortedMap()
            for ((path, cs) in byFile) {
                sb.append("### `$path`\n\n")
                cs.sortedWith(order()).forEach { sb.append(entry(null, it, withKind = true)) }
            }
        }

        val outdated = open.filter { it.outdated }
        if (outdated.isNotEmpty()) {
            sb.append("## Outdated (code changed since the comment was made)\n\n")
            outdated.sortedWith(order()).forEach { sb.append(entry(null, it, withKind = true)) }
        }

        val rules = AgentsMd.candidates(live)
        if (rules.isNotEmpty()) {
            sb.append("## Proposed AGENTS.md rules\n\n")
            rules.forEach { sb.append("- ${it.text}  \n  _from ${it.sources.joinToString(", ") { s -> locationOf(s) }}_\n") }
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

        val unviewed = input.changes.filter { !input.isViewed(it) }
        if (unviewed.isNotEmpty()) {
            sb.append("## Files not marked as reviewed\n\n")
            unviewed.forEach { sb.append("- `${it.path}`\n") }
            sb.append('\n')
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun order() = compareBy<Comment>({ it.anchor.path ?: "" }, { it.anchor.startLine ?: 0 }, { it.createdAt })

    private fun entry(label: String?, c: Comment, withKind: Boolean): String {
        val sb = StringBuilder()
        val title = buildString {
            if (label != null) append("**$label.** ")
            if (withKind) append("**${c.kind.label}** ")
            append(locationOf(c))
        }
        sb.append("$title  \n")
        sb.append("_id: ${c.id}_\n\n")
        if (c.anchor.scope == AnchorScope.LINES && c.anchor.snippet.isNotEmpty()) {
            sb.append(fence(c.anchor.snippet, langOf(c.anchor.path))).append("\n\n")
        }
        sb.append(quote(c.body)).append("\n\n")
        if (c.rule != null) sb.append("Rule candidate: ${c.rule}\n\n")
        return sb.toString()
    }
}

@Serializable
data class ReportFile(val path: String, val oldPath: String? = null, val status: String, val viewed: Boolean)

@Serializable
data class ReportJson(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val review: Review,
    val files: List<ReportFile>,
    val comments: List<Comment>,
    val proposedRules: List<String>,
)

object JsonReport {
    fun render(input: ExportInput): String = StoreJson.encodeToString(
        ReportJson.serializer(),
        ReportJson(
            generatedAt = input.generatedAt,
            review = input.review,
            files = input.changes.map { ReportFile(it.path, it.oldPath.takeIf { o -> o != it.path }, it.status.name, input.isViewed(it)) },
            comments = input.comments,
            proposedRules = AgentsMd.candidates(input.comments).map { it.text },
        ),
    ) + "\n"
}

object AgentPrompts {
    /** A prompt for the whole review: instructions + the open items of the Markdown report. */
    fun forReview(input: ExportInput): String {
        val r = input.review
        val hasRules = AgentsMd.candidates(input.comments).isNotEmpty()
        return buildString {
            append("You are working on branch `${r.headRef}` (compared to `${r.baseRef}`). A human reviewed your changes locally and ")
            append("left the feedback below. Address it as follows.\n\n")
            append("1. **Change requests**: make the requested change. If you disagree or it is infeasible, say why instead of silently skipping it.\n")
            append("2. **Questions**: answer each one explicitly. Only change code if your answer reveals a defect.\n")
            append("3. **Notes**: consider them; act only where they clearly apply.\n")
            append("4. Refer to feedback by its `id` when you report back. List what you changed, what you answered, and what you declined.\n")
            if (hasRules) {
                append("5. **AGENTS.md**: the \"Proposed AGENTS.md rules\" section lists recurring issues the reviewer wants prevented in future work. ")
                append("Update the repository's AGENTS.md with concise, general rules covering them (merge with existing rules, ")
                append("avoid duplicates, do not copy review-specific details).\n")
            }
            append("\nDo not touch code that no feedback item refers to.\n\n---\n\n")
            append(MarkdownReport.render(input))
        }
    }

    fun forComment(review: Review, c: Comment): String = buildString {
        append("Code review feedback on branch `${review.headRef}`")
        if (c.anchor.scope != AnchorScope.REVIEW) append(" at ${locationOf(c)}")
        append(" (${c.kind.label.lowercase()}, id ${c.id}):\n\n")
        if (c.anchor.scope == AnchorScope.LINES && c.anchor.snippet.isNotEmpty()) {
            append(fence(c.anchor.snippet, langOf(c.anchor.path))).append("\n\n")
        }
        append(c.body.trim()).append("\n\n")
        append(
            when (c.kind) {
                CommentKind.CHANGE_REQUEST -> "Please make this change (or explain why it should not be made)."
                CommentKind.QUESTION -> "Please answer this question. Only change code if the answer reveals a defect."
                CommentKind.NOTE -> "Please take this note into account."
            },
        )
        if (c.rule != null) append("\n\nAlso consider adding this rule to AGENTS.md: ${c.rule}")
        append('\n')
    }
}

data class RuleCandidate(val text: String, val sources: List<Comment>)

object AgentsMd {
    const val HEADING = "## Rules from local code review"

    /** Distinct (whitespace/case-insensitive) rule texts from live, non-"won't fix" comments, in first-seen order. */
    fun candidates(comments: List<Comment>): List<RuleCandidate> {
        val byKey = LinkedHashMap<String, MutableList<Comment>>()
        comments.filter { it.rule != null && it.status != CommentStatus.WONT_FIX && !it.deleted }
            .sortedBy { it.createdAt }
            .forEach { byKey.getOrPut(normalize(it.rule!!)) { mutableListOf() } += it }
        return byKey.values.map { RuleCandidate(it.first().rule!!.trim(), it) }
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
