package com.mythic.localreview.ui.diff

import com.intellij.diff.DiffContentFactoryEx
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side as DiffSide
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.VirtualFile
import com.mythic.localreview.core.FileChange
import com.mythic.localreview.ui.ActiveReview
import com.mythic.localreview.ui.ReviewService

/** Attached to every diff request we create; presence tells [ReviewDiffExtension] to add review UI. */
class ReviewFileContext(val project: Project, val review: ActiveReview, val change: FileChange) {
    /** Hunk fingerprints of the viewer currently showing this file (filled by the viewer controller). */
    @Volatile
    var hunkFingerprints: List<String> = emptyList()
}

object ReviewDiff {
    val CONTEXT: Key<ReviewFileContext> = Key.create("LocalReview.FileContext")

    private val openFiles = HashMap<Project, VirtualFile>()

    /**
     * Opens the review's changes in the IDE diff viewer (editor tab, or window per the IDE's diff setting), positioned on
     * [change]. All changed files are in one chain so the viewer's next/previous-file navigation works. Replaces any
     * previously opened review diff tab so tabs do not pile up.
     */
    fun open(project: Project, change: FileChange, scrollTo: Pair<DiffSide, Int>? = null) {
        val a = ReviewService.getInstance(project).active ?: return
        val producers = a.changes.map { FileDiffProducer(project, a, it, if (it.path == change.path) scrollTo else null) }
        val chain = SimpleDiffRequestChain.fromProducers(producers, a.changes.indexOfFirst { it.path == change.path }.coerceAtLeast(0))
        val file = ChainDiffVirtualFile(chain, "Review: ${a.store.review().title}")
        openFiles.remove(project)?.let { FileEditorManager.getInstance(project).closeFile(it) }
        openFiles[project] = file
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
    }
}

private class FileDiffProducer(
    private val project: Project,
    private val review: ActiveReview,
    private val change: FileChange,
    private val scrollTo: Pair<DiffSide, Int>?,
) : DiffRequestProducer {
    override fun getName(): String = change.path

    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
        val rev = review.store.review().current
        val root = review.git.toplevel()
        try {
            fun content(sha: String, path: String?): DiffContent {
                if (path == null) return DiffContentFactoryEx.getInstanceEx().createEmpty()
                val bytes = review.git.showFile(sha, path) ?: ByteArray(0)
                return DiffContentFactoryEx.getInstanceEx().createFromBytes(project, bytes, LocalFilePath(root.resolve(path).toString(), false))
            }

            val left = content(rev.effectiveBaseSha, change.oldPath)
            val right = content(rev.headSha, change.newPath)
            val r = review.store.review()
            val request = SimpleDiffRequest(
                change.path, left, right,
                "${r.baseRef} (${rev.effectiveBaseSha.take(8)})${change.oldPath?.takeIf { it != change.newPath }?.let { " · $it" } ?: ""}",
                "${r.headRef} (${rev.headSha.take(8)})",
            )
            request.putUserData(ReviewDiff.CONTEXT, ReviewFileContext(project, review, change))
            request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
            request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, FileActions.create(project, review, request.getUserData(ReviewDiff.CONTEXT)!!))
            if (scrollTo != null) request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, com.intellij.openapi.util.Pair.create(scrollTo.first, scrollTo.second))
            return request
        } catch (e: com.mythic.localreview.core.GitException) {
            throw DiffRequestProducerException(e.message ?: "git failed")
        }
    }
}
