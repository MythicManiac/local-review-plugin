package com.mythic.localreview.export

import com.mythic.localreview.core.Anchor
import com.mythic.localreview.core.AnchorScope
import com.mythic.localreview.core.ChangeStatus
import com.mythic.localreview.core.Comment
import com.mythic.localreview.core.CommentKind
import com.mythic.localreview.core.CommentStatus
import com.mythic.localreview.core.FileChange
import com.mythic.localreview.core.FileProgress
import com.mythic.localreview.core.RepoInfo
import com.mythic.localreview.core.Review
import com.mythic.localreview.core.Revision
import com.mythic.localreview.core.Side
import com.mythic.localreview.core.StoreJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportTest {
    private val review = Review(
        id = "r", title = "Feature A vs master", repo = RepoInfo("proj"), baseRef = "master", headRef = "feature/a",
        revisions = listOf(Revision(1, "t", "b".repeat(40), "h".repeat(40), "m".repeat(40))),
        createdAt = "t", updatedAt = "t",
    )

    private fun c(
        id: String, kind: CommentKind, body: String, path: String? = "src/a.kt", line: Int = 3, end: Int = line,
        status: CommentStatus = CommentStatus.OPEN, rule: String? = null, outdated: Boolean = false, deleted: Boolean = false,
        scope: AnchorScope = if (path == null) AnchorScope.REVIEW else AnchorScope.LINES,
    ) = Comment(
        id = id, kind = kind, status = status, body = body,
        anchor = Anchor(scope, path, Side.HEAD, if (scope == AnchorScope.LINES) line else null, if (scope == AnchorScope.LINES) end else null, if (scope == AnchorScope.LINES) listOf("val x = 1") else emptyList()),
        outdated = outdated, rule = rule, createdAt = "2026-01-01T00:00:0$id", updatedAt = "t", deletedAt = if (deleted) "t" else null,
    )

    private val files = listOf(
        FileChange(ChangeStatus.MODIFIED, "src/a.kt", "src/a.kt", "o1", "n1"),
        FileChange(ChangeStatus.ADDED, null, "src/b.kt", "", "n2"),
    )

    private fun input(vararg comments: Comment, viewedA: Boolean = true) = ExportInput(
        review, comments.filter { !it.deleted }, files,
        if (viewedA) mapOf("src/a.kt" to FileProgress(path = "src/a.kt", viewedFingerprint = files[0].fingerprint, updatedAt = "t")) else emptyMap(),
        "2026-02-02T00:00:00Z",
    )

    @Test
    fun reportOrdersQuestionsFirstAndGroupsByFile() {
        val md = MarkdownReport.render(
            input(
                c("1", CommentKind.CHANGE_REQUEST, "rename this", "src/b.kt", 7),
                c("2", CommentKind.QUESTION, "why a map here?", "src/a.kt", 3, 4),
                c("3", CommentKind.NOTE, "general thought", null),
            ),
        )
        assertTrue(md.indexOf("## Open questions") < md.indexOf("## Findings by file"))
        assertTrue(md.contains("**Q1.** `src/a.kt:3-4` (head)"), md)
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
                c("1", CommentKind.NOTE, "SECRET-DELETED", deleted = true),
                c("2", CommentKind.CHANGE_REQUEST, "done already", status = CommentStatus.RESOLVED),
            ),
        )
        assertFalse(md.contains("SECRET-DELETED"))
        assertTrue(md.contains("## Resolved / won't fix") && md.contains("[Resolved]"))
        assertFalse(md.contains("## Findings by file"), "resolved items are not open findings")
    }

    @Test
    fun outdatedIsFlagged() {
        val md = MarkdownReport.render(input(c("1", CommentKind.CHANGE_REQUEST, "stale", outdated = true)))
        assertTrue(md.contains("## Outdated"))
        assertTrue(md.contains("outdated)"))
    }

    @Test
    fun ruleCandidatesAreDeduplicatedAndSkipWontFix() {
        val cs = listOf(
            c("1", CommentKind.CHANGE_REQUEST, "x", rule = "Never swallow exceptions"),
            c("2", CommentKind.CHANGE_REQUEST, "y", path = "src/b.kt", rule = "  never   swallow EXCEPTIONS "),
            c("3", CommentKind.NOTE, "z", rule = "Ignored", status = CommentStatus.WONT_FIX),
            c("4", CommentKind.NOTE, "w", rule = "Prefer sealed classes"),
        )
        val rules = AgentsMd.candidates(cs)
        assertEquals(listOf("Never swallow exceptions", "Prefer sealed classes"), rules.map { it.text })
        assertEquals(2, rules[0].sources.size)
        val md = MarkdownReport.render(input(*cs.toTypedArray()))
        assertTrue(md.contains("## Proposed AGENTS.md rules") && md.contains("Prefer sealed classes"))
        assertFalse(md.contains("- Ignored"))
    }

    @Test
    fun agentsMdProposalCreatesAppendsAndIsIdempotent() {
        val rules = AgentsMd.candidates(listOf(c("1", CommentKind.NOTE, "x", rule = "Rule one"), c("2", CommentKind.NOTE, "y", rule = "Rule two")))

        val fresh = AgentsMd.propose("", rules)
        assertEquals("${AgentsMd.HEADING}\n\n- Rule one\n- Rule two\n", fresh)

        val existing = "# Project\n\nBe nice.\n\n## Other\n\nStuff\n"
        val appended = AgentsMd.propose(existing, rules)
        assertTrue(appended.startsWith("# Project\n\nBe nice.\n\n## Other\n\nStuff\n\n${AgentsMd.HEADING}\n\n- Rule one"), appended)

        assertEquals(appended, AgentsMd.propose(appended, rules), "second run changes nothing")

        // New rule joins the existing section instead of creating a second heading.
        val more = AgentsMd.candidates(listOf(c("3", CommentKind.NOTE, "x", rule = "Rule three")))
        val merged = AgentsMd.propose("${AgentsMd.HEADING}\n\n- Rule one\n\n## After\n\nz\n", more)
        assertEquals("${AgentsMd.HEADING}\n\n- Rule one\n- Rule three\n\n## After\n\nz\n", merged)
        assertEquals(1, Regex(Regex.escape(AgentsMd.HEADING)).findAll(merged).count())
    }

    @Test
    fun agentsMdProposalPreservesCrLf() {
        val rules = AgentsMd.candidates(listOf(c("1", CommentKind.NOTE, "x", rule = "Rule one")))
        val out = AgentsMd.propose("# T\r\n\r\ntext\r\n", rules)
        assertFalse(out.replace("\r\n", "").contains("\n"), "no bare LF introduced")
    }

    @Test
    fun jsonReportRoundTripsAndCarriesIds() {
        val json = JsonReport.render(input(c("1", CommentKind.QUESTION, "q?", rule = "R")))
        val parsed = StoreJson.decodeFromString(ReportJson.serializer(), json)
        assertEquals(listOf("1"), parsed.comments.map { it.id })
        assertEquals(listOf("R"), parsed.proposedRules)
        assertEquals(listOf(true, false), parsed.files.map { it.viewed })
        assertEquals("feature/a", parsed.review.headRef)
    }

    @Test
    fun agentPromptsMentionAgentsMdOnlyWithRulesAndCommentPromptHasContext() {
        val without = AgentPrompts.forReview(input(c("1", CommentKind.CHANGE_REQUEST, "x")))
        assertFalse(without.contains("AGENTS.md**"))
        val withRules = AgentPrompts.forReview(input(c("1", CommentKind.CHANGE_REQUEST, "x", rule = "R")))
        assertTrue(withRules.contains("AGENTS.md**"))

        val p = AgentPrompts.forComment(review, c("9", CommentKind.QUESTION, "why?", "src/a.kt", 3, 5, rule = "R"))
        assertTrue(p.contains("src/a.kt:3-5") && p.contains("val x = 1") && p.contains("why?") && p.contains("answer this question"))
        assertTrue(p.contains("Also consider adding this rule to AGENTS.md: R"))
    }

    @Test
    fun codeFenceGrowsWhenSnippetContainsBackticks() {
        val cm = c("1", CommentKind.NOTE, "n").let { it.copy(anchor = it.anchor.copy(snippet = listOf("```", "x"))) }
        val md = MarkdownReport.render(input(cm))
        assertTrue(md.contains("````kotlin\n```\nx\n````"), md)
    }
}
