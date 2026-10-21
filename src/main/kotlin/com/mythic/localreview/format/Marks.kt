package com.mythic.localreview.format

/**
 * Where the marked lines of [range] are in [lines] now (ALGORITHMS 3.1): the same place if they still hash alike there,
 * else the one window of the same length that does, else null (also null if [lines] is null: that side does not exist or is
 * not text).
 */
fun resolveMark(range: MarkRange, lines: Lines?): Interval? {
    if (lines == null) return null
    val n = range.endLine - range.startLine + 1
    if (range.endLine <= lines.size && lines.hashRange(range.startLine - 1, range.endLine) == range.hash) {
        return Interval(range.startLine, range.endLine)
    }
    var found: Interval? = null
    for (i in 0..lines.size - n) {
        if (lines.hashRange(i, i + n) != range.hash) continue
        if (found != null) return null // ambiguous: never guess
        found = Interval(i + 1, i + n)
    }
    return found
}

/** One step of region evaluation: a range that was marked reviewed or unreviewed. */
data class MarkStep(val reviewed: Boolean, val range: MarkRange)

/** A range that was reviewed once and cannot be placed now: [startLine]..[endLine] are numbered in [blob] (ALGORITHMS 3.2). */
data class StaleRange(val blob: String, val startLine: Int, val endLine: Int)

/** What the marks of one path and side add up to: the lines reviewed in the current file, and the ranges that cannot be placed. */
data class RegionCoverage(val reviewed: List<Interval>, val stale: List<StaleRange>)

/** Evaluates the steps of one path and side, in fold order, against the current [lines] of that side (ALGORITHMS 3.2). */
fun evaluateSteps(steps: List<MarkStep>, lines: Lines?): RegionCoverage {
    var covered: List<Interval> = emptyList()
    val staleByBlob = LinkedHashMap<String, List<Interval>>()
    for ((reviewed, range) in steps) {
        val at = resolveMark(range, lines)
        if (at != null) {
            covered = if (reviewed) addInterval(covered, at.start, at.end) else subtractInterval(covered, at.start, at.end)
        } else {
            val list = staleByBlob[range.blob].orEmpty()
            staleByBlob[range.blob] = if (reviewed) addInterval(list, range.startLine, range.endLine) else subtractInterval(list, range.startLine, range.endLine)
        }
    }
    val stale = staleByBlob.flatMap { (blob, list) -> list.map { StaleRange(blob, it.start, it.end) } }
        .sortedWith(compareBy<StaleRange> { it.startLine }.thenBy { it.blob })
    return RegionCoverage(covered, stale)
}
