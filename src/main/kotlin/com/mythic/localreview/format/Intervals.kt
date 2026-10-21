package com.mythic.localreview.format

/** An inclusive range of 1-based line numbers. */
data class Interval(val start: Int, val end: Int) {
    init {
        require(end >= start) { "empty interval $start..$end" }
    }

    val length get() = end - start + 1
    operator fun contains(line: Int) = line in start..end
}

/** Adds `[start, end]`, merging every interval it overlaps or touches (ALGORITHMS 2). The list stays sorted and disjoint. */
fun addInterval(list: List<Interval>, start: Int, end: Int): List<Interval> {
    val out = ArrayList<Interval>(list.size + 1)
    var ns = start
    var ne = end
    for (i in list) {
        if (i.end + 1 < ns || i.start > ne + 1) out += i
        else {
            ns = minOf(ns, i.start)
            ne = maxOf(ne, i.end)
        }
    }
    out += Interval(ns, ne)
    out.sortBy { it.start }
    return out
}

/** Removes `[start, end]`, splitting an interval whose middle is removed (ALGORITHMS 2). */
fun subtractInterval(list: List<Interval>, start: Int, end: Int): List<Interval> {
    val out = ArrayList<Interval>(list.size + 1)
    for (i in list) {
        if (i.end < start || i.start > end) out += i
        else {
            if (i.start < start) out += Interval(i.start, start - 1)
            if (i.end > end) out += Interval(end + 1, i.end)
        }
    }
    return out
}
