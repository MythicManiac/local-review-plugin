package com.mythic.localreview.ui.notes

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingConstants

/** The file type of the review notes tab: it has no content of its own, so it only gives the tab a name and an icon. */
object ReviewNotesFileType : FileType {
    override fun getName() = "Local Diff Review notes"
    override fun getDescription() = "Notes about a whole review"
    override fun getDefaultExtension() = ""
    override fun getIcon(): Icon = AllIcons.General.Balloon
    override fun isBinary() = false
    override fun isReadOnly() = true
}

/** The "file" of the review notes tab. One per project, kept by [ReviewNotes]. */
class ReviewNotesFile : LightVirtualFile("Review notes", ReviewNotesFileType, "") {
    override fun isWritable() = false
}

/** The review notes as an editor tab: it can be opened beside a diff in a split like any other tab. */
class ReviewNotesEditor(project: Project, private val file: ReviewNotesFile) : UserDataHolderBase(), FileEditor {
    private val panel = ReviewNotesPanel(project)

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent? = panel.preferredFocus()
    override fun getName() = "Review notes"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() = panel.dispose()

    /** Scrolls to a note (a comment id) and highlights it briefly. */
    fun reveal(annotationId: String) = panel.reveal(annotationId)
}

class ReviewNotesEditorProvider : FileEditorProvider, com.intellij.openapi.project.DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ReviewNotesFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ReviewNotesEditor(project, file as ReviewNotesFile)
    override fun getEditorTypeId() = "local-diff-review-notes"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Opening and closing the review notes tab of a project. EDT only. */
object ReviewNotes {
    private val files = HashMap<Project, ReviewNotesFile>()

    private fun file(project: Project) = files.getOrPut(project) { ReviewNotesFile() }

    /** Opens (or selects) the tab. With [toTheSide] it opens in a split next to the current editor, so a diff and the notes are visible together. */
    fun open(project: Project, toTheSide: Boolean = false, reveal: String? = null) {
        if (project.isDisposed) return
        val f = file(project)
        val fem = FileEditorManager.getInstance(project)
        if (toTheSide && !fem.isFileOpen(f)) {
            val ex = FileEditorManagerEx.getInstanceEx(project)
            val current = ex.currentWindow
            val other = current?.let { w -> ex.windows.firstOrNull { it !== w } }
            when {
                // A split already exists: use the other half (make it current, then open as usual).
                other != null -> {
                    other.setAsCurrentWindow(true)
                    fem.openFile(f, true)
                }
                current != null -> current.split(SwingConstants.VERTICAL, true, f, true)
                else -> fem.openFile(f, true)
            }
        } else {
            fem.openFile(f, true)
        }
        if (reveal != null) {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                fem.getEditors(f).filterIsInstance<ReviewNotesEditor>().firstOrNull()?.reveal(reveal)
            }
        }
    }

    fun close(project: Project) {
        if (project.isDisposed) return
        files.remove(project)?.let { FileEditorManager.getInstance(project).closeFile(it) }
    }
}
