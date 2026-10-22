package com.mythic.localreview.ui.diff

import com.intellij.diff.util.Side as DiffSide
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.mythic.localreview.format.ChangedFile
import com.mythic.localreview.format.Side
import com.mythic.localreview.ui.ReviewService
import java.nio.file.Path

/**
 * Following a reference from a review diff (Go to Declaration or Ctrl+click, which are the same actions) opens the target in an ordinary editor tab.
 * When the target is a file of the review's change set, this replaces that tab by a review diff of it, in a new tab, at the line navigation chose; a file
 * outside the change set stays an ordinary editor of its checked-out version. No tab that was open before is touched: the diff the user came from stays
 * where it is, with its state, and a target the user already had open in an editor is simply shown.
 *
 * It acts only on a file that is opened for the first time within [WINDOW_MS] of a declaration action started while a review diff tab was selected.
 * Opening a file any other way (the project view, search, a click on a tab) is never redirected.
 */
class FollowToDiff(private val project: Project, private val service: ReviewService) : AnActionListener, FileEditorManagerListener {
    /** When a declaration action last started in a review diff tab; 0 when none is pending. */
    @Volatile
    private var armedAt = 0L

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (event.project != project) return
        if (ActionManager.getInstance().getId(action) !in DECLARATION_ACTIONS) return
        val selected = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        armedAt = if (selected != null && ReviewDiff.isReviewTab(project, selected)) System.nanoTime() else 0L
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val armed = armedAt
        if (armed == 0L || (System.nanoTime() - armed) / 1_000_000 > WINDOW_MS) return
        val change = changeOf(file) ?: return
        armedAt = 0L
        // Navigation places the caret after the editor opens: read it once that has happened.
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val line = source.selectedTextEditor?.takeIf { it.virtualFile == file }?.caretModel?.logicalPosition?.line
            ReviewDiff.open(project, change, line?.let { DiffSide.RIGHT to it }, newTab = true)
            source.closeFile(file) // the plain tab the navigation opened; it was not open before, or no "opened" event would have fired
        }, ModalityState.nonModal())
    }

    /** The changed file that [file] is the checked-out version of, if it has a head side to show. */
    private fun changeOf(file: VirtualFile): ChangedFile? {
        val a = service.active ?: return null
        if (!file.isInLocalFileSystem) return null
        val rel = try {
            a.root.toAbsolutePath().normalize().relativize(Path.of(file.path).toAbsolutePath().normalize())
        } catch (_: Exception) {
            return null
        }
        if (rel.startsWith("..") || rel.isAbsolute) return null
        return a.file(rel.joinToString("/") { it.toString() })?.takeIf { it.blob(Side.NEW) != null }
    }

    private companion object {
        /** A navigation that shows a list first (several targets) can take a while to open its file. */
        const val WINDOW_MS = 15_000L
        val DECLARATION_ACTIONS = setOf("GotoDeclaration", "GotoDeclarationOnly", "GotoTypeDeclaration", "GotoTypeDeclarationOnly")
    }
}
