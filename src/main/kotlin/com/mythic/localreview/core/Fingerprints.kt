package com.mythic.localreview.core

import java.security.MessageDigest

object Fingerprints {
    fun sha1Hex(text: String): String = MessageDigest.getInstance("SHA-1")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Stable short key for per-file progress files. */
    fun pathKey(path: String): String = sha1Hex(path).take(16)

    /**
     * Fingerprint of one diff hunk, independent of line numbers so it survives unrelated edits above it.
     * [leftLines]/[rightLines] are the changed line ranges on each side exactly as the diff viewer shows them.
     * Identical hunks in one file are disambiguated by [occurrence] (0 for the first).
     */
    fun hunk(leftLines: List<String>, rightLines: List<String>, occurrence: Int = 0): String =
        sha1Hex("L${leftLines.size}\n${leftLines.joinToString("\n")}\nR${rightLines.size}\n${rightLines.joinToString("\n")}\n#$occurrence")
            .take(16)

    /** Fingerprints for all hunks of a file in order, assigning occurrence indexes to duplicates. */
    fun hunks(hunks: List<Pair<List<String>, List<String>>>): List<String> {
        val seen = HashMap<String, Int>()
        return hunks.map { (l, r) ->
            val base = hunk(l, r, 0)
            val occ = seen.merge(base, 1, Int::plus)!! - 1
            if (occ == 0) base else hunk(l, r, occ)
        }
    }
}
