package com.mythic.localreview.core

import kotlin.math.abs

object Anchoring {
    const val CONTEXT_LINES = 3
    private const val FUZZY_THRESHOLD = 0.7

    data class Captured(val snippet: List<String>, val before: List<String>, val after: List<String>)

    data class Located(val start: Int, val end: Int)

    /** Captures the selected lines (1-based inclusive) plus [CONTEXT_LINES] of context on each side. */
    fun capture(lines: List<String>, start: Int, end: Int): Captured {
        val s = (start - 1).coerceIn(0, lines.size)
        val e = end.coerceIn(s, lines.size)
        return Captured(
            snippet = lines.subList(s, e).toList(),
            before = lines.subList((s - CONTEXT_LINES).coerceAtLeast(0), s).toList(),
            after = lines.subList(e, (e + CONTEXT_LINES).coerceAtMost(lines.size)).toList(),
        )
    }

    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split("\r\n", "\n")
        return if (text.endsWith("\n")) lines.dropLast(1) else lines
    }

    /**
     * Finds where [anchor]'s snippet now lives in [lines] (the target file content), or null if it no longer does.
     * Preference: exact match (ignoring trailing whitespace) > whitespace-insensitive match > >=70% line-wise similar
     * window for multi-line snippets. Among candidates, matching surrounding context wins, then proximity to the old line.
     */
    fun locate(anchor: Anchor, lines: List<String>): Located? {
        val snippet = anchor.snippet
        val n = snippet.size
        val oldStart = anchor.startLine ?: return null
        if (n == 0 || lines.size < n) return null

        fun candidates(match: (Int) -> Boolean) = (0..lines.size - n).filter(match)

        fun pick(cands: List<Int>): Located? = cands.maxWithOrNull(
            compareBy<Int> { contextScore(anchor, lines, it, n) }.thenBy { -abs(it + 1 - oldStart) },
        )?.let { Located(it + 1, it + n) }

        pick(candidates { i -> (0 until n).all { lines[i + it].trimEnd() == snippet[it].trimEnd() } })?.let { return it }
        pick(candidates { i -> (0 until n).all { lines[i + it].trim() == snippet[it].trim() } })?.let { return it }
        if (n >= 3) {
            val scored = (0..lines.size - n).associateWith { i ->
                (0 until n).count { lines[i + it].trim() == snippet[it].trim() }.toDouble() / n
            }
            val ok = scored.filterValues { it >= FUZZY_THRESHOLD }.keys.toList()
            pick(ok)?.let { return it }
        }
        return null
    }

    private fun contextScore(anchor: Anchor, lines: List<String>, i: Int, n: Int): Int {
        var score = 0
        for ((k, b) in anchor.before.asReversed().withIndex()) {
            val idx = i - 1 - k
            if (idx >= 0 && lines[idx].trim() == b.trim()) score++
        }
        for ((k, a) in anchor.after.withIndex()) {
            val idx = i + n + k
            if (idx < lines.size && lines[idx].trim() == a.trim()) score++
        }
        return score
    }
}
