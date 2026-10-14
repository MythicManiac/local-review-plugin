package com.mythic.localreview.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.mythic.localreview.core.Anchor
import com.mythic.localreview.core.AnchorScope
import com.mythic.localreview.core.Side
import com.mythic.localreview.ui.ActiveReview
import com.mythic.localreview.ui.comments.CommentDialog
import java.io.IOException

/** Hooks the review UI into every diff viewer whose request carries a [ReviewFileContext]. */
class ReviewDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val ctx = request.getUserData(ReviewDiff.CONTEXT) ?: return
        if (request !is ContentDiffRequest) return
        DiffReviewController(viewer, ctx, request)
    }
}

/** Diff-toolbar actions for the file being shown. */
object FileActions {
    fun create(project: Project, review: ActiveReview, ctx: ReviewFileContext): List<AnAction> =
        listOf(ToggleFileViewed(review, ctx), NextUnviewed(project, review, ctx), AddFileComment(project, review, ctx))

    private class ToggleFileViewed(val review: ActiveReview, val ctx: ReviewFileContext) :
        ToggleAction("Mark File as Reviewed", "Toggle whether you have finished reviewing this file", AllIcons.Actions.Checked) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent) = review.isViewed(ctx.change)
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            try {
                review.store.setFileViewed(ctx.change.path, ctx.change.fingerprint, state, ctx.hunkFingerprints.takeIf { it.isNotEmpty() })
            } catch (ex: IOException) {
                com.mythic.localreview.ui.ReviewService.getInstance(ctx.project).notify("Could not save: ${ex.message}", com.intellij.notification.NotificationType.ERROR)
            }
        }
    }

    private class NextUnviewed(val project: Project, val review: ActiveReview, val ctx: ReviewFileContext) :
        DumbAwareAction("Next Unreviewed File", "Open the next file that is not yet marked as reviewed", AllIcons.Actions.Forward) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = review.changes.any { it.path != ctx.change.path && !review.isViewed(it) }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val all = review.changes
            val i = all.indexOfFirst { it.path == ctx.change.path }
            val ordered = all.drop(i + 1) + all.take(i)
            val next = ordered.firstOrNull { !review.isViewed(it) } ?: return
            ReviewDiff.open(project, next)
        }
    }

    private class AddFileComment(val project: Project, val review: ActiveReview, val ctx: ReviewFileContext) :
        DumbAwareAction("Add Comment on This File", "Add a comment that applies to the whole file", AllIcons.General.Add) {
        override fun actionPerformed(e: AnActionEvent) {
            val dlg = CommentDialog(project, "Comment on ${ctx.change.path}", null)
            if (!dlg.showAndGet()) return
            val c = ctx.change
            val side = if (c.newPath != null) Side.HEAD else Side.BASE
            val anchor = Anchor(AnchorScope.FILE, c.newPath ?: c.oldPath, side, revision = review.store.review().current.n)
            try {
                review.store.addComment(dlg.kind, dlg.text, anchor, dlg.rule)
            } catch (ex: IOException) {
                com.mythic.localreview.ui.ReviewService.getInstance(project).notify("Could not save: ${ex.message}", com.intellij.notification.NotificationType.ERROR)
            }
        }
    }
}
