package com.mythic.localreview.format

/** The lines a group's `.gitattributes` and `.gitignore` must contain, in this order (SPEC 2.1). */
object RepoFiles {
    val GITATTRIBUTES = listOf("* -text", "*.jsonl merge=union")
    val GITIGNORE = listOf("*.lock", "*.tmp", "/reviews/*/local/")

    private fun lines(text: String) = text.split("\n").map { if (it.endsWith('\r')) it.dropLast(1) else it }

    /**
     * Opening a group (SPEC 2.1): [text] with every required line that is missing appended, in the order given. Existing
     * content is never changed, and a text that has every line is returned as it is. A missing file is "".
     */
    fun appendMissing(text: String, required: List<String>): String {
        val have = lines(text).toHashSet()
        val missing = required.filter { it !in have }
        if (missing.isEmpty()) return text
        val base = if (text.isEmpty() || text.endsWith("\n")) text else "$text\n"
        return base + missing.joinToString("\n") + "\n"
    }

    /**
     * A merge conflict in one of these files (SPEC 9.3): the other lines of either version (without blank lines or duplicates),
     * sorted by their UTF-8 bytes, then the required lines in their order, so no other line can override them. Symmetric.
     */
    fun merge(required: List<String>, ours: String, theirs: String): String {
        val req = required.toHashSet()
        val others = (lines(ours) + lines(theirs)).filter { it.isNotEmpty() && it !in req }.toSortedSet(Utf8Order)
        return (others + required).joinToString("\n") + "\n"
    }

    private object Utf8Order : Comparator<String> {
        override fun compare(a: String, b: String) = java.util.Arrays.compareUnsigned(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
