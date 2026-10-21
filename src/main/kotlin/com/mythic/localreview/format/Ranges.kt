package com.mythic.localreview.format

/** Which side of a diff a line number refers to: the base (`old`) or the head (`new`). */
enum class Side(val wire: String) {
    OLD("old"),
    NEW("new");

    companion object {
        fun fromWire(s: String?): Side? = entries.firstOrNull { it.wire == s }
    }
}

/** The text of an anchored range at the time of the comment, with the lines just above and below it. */
data class Snapshot(val lines: List<String>, val before: List<String>, val after: List<String>)

/** One range of a lines anchor: 1-based inclusive lines on one side, and what they said (SPEC 6). */
data class AnchorRange(val side: Side, val startLine: Int, val endLine: Int, val snapshot: Snapshot)

/** One range of a region mark: lines on one side, the blob they were read from, and the hash of those lines (ALGORITHMS 3). */
data class MarkRange(val side: Side, val startLine: Int, val endLine: Int, val blob: String, val hash: String)
