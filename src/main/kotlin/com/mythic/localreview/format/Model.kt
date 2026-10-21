package com.mythic.localreview.format

/** The three commits a set of line numbers is valid for (SPEC 4): the diff's left side, what the base ref resolved to, and the head. */
data class Pin(val baseSha: String, val baseTipSha: String, val headSha: String)

enum class Kind(val wire: String) {
    CHANGE_REQUEST("changeRequest"),
    QUESTION("question"),
    COMMENT("comment");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

enum class AnnotationStatus(val wire: String) {
    OPEN("open"),
    RESOLVED("resolved"),
    WONT_FIX("wontFix");

    val closed get() = this != OPEN

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

enum class ReviewStatus(val wire: String) {
    OPEN("open"),
    DONE("done"),
    ARCHIVED("archived");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

enum class Decision(val wire: String) {
    APPLIED("applied"),
    DISMISSED("dismissed");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

enum class ReanchorMethod(val wire: String) {
    PICKED("picked"),
    SUGGESTION("suggestion");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

/** The tag that flags a comment as a recurring problem that should become a rule (SPEC 6.2). */
const val RULE_CANDIDATE_TAG = "rule_candidate"

/** Where a comment sits (SPEC 6.3). Never mentions hunks. */
sealed interface Anchor {
    data object Review : Anchor

    /** About one changed file as a whole. [path] is the file's identity in the diff; [oldPath] is set only for a rename. */
    data class File(val path: String, val oldPath: String?, val pin: Pin) : Anchor

    /** About lines of one changed file: one range per side, at most two. */
    data class Lines(val path: String, val oldPath: String?, val pin: Pin, val ranges: List<AnchorRange>) : Anchor
}

/** A field an edit may leave alone: `null` for the field itself means "absent", `Field(x)` means "set to x" (x may itself be null). */
data class Field<out T>(val value: T)

/** One event of a review (SPEC 6). A value of this type is valid; the codec never produces an invalid one. */
sealed interface Event {
    val type: String

    data class ReviewRenamed(val title: String) : Event { override val type get() = "reviewRenamed" }
    data class ReviewStatusChanged(val status: ReviewStatus) : Event { override val type get() = "reviewStatusChanged" }
    data class ReviewRetargeted(val pin: Pin) : Event { override val type get() = "reviewRetargeted" }

    data class AnnotationCreated(val annotationId: String, val kind: Kind, val tags: List<String>, val body: String, val anchor: Anchor, val ruleText: String? = null) : Event {
        override val type get() = "annotationCreated"
    }

    /** `ruleText = Field(null)` clears the rule text; `null` leaves it alone. */
    data class AnnotationEdited(val annotationId: String, val body: String? = null, val kind: Kind? = null, val tags: List<String>? = null, val ruleText: Field<String?>? = null) : Event {
        override val type get() = "annotationEdited"
    }

    data class AnnotationStatusChanged(val annotationId: String, val status: AnnotationStatus, val note: String? = null) : Event { override val type get() = "annotationStatusChanged" }
    data class AnnotationReanchored(val annotationId: String, val anchor: Anchor, val method: ReanchorMethod? = null) : Event { override val type get() = "annotationReanchored" }
    data class AnnotationDeleted(val annotationId: String) : Event { override val type get() = "annotationDeleted" }
    data class AnnotationRestored(val annotationId: String) : Event { override val type get() = "annotationRestored" }
    data class ReplyAdded(val annotationId: String, val replyId: String, val body: String) : Event { override val type get() = "replyAdded" }
    data class ReplyEdited(val annotationId: String, val replyId: String, val body: String) : Event { override val type get() = "replyEdited" }
    data class ReplyDeleted(val annotationId: String, val replyId: String) : Event { override val type get() = "replyDeleted" }
    data class ReplyRestored(val annotationId: String, val replyId: String) : Event { override val type get() = "replyRestored" }
    data class FileReviewed(val path: String, val changeKey: String) : Event { override val type get() = "fileReviewed" }
    data class FileUnreviewed(val path: String) : Event { override val type get() = "fileUnreviewed" }
    data class RegionReviewed(val path: String, val ranges: List<MarkRange>) : Event { override val type get() = "regionReviewed" }
    data class RegionUnreviewed(val path: String, val ranges: List<MarkRange>) : Event { override val type get() = "regionUnreviewed" }
    data class RuleDecided(val annotationIds: List<String>, val targetPath: String, val decision: Decision) : Event { override val type get() = "ruleDecided" }
}
