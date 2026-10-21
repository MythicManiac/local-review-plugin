package com.mythic.localreview.format

/** What a person reads for each value of the vocabulary (reports, lists, dialogs). */
val Kind.label: String
    get() = when (this) {
        Kind.CHANGE_REQUEST -> "Change request"
        Kind.QUESTION -> "Question"
        Kind.COMMENT -> "Note"
    }

val AnnotationStatus.label: String
    get() = when (this) {
        AnnotationStatus.OPEN -> "Open"
        AnnotationStatus.RESOLVED -> "Resolved"
        AnnotationStatus.WONT_FIX -> "Won't fix"
    }

val ReviewStatus.label: String
    get() = when (this) {
        ReviewStatus.OPEN -> "Open"
        ReviewStatus.DONE -> "Done"
        ReviewStatus.ARCHIVED -> "Archived"
    }
