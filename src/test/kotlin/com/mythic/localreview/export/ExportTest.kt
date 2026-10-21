package com.mythic.localreview.export

import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.AnchorRange
import com.mythic.localreview.format.Annotation
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.ChangedFile
import com.mythic.localreview.format.CommentState
import com.mythic.localreview.format.Decision
import com.mythic.localreview.format.Evaluation
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.Pin
import com.mythic.localreview.format.RULE_CANDIDATE_TAG
import com.mythic.localreview.format.ReadReport
import com.mythic.localreview.format.RepoRef
import com.mythic.localreview.format.Reply
import com.mythic.localreview.format.ReviewHeader
import com.mythic.localreview.format.ReviewMode
import com.mythic.localreview.format.ReviewState
import com.mythic.localreview.format.ReviewStatus
import com.mythic.localreview.format.RuleDecision
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.Snapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportTest {
    private val pin = Pin("m".repeat(40), "b".repeat(40), "h".repeat(40))
    private val oldPin = Pin("1".repeat(40), "2".repeat(40), "3".repeat(40))
    private val header = ReviewHeader("r", "Feature A vs master", ReviewStatus.OPEN, 1, "dev", RepoRef("proj", null, null), "master", "feature/a", ReviewMode.MERGE_BASE, pin)

    private fun c(
        id: String, kind: Kind, body: String, path: String? = "src/a.kt", line: Int = 3, end: Int = line,
        status: AnnotationStatus = AnnotationStatus.OPEN, ruleText: String? = null, tagged: Boolean = ruleText != null, deleted: Boolean = false,
        decision: Decision? = null, replies: List<String> = emptyList(), at: Pin = pin, snippet: List<String> = listOf("val x = 1"),
    ) = Annotation(
        id = id, kind = kind, tags = if (tagged) listOf(RULE_CANDIDATE_TAG) else emptyList(), body = body, ruleText = ruleText, status = status, statusNote = null,
        deleted = deleted, createdAt = id.filter { it.isDigit() }.toLongOrNull() ?: 0, createdBy = "dev", updatedAt = 0,
        anchor = if (path == null) Anchor.Review else Anchor.Lines(path, null, at, listOf(AnchorRange(Side.NEW, line, end, Snapshot(snippet, emptyList(), emptyList())))),
        ruleDecision = decision?.let { RuleDecision(it, "AGENTS.md") },
        replies = replies.mapIndexed { i, b -> Reply("p$i", b, false, 10L + i, "dev", 10L + i) },
    )

    private val files = listOf(
        ChangedFile("src/a.kt", null, "modified", "100644", "100644", "o1", "n1"),
        ChangedFile("src/b.kt", null, "added", "000000", "100644", "0".repeat(40), "n2"),
    )

    private fun input(vararg comments: Annotation, reviewed: Set<String> = setOf("src/a.kt"), evaluation: Evaluation? = null) = ExportInput(
        ReviewState(header, comments.toList(), emptyList(), emptyList(), ReadReport()), files, evaluation, reviewed, "2026-02-02T00:00:00Z",
    )

    @Test
    fun reportOrdersQuestionsFirstAndGroupsByFile() {
        val md = MarkdownReport.render(
            input(
                c("1", Kind.CHANGE_REQUEST, "rename this", "src/b.kt", 7),
                c("2", Kind.QUESTION, "why a map here?", "src/a.kt", 3, 4),
                c("3", Kind.COMMENT, "general thought", null),
            ),
        )
        assertTrue(md.indexOf("## Open questions") < md.indexOf("## Findings by file"))
        assertTrue(md.contains("**Q1.** `src/a.kt` (head 3-4)"), md)
        assertTrue(md.contains("### General") && md.contains("general thought"))
        assertTrue(md.contains("### `src/b.kt`") && md.contains("rename this"))
        assertTrue(md.contains("```\nval x = 1\n```") || md.contains("val x = 1"))
        assertTrue(md.contains("Files reviewed: 1 of 2"))
        assertTrue(md.contains("## Files not marked as reviewed") && md.contains("- `src/b.kt`"))
        assertTrue(md.contains("1 change requests, 1 questions, 1 notes"))
    }

    @Test
    fun tombstonesAreNeverExportedAndResolvedAreSeparate() {
        val md = MarkdownReport.render(
            input(
                c("1", Kind.COMMENT, "SECRET-DELETED", deleted = true),
                c("2", Kind.CHANGE_REQUEST, "done already", status = AnnotationStatus.RESOLVED),
            ),
        )
        assertFalse(md.contains("SECRET-DELETED"))
        assertTrue(md.contains("## Resolved / won't fix") && md.contains("[Resolved]"))
        assertFalse(md.contains("## Findings by file"), "resolved items are not open findings")
    }

    @Test
    fun outdatedIsFlaggedFromTheEvaluation() {
        val old = c("1", Kind.CHANGE_REQUEST, "stale", at = oldPin)
        val evaluation = Evaluation(pin, mapOf("1" to CommentState.Outdated(null)), emptyList(), emptyList())
        val md = MarkdownReport.render(input(old, evaluation = evaluation))
        assertTrue(md.contains("## Outdated"))
        assertTrue(md.contains("outdated)"))
        // The same comment against code that is not available is not called outdated: nobody knows.
        val unverified = MarkdownReport.render(input(old, evaluation = null))
        assertFalse(unverified.contains("## Outdated"))
        assertTrue(unverified.contains("## Findings by file"), "unknown state is not hidden from the report")
    }

    @Test
    fun ruleInboxIsTaggedUndecidedCommentsGroupedByRuleText() {
        val cs = listOf(
            c("1", Kind.CHANGE_REQUEST, "x", ruleText = "Never swallow exceptions"),
            c("2", Kind.CHANGE_REQUEST, "y", path = "src/b.kt", ruleText = "  never   swallow EXCEPTIONS "),
            c("3", Kind.COMMENT, "z", ruleText = "Ignored", status = AnnotationStatus.WONT_FIX),
            c("4", Kind.COMMENT, "w", ruleText = "Prefer sealed classes"),
            c("5", Kind.COMMENT, "already handled", ruleText = "Decided rule", decision = Decision.APPLIED),
            c("6", Kind.CHANGE_REQUEST, "flagged but not worded", tagged = true),
            c("7", Kind.COMMENT, "text without the flag", ruleText = "Written but not flagged", tagged = false),
            c("8", Kind.COMMENT, "gone", ruleText = "Deleted one", deleted = true),
        )
        val inbox = AgentsMd.candidates(cs)
        assertEquals(listOf("Never swallow exceptions", "Prefer sealed classes"), inbox.rules.map { it.text })
        assertEquals(2, inbox.rules[0].sources.size)
        assertEquals(listOf("6"), inbox.needsWording.map { it.id }, "a tag without text asks for wording; text without the tag is not collected")
        val md = MarkdownReport.render(input(*cs.toTypedArray()))
        assertTrue(md.contains("## Proposed AGENTS.md rules") && md.contains("Prefer sealed classes"))
        assertTrue(md.contains("Needs wording"), md)
        assertFalse(md.contains("- Ignored") || md.contains("- Decided rule") || md.contains("- Written but not flagged"))
    }

    @Test
    fun agentsMdProposalCreatesAppendsAndIsIdempotent() {
        val rules = AgentsMd.candidates(listOf(c("1", Kind.COMMENT, "x", ruleText = "Rule one"), c("2", Kind.COMMENT, "y", ruleText = "Rule two"))).rules

        val fresh = AgentsMd.propose("", rules)
        assertEquals("${AgentsMd.HEADING}\n\n- Rule one\n- Rule two\n", fresh)

        val existing = "# Project\n\nBe nice.\n\n## Other\n\nStuff\n"
        val appended = AgentsMd.propose(existing, rules)
        assertTrue(appended.startsWith("# Project\n\nBe nice.\n\n## Other\n\nStuff\n\n${AgentsMd.HEADING}\n\n- Rule one"), appended)

        assertEquals(appended, AgentsMd.propose(appended, rules), "second run changes nothing")

        // New rule joins the existing section instead of creating a second heading.
        val more = AgentsMd.candidates(listOf(c("3", Kind.COMMENT, "x", ruleText = "Rule three"))).rules
        val merged = AgentsMd.propose("${AgentsMd.HEADING}\n\n- Rule one\n\n## After\n\nz\n", more)
        assertEquals("${AgentsMd.HEADING}\n\n- Rule one\n- Rule three\n\n## After\n\nz\n", merged)
        assertEquals(1, Regex(Regex.escape(AgentsMd.HEADING)).findAll(merged).count())
    }

    @Test
    fun agentsMdProposalPreservesCrLf() {
        val rules = AgentsMd.candidates(listOf(c("1", Kind.COMMENT, "x", ruleText = "Rule one"))).rules
        val out = AgentsMd.propose("# T\r\n\r\ntext\r\n", rules)
        assertFalse(out.replace("\r\n", "").contains("\n"), "no bare LF introduced")
    }

    @Test
    fun repliesAreInReportsAndPromptsButDeletedOnesAreNot() {
        val withReplies = c("1", Kind.QUESTION, "why?", replies = listOf("because of the cache", "second thought"))
        val deleted = withReplies.copy(replies = withReplies.replies + Reply("gone", "SECRET-REPLY", true, 99, "dev", 99))
        val md = MarkdownReport.render(input(deleted))
        assertTrue(md.contains("because of the cache") && md.contains("second thought"))
        assertFalse(md.contains("SECRET-REPLY"))
        assertTrue(AgentPrompts.forComment(input(deleted), deleted).contains("because of the cache"))
        val json = Json.parseToJsonElement(JsonReport.render(input(deleted))).jsonObject
        val replies = json.getValue("comments").jsonArray.single().jsonObject.getValue("replies").jsonArray
        assertEquals(listOf("because of the cache", "second thought"), replies.map { it.jsonObject.getValue("body").jsonPrimitive.content })
    }

    @Test
    fun jsonReportCarriesIdsStatesAndRules() {
        val json = Json.parseToJsonElement(JsonReport.render(input(c("1", Kind.QUESTION, "q?", ruleText = "R"), c("2", Kind.COMMENT, "n", tagged = true)))).jsonObject
        val comments = json.getValue("comments").jsonArray.map { it.jsonObject }
        assertEquals(listOf("1", "2"), comments.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("R"), json.getValue("proposedRules").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("2"), json.getValue("rulesNeedingWording").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(true, false), json.getValue("files").jsonArray.map { it.jsonObject.getValue("reviewed").jsonPrimitive.content.toBoolean() })
        assertEquals("feature/a", json.getValue("review").jsonObject.getValue("headRef").jsonPrimitive.content)
        assertEquals("current", comments[0].getValue("state").jsonPrimitive.content)
        // A comment from an older pin has no state when the code is not available.
        val unverified = Json.parseToJsonElement(JsonReport.render(input(c("3", Kind.COMMENT, "old", at = oldPin)))).jsonObject
        assertEquals("unverified", unverified.getValue("comments").jsonArray.single().jsonObject.getValue("state").jsonPrimitive.content)
    }

    @Test
    fun agentPromptsMentionAgentsMdOnlyWithRulesAndCommentPromptHasContext() {
        val without = AgentPrompts.forReview(input(c("1", Kind.CHANGE_REQUEST, "x")))
        assertFalse(without.contains("AGENTS.md**"))
        val withRules = AgentPrompts.forReview(input(c("1", Kind.CHANGE_REQUEST, "x", ruleText = "R")))
        assertTrue(withRules.contains("AGENTS.md**"))
        val tagOnly = AgentPrompts.forReview(input(c("1", Kind.CHANGE_REQUEST, "x", tagged = true)))
        assertTrue(tagOnly.contains("needs wording"), "a tag-only candidate asks the agent to draft the rule")

        val one = c("9", Kind.QUESTION, "why?", "src/a.kt", 3, 5, ruleText = "R")
        val p = AgentPrompts.forComment(input(one), one)
        assertTrue(p.contains("src/a.kt") && p.contains("head 3-5") && p.contains("val x = 1") && p.contains("why?") && p.contains("answer this question"))
        assertTrue(p.contains("Also consider adding this rule to AGENTS.md: R"))
    }

    @Test
    fun codeFenceGrowsWhenSnippetContainsBackticks() {
        val md = MarkdownReport.render(input(c("1", Kind.COMMENT, "n", snippet = listOf("```", "x"))))
        assertTrue(md.contains("````kotlin\n```\nx\n````"), md)
    }
}
