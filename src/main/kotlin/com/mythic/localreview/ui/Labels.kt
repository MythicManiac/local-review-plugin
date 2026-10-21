package com.mythic.localreview.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.mythic.localreview.format.ChangedFile
import com.mythic.localreview.format.Kind
import java.awt.Color

val Kind.color: Color
    get() = when (this) {
        Kind.CHANGE_REQUEST -> JBColor(Color(0xC7, 0x22, 0x22), Color(0xE0, 0x6C, 0x6C))
        Kind.QUESTION -> JBColor(Color(0x1F, 0x6F, 0xD0), Color(0x5C, 0xA5, 0xF5))
        Kind.COMMENT -> JBColor(Color(0x6B, 0x6B, 0x6B), Color(0xA0, 0xA0, 0xA0))
    }

val Kind.icon
    get() = when (this) {
        Kind.CHANGE_REQUEST -> AllIcons.General.Warning
        Kind.QUESTION -> AllIcons.General.ContextHelp
        Kind.COMMENT -> AllIcons.General.Information
    }

/** The one letter shown next to a file in the Changes tree (`git diff --raw` status). */
val ChangedFile.letter: String
    get() = when (status) {
        "added" -> "A"
        "deleted" -> "D"
        "renamed" -> "R"
        "copied" -> "C"
        "typeChanged" -> "T"
        else -> "M"
    }
