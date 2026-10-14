package com.mythic.localreview.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnchoringTest {
    private fun anchorFor(lines: List<String>, start: Int, end: Int): Anchor {
        val c = Anchoring.capture(lines, start, end)
        return Anchor(AnchorScope.LINES, "f", Side.HEAD, start, end, c.snippet, c.before, c.after)
    }

    private val orig = (1..20).map { "line $it" }

    @Test
    fun captureTakesContext() {
        val c = Anchoring.capture(orig, 5, 6)
        assertEquals(listOf("line 5", "line 6"), c.snippet)
        assertEquals(listOf("line 2", "line 3", "line 4"), c.before)
        assertEquals(listOf("line 7", "line 8", "line 9"), c.after)
    }

    @Test
    fun followsInsertionsAbove() {
        val a = anchorFor(orig, 10, 11)
        val moved = listOf("new a", "new b") + orig
        assertEquals(Anchoring.Located(12, 13), Anchoring.locate(a, moved))
    }

    @Test
    fun duplicateSnippetPrefersMatchingContext() {
        val file = listOf("a", "x", "b", "c", "d", "x", "e", "f")
        val a = Anchor(AnchorScope.LINES, "f", Side.HEAD, 6, 6, listOf("x"), listOf("b", "c", "d"), listOf("e", "f"))
        // Move the whole thing; both x lines remain but context only matches the second.
        val moved = listOf("zz") + file
        assertEquals(Anchoring.Located(7, 7), Anchoring.locate(a, moved))
    }

    @Test
    fun whitespaceOnlyChangeStillLocates() {
        val a = anchorFor(orig, 3, 3)
        val reindented = orig.toMutableList().also { it[2] = "    line 3" }
        assertEquals(Anchoring.Located(3, 3), Anchoring.locate(a, reindented))
    }

    @Test
    fun editedSingleLineIsOutdated() {
        val a = anchorFor(orig, 3, 3)
        val edited = orig.toMutableList().also { it[2] = "something else" }
        assertNull(Anchoring.locate(a, edited))
    }

    @Test
    fun mostlySameMultiLineSnippetStillLocates() {
        val a = anchorFor(orig, 5, 9) // 5 lines
        val edited = orig.toMutableList().also { it[5] = "changed" } // 1/5 lines differ -> 80% similar
        assertEquals(Anchoring.Located(5, 9), Anchoring.locate(a, edited))
    }

    @Test
    fun heavilyEditedMultiLineSnippetIsOutdated() {
        val a = anchorFor(orig, 5, 9)
        val edited = orig.toMutableList().also { for (i in 4..7) it[i] = "x$i" }
        assertNull(Anchoring.locate(a, edited))
    }

    @Test
    fun splitLinesHandlesCrLfAndTrailingNewline() {
        assertEquals(listOf("a", "b"), Anchoring.splitLines("a\r\nb\r\n"))
        assertEquals(listOf("a", "b"), Anchoring.splitLines("a\nb"))
        assertEquals(emptyList(), Anchoring.splitLines(""))
    }
}
