package com.mythic.localreview.ui

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
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
import com.mythic.localreview.core.AtomicFiles
import com.mythic.localreview.export.AgentPrompts
import com.mythic.localreview.export.AgentsMd
import com.mythic.localreview.export.ExportInput
import com.mythic.localreview.export.JsonReport
import com.mythic.localreview.export.MarkdownReport
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.Decision
import com.mythic.localreview.format.ReviewMode
import com.mythic.localreview.format.ReviewStatus
import com.mythic.localreview.format.label
import com.mythic.localreview.store.ReviewSummary
import com.mythic.localreview.ui.comments.CommentDialog
import com.mythic.localreview.ui.diff.ReviewDiff
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Files
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
            Messages.showErrorDialog(project, "This project has no git repository.", "Local Diff Review")
            return
        }
        val dlg = NewReviewDialog(project, service)
        if (!dlg.showAndGet()) return
        val mode = if (dlg.useMergeBase) ReviewMode.MERGE_BASE else ReviewMode.DIRECT
        runInBackground(project, "Creating review") { service.create(dlg.root, dlg.reviewTitle, dlg.baseRef, dlg.headRef, mode, dlg.groupName) }
    }
}

class OpenReviewAction : ReviewAction() {
    /** One row of the chooser: a review, or the entry that lists the archived ones. */
    private class Choice(val summary: ReviewSummary?, val archived: List<ReviewSummary> = emptyList())

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        runInBackground(project, "Listing reviews") {
            val all = service.listReviews(includeArchived = true)
            ApplicationManager.getApplication().invokeLater {
                if (all.isEmpty()) {
                    Messages.showInfoMessage(project, "No reviews for this repository yet in ${ReviewSettings.getInstance().storeRoot()}.", "Local Diff Review")
                    return@invokeLater
                }
                val live = all.filter { it.status != ReviewStatus.ARCHIVED }
                val archived = all.filter { it.status == ReviewStatus.ARCHIVED }
                val rows = live.map { Choice(it) } + if (archived.isNotEmpty()) listOf(Choice(null, archived)) else emptyList()
                choose(project, service, rows, "Open Review")
            }
        }
    }

    private fun choose(project: Project, service: ReviewService, rows: List<Choice>, title: String) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(rows)
            .setTitle(title)
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<Choice>() {
                override fun customizeCellRenderer(l: JList<out Choice>, v: Choice, i: Int, s: Boolean, f: Boolean) {
                    val gray = com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES
                    val r = v.summary
                    if (r == null) {
                        append("Archived reviews (${v.archived.size})…", gray)
                        return
                    }
                    append("[${r.group}] ", gray)
                    append(r.title)
                    val m = r.meta
                    val status = if (r.status != ReviewStatus.OPEN) " · ${r.status.label}" else ""
                    append("   ${m.baseRef} → ${m.headRef}$status · ${Instant.ofEpochMilli(m.createdAt).toString().take(10)}", gray)
                }
            })
            .setItemChosenCallback { choice ->
                val summary = choice.summary
                if (summary == null) choose(project, service, choice.archived.map { Choice(it) }, "Archived Reviews")
                else runInBackground(project, "Opening review") { service.open(summary) }
            }
            .createPopup().showCenteredInCurrentWindow(project)
    }
}

class CloseReviewAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReviewService.getInstance(e.project ?: return).close()
    }
}

/** Marks the review open, done or archived (`reviewStatusChanged`); an archived review is left out of the Open Review list but stays openable. */
class ReviewStatusAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        val current = service.active?.header?.status ?: return
        JBPopupFactory.getInstance().createPopupChooserBuilder(ReviewStatus.entries)
            .setTitle("Review Status")
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<ReviewStatus>() {
                override fun customizeCellRenderer(l: JList<out ReviewStatus>, v: ReviewStatus, i: Int, s: Boolean, f: Boolean) {
                    append(v.label + if (v == current) "  (current)" else "")
                }
            })
            .setItemChosenCallback { status -> if (status != current) service.setReviewStatus(status) }
            .createPopup().showCenteredInCurrentWindow(project)
    }
}

class UpdateReviewAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        runInBackground(project, "Updating review") {
            val r = service.retarget()
            service.notify(
                if (!r.changed) "Review is already up to date with ${service.active?.header?.headRef}."
                else "Updated to the new commits: ${r.outdated} comments are outdated, ${r.withSuggestion} of them with a suggested new place (right-click a comment to accept it).",
            )
        }
    }
}

/** The input of every export, built from what the review shows right now. */
fun exportInput(a: ActiveReview) = ExportInput(
    state = a.state,
    changes = a.changes,
    evaluation = a.analysis?.evaluation,
    reviewed = a.changes.filter { a.isReviewed(it) }.map { it.path }.toSet(),
    generatedAt = Instant.now().toString(),
)

class ExportReviewAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        val a = service.active ?: return
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val input = exportInput(a)
        val rules = AgentsMd.candidates(input.comments)
        val dlg = ExportDialog(project, a.open.dir.resolve("exports").resolve(stamp), rules.rules.isNotEmpty() || rules.needsWording.isNotEmpty())
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
        a.open.group.committer.request("Export review")
        service.notify(
            "Review exported to $out",
            NotificationType.INFORMATION,
            "Copy agent prompt" to { CopyPasteManager.getInstance().setContents(StringSelection(AgentPrompts.forReview(exportInput(a)))) },
            "Copy report" to { CopyPasteManager.getInstance().setContents(StringSelection(MarkdownReport.render(exportInput(a)))) },
            "Show in Files" to { RevealFileAction.openDirectory(out) },
        )
    }
}

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
        val candidates = AgentsMd.candidates(a.comments())
        val wording = candidates.needsWording.size
        if (candidates.rules.isEmpty()) {
            service.notify(
                if (wording > 0) "$wording comment(s) are flagged as recurring patterns but have no rule text yet. Edit them (right-click → Edit…) and write the rule, then propose again."
                else "No comments are marked as AGENTS.md rule candidates yet. Tick \"Recurring pattern\" when writing a comment.",
                NotificationType.WARNING,
            )
            return
        }
        val file = a.git.toplevel().resolve("AGENTS.md")
        val existing = if (Files.exists(file)) Files.readString(file) else ""
        val proposed = AgentsMd.propose(existing, candidates.rules)
        val sources = candidates.rules.flatMap { it.sources }
        if (proposed == existing) {
            // Everything is in the file already: the candidates are applied, so they leave the inbox.
            service.decideRules(sources, "AGENTS.md", Decision.APPLIED)
            service.notify("AGENTS.md already contains all ${candidates.rules.size} proposed rules.")
            return
        }
        val dlg = AgentsMdDialog(project, "AGENTS.md", existing, proposed)
        if (!dlg.showAndGet()) return
        try {
            AtomicFiles.write(file, dlg.finalText.toByteArray())
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)?.refresh(true, false)
        } catch (ex: IOException) {
            service.notify("Could not write AGENTS.md: ${ex.message}", NotificationType.ERROR)
            return
        }
        service.decideRules(sources, "AGENTS.md", Decision.APPLIED)
        service.notify("Wrote $file" + if (wording > 0) ". $wording more candidate(s) still need wording." else "")
    }
}

class AddReviewNoteAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        active(e) ?: return
        val dlg = CommentDialog(project, "Comment on the whole review", null)
        if (!dlg.showAndGet()) return
        ReviewService.getInstance(project).addComment(dlg.kind, dlg.text, Anchor.Review, dlg.rule)
    }
}

class OpenFirstDiffAction : NeedsReviewAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = active(e)?.let { it.verified && it.changes.isNotEmpty() } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val a = active(e) ?: return
        val first = a.changes.firstOrNull { !a.isReviewed(it) } ?: a.changes.firstOrNull() ?: return
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

class OpenSelectedDiffInNewTabAction : ReviewAction("Open Diff in New Tab") {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !e.getData(ReviewDataKeys.SELECTED_FILES).isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewDiff.open(project, e.getData(ReviewDataKeys.SELECTED_FILES)?.firstOrNull() ?: return, newTab = true)
    }
}

abstract class MarkViewedAction(private val viewed: Boolean, text: String) : ReviewAction(text) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !e.getData(ReviewDataKeys.SELECTED_FILES).isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(ReviewDataKeys.SELECTED_FILES).orEmpty()
        // Reads blobs to find the hunks, so not on the UI thread.
        ApplicationManager.getApplication().executeOnPooledThread { ReviewService.getInstance(project).setFilesReviewed(files, viewed) }
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
        ReviewService.getInstance(project).addComment(dlg.kind, dlg.text, Anchor.File(c.path, c.oldPath, a.pin), dlg.rule)
    }
}

class ManageGroupsAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ManageGroupsDialog(project, ReviewService.getInstance(project)).show()
    }
}

class SyncGroupAction : NeedsReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewService.getInstance(project)
        val group = service.active?.group ?: return
        runInBackground(project, "Syncing review group") {
            val r = GroupSyncService.getInstance().syncNow(group)
            service.notify(
                describe(r, group),
                if (r is com.mythic.localreview.core.SyncResult.Failed) NotificationType.ERROR else NotificationType.INFORMATION,
            )
        }
    }
}
