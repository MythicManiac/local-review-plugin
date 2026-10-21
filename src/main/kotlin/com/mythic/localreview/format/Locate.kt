package com.mythic.localreview.format

private const val FUZZY = 0.7

/**
 * Where the code of an anchor range is in [lines] now, for a suggestion (ALGORITHMS 4): exact after `trimEnd`, else after
 * `trim`, else (3 lines or more) at least 70% of lines equal after `trim`; the best of the matching windows by context score,
 * then distance from the old start line, then the smaller start line. Null if nothing matches.
 */
fun locateRange(range: AnchorRange, lines: List<String>): Interval? {
    val snip = range.snapshot.lines
    val n = snip.size
    if (n == 0 || lines.size < n) return null
    val before = range.snapshot.before
    val after = range.snapshot.after

    fun score(i: Int): Int {
        var s = 0
        // `before` is compared nearest-first: its last line against the line right above the window.
        for (k in before.indices) {
            val idx = i - 1 - k
            if (idx >= 0 && trimSpec(lines[idx]) == trimSpec(before[before.size - 1 - k])) s++
        }
        for (k in after.indices) {
            val idx = i + n + k
            if (idx < lines.size && trimSpec(lines[idx]) == trimSpec(after[k])) s++
        }
        return s
    }

    fun pick(match: (Int) -> Boolean): Interval? {
        var best = -1
        var bestScore = 0
        var bestDistance = 0
        for (i in 0..lines.size - n) {
            if (!match(i)) continue
            val s = score(i)
            val d = Math.abs(i + 1 - range.startLine)
            if (best < 0 || s > bestScore || (s == bestScore && d < bestDistance)) {
                best = i
                bestScore = s
                bestDistance = d
            }
        }
        return if (best < 0) null else Interval(best + 1, best + n)
    }

    pick { i -> (0 until n).all { k -> trimEndSpec(lines[i + k]) == trimEndSpec(snip[k]) } }?.let { return it }
    pick { i -> (0 until n).all { k -> trimSpec(lines[i + k]) == trimSpec(snip[k]) } }?.let { return it }
    if (n >= 3) {
        pick { i -> (0 until n).count { k -> trimSpec(lines[i + k]) == trimSpec(snip[k]) }.toDouble() / n >= FUZZY }?.let { return it }
    }
    return null
}
