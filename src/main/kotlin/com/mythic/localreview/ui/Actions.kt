package com.mythic.localreview.ui

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.mythic.localreview.core.Anchor
import com.mythic.localreview.core.AnchorScope
import com.mythic.localreview.core.AtomicFiles
import com.mythic.localreview.core.ReviewSummary
import com.mythic.localreview.export.AgentPrompts
import com.mythic.localreview.export.AgentsMd
import com.mythic.localreview.export.ExportInput
import com.mythic.localreview.export.JsonReport
import com.mythic.localreview.export.MarkdownReport
import com.mythic.localreview.ui.comments.CommentDialog
import com.mythic.localreview.ui.diff.ReviewDiff
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JList

private fun runInBackground(project: Project, title: String, work: () -> Unit) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
        override fun run(indicator: ProgressIndicator) {
            try {
                work()
            } catch (e: Exception) {
                ReviewService.getInstance(project).notify("$title failed: ${e.message}", NotificationType.ERROR)
            }
        }
    })
}

abstract class ReviewAction(text: String? = null, description: String? = null, icon: javax.swing.Icon? = null) : DumbAwareAction(text, description, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    protected fun active(e: AnActionEvent): ActiveReview? = e.project?.let { ReviewService.getInstance(it).active }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

abstract class NeedsReviewAction(text: String? = null, description: String? = null, icon: javax.swing.Icon? = null) : ReviewAction(text, description, icon) {
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = e.project != null
        e.presentation.isEnabled = active(e) != null
    }
}

class NewReviewAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        if (service.gitRoots().isEmpty()) {
            Messages.showErrorDialog(project, "This project has no git repository.", "Local Review")
            return
        }
        val dlg = NewReviewDialog(project, service)
        if (!dlg.showAndGet()) return
        runInBackground(project, "Creating review") { service.create(dlg.root, dlg.reviewTitle, dlg.baseRef, dlg.headRef, dlg.useMergeBase) }
    }
}

class OpenReviewAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        runInBackground(project, "Listing reviews") {
            val reviews = service.listReviews()
            ApplicationManager.getApplication().invokeLater {
                if (reviews.isEmpty()) {
                    Messages.showInfoMessage(project, "No reviews for this repository yet in ${ReviewSettings.getInstance().storeRoot()}.", "Local Review")
                    return@invokeLater
                }
                JBPopupFactory.getInstance().createPopupChooserBuilder(reviews)
                    .setTitle("Open Review")
                    .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<ReviewSummary>() {
                        override fun customizeCellRenderer(l: JList<out ReviewSummary>, v: ReviewSummary, i: Int, s: Boolean, f: Boolean) {
                            append(v.review.title)
                            append("   ${v.review.baseRef} → ${v.review.headRef} · rev ${v.review.current.n} · ${v.review.createdAt.take(10)}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    })
                    .setItemChosenCallback { choice -> runInBackground(project, "Opening review") { service.open(choice) } }
                    .createPopup().showCenteredInCurrentWindow(project)
            }
        }
    }
}

class CloseReviewAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReviewService.getInstance(e.project ?: return).close()
    }
}

class UpdateReviewAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        runInBackground(project, "Updating review") {
            val r = service.update()
            service.notify(
                if (r.newRevision == null) "Review is already up to date with ${service.active?.store?.review()?.headRef}."
                else "Updated to revision ${r.newRevision.n}: ${r.reanchored} comments re-anchored, ${r.outdated} outdated.",
            )
        }
    }
}

class ExportReviewAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        val a = service.active ?: return
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val input = exportInput(a)
        val dlg = ExportDialog(project, a.store.dir.resolve("exports").resolve(stamp), AgentsMd.candidates(input.comments).isNotEmpty())
        if (!dlg.showAndGet()) return
        val out = dlg.outputDir
        try {
            Files.createDirectories(out)
            if (dlg.wantMarkdown) AtomicFiles.write(out.resolve("report.md"), MarkdownReport.render(input).toByteArray())
            if (dlg.wantJson) AtomicFiles.write(out.resolve("report.json"), JsonReport.render(input).toByteArray())
            if (dlg.wantPrompt) AtomicFiles.write(out.resolve("agent-prompt.md"), AgentPrompts.forReview(input).toByteArray())
        } catch (ex: IOException) {
            service.notify("Export failed: ${ex.message}", NotificationType.ERROR)
            return
        }
        a.store.commitExternalChanges("Export review")
        service.notify(
            "Review exported to $out",
            NotificationType.INFORMATION,
            "Copy agent prompt" to { CopyPasteManager.getInstance().setContents(StringSelection(AgentPrompts.forReview(exportInput(a)))) },
            "Copy report" to { CopyPasteManager.getInstance().setContents(StringSelection(MarkdownReport.render(exportInput(a)))) },
            "Show in Files" to { RevealFileAction.openDirectory(out) },
        )
    }
}

fun exportInput(a: ActiveReview) = ExportInput(
    review = a.store.review(),
    comments = a.store.comments(),
    changes = a.changes,
    progress = a.store.allProgress().associateBy { it.path },
    generatedAt = Instant.now().toString(),
)

class CopyAgentPromptAction : NeedsReviewAction("Copy Agent Prompt for Whole Review") {
    override fun actionPerformed(e: AnActionEvent) {
        val a = active(e) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(AgentPrompts.forReview(exportInput(a))))
        ReviewService.getInstance(e.project ?: return).notify("Agent prompt copied to the clipboard.")
    }
}

class ProposeAgentsMdAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        val a = service.active ?: return
        val rules = AgentsMd.candidates(a.store.comments())
        if (rules.isEmpty()) {
            service.notify("No comments are marked as AGENTS.md rule candidates yet. Tick \"Recurring pattern\" when writing a comment.", NotificationType.WARNING)
            return
        }
        val file = a.git.toplevel().resolve("AGENTS.md")
        val existing = if (Files.exists(file)) Files.readString(file) else ""
        val proposed = AgentsMd.propose(existing, rules)
        if (proposed == existing) {
            service.notify("AGENTS.md already contains all ${rules.size} proposed rules.")
            return
        }
        val dlg = AgentsMdDialog(project, "AGENTS.md", existing, proposed)
        if (!dlg.showAndGet()) return
        try {
            AtomicFiles.write(file, dlg.finalText.toByteArray())
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)?.refresh(true, false)
            service.notify("Wrote $file")
        } catch (ex: IOException) {
            service.notify("Could not write AGENTS.md: ${ex.message}", NotificationType.ERROR)
        }
    }
}

class AddReviewNoteAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val a = active(e) ?: return
        val dlg = CommentDialog(project, "Comment on the whole review", null)
        if (!dlg.showAndGet()) return
        try {
            a.store.addComment(dlg.kind, dlg.text, Anchor(AnchorScope.REVIEW, revision = a.store.review().current.n), dlg.rule)
        } catch (ex: IOException) {
            ReviewService.getInstance(project).notify("Could not save: ${ex.message}", NotificationType.ERROR)
        }
    }
}

class OpenFirstDiffAction : NeedsReviewAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = active(e)?.changes?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val a = active(e) ?: return
        val first = a.changes.firstOrNull { !a.isViewed(it) } ?: a.changes.firstOrNull() ?: return
        ReviewDiff.open(project, first)
    }
}

class ToggleHideViewedAction : ToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent) = ReviewUiState.hideViewed
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ReviewUiState.hideViewed = state
        e.project?.let { ReviewService.getInstance(it).refreshUi() }
    }
}

object ReviewUiState {
    @Volatile
    var hideViewed = false
}

// ---- popup (changes tree) --------------------------------------------------------------------------------------

class OpenSelectedDiffAction : ReviewAction("Open Diff") {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !e.getData(ReviewDataKeys.SELECTED_FILES).isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewDiff.open(project, e.getData(ReviewDataKeys.SELECTED_FILES)?.firstOrNull() ?: return)
    }
}

abstract class MarkViewedAction(private val viewed: Boolean, text: String) : ReviewAction(text) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !e.getData(ReviewDataKeys.SELECTED_FILES).isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val a = active(e) ?: return
        try {
            for (c in e.getData(ReviewDataKeys.SELECTED_FILES).orEmpty()) a.store.setFileViewed(c.path, c.fingerprint, viewed)
        } catch (ex: IOException) {
            ReviewService.getInstance(e.project ?: return).notify("Could not save: ${ex.message}", NotificationType.ERROR)
        }
    }
}

class MarkSelectedViewedAction : MarkViewedAction(true, "Mark as Reviewed")
class MarkSelectedUnviewedAction : MarkViewedAction(false, "Mark as Not Reviewed")

class CommentOnSelectedFileAction : ReviewAction("Add Comment on File…") {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(ReviewDataKeys.SELECTED_FILES)?.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val a = active(e) ?: return
        val c = e.getData(ReviewDataKeys.SELECTED_FILES)?.singleOrNull() ?: return
        val dlg = CommentDialog(project, "Comment on ${c.path}", null)
        if (!dlg.showAndGet()) return
        val side = if (c.newPath != null) com.mythic.localreview.core.Side.HEAD else com.mythic.localreview.core.Side.BASE
        try {
            a.store.addComment(dlg.kind, dlg.text, Anchor(AnchorScope.FILE, c.newPath ?: c.oldPath, side, revision = a.store.review().current.n), dlg.rule)
        } catch (ex: IOException) {
            ReviewService.getInstance(project).notify("Could not save: ${ex.message}", NotificationType.ERROR)
        }
    }
}
