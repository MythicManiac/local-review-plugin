package com.mythic.localreview.ui.diff

import com.intellij.diff.DiffContentFactory
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
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.VirtualFile
import com.mythic.localreview.format.ChangedFile
import com.mythic.localreview.format.Lines
import com.mythic.localreview.format.Side
import com.mythic.localreview.ui.ActiveReview
import com.mythic.localreview.ui.ReviewService

/**
 * Attached to every diff request we create; presence tells [ReviewDiffExtension] to add review UI. [oldLines] and [newLines] are the lines of each side
 * by the format's line model (ALGORITHMS 1), null for a side that is absent or not text. Line comments and region marks exist only for a side that has
 * them, so the numbers a user sees are the numbers that are stored.
 */
class ReviewFileContext(val project: Project, val review: ActiveReview, val change: ChangedFile, val oldLines: Lines?, val newLines: Lines?) {
    fun lines(side: Side): Lines? = if (side == Side.OLD) oldLines else newLines
}

object ReviewDiff {
    val CONTEXT: Key<ReviewFileContext> = Key.create("LocalReview.FileContext")

    private val openFiles = HashMap<Project, VirtualFile>()

    /** Closes the review diff tab for [project], if open. EDT only. */
    fun close(project: Project) {
        if (project.isDisposed) return
        openFiles.remove(project)?.let { FileEditorManager.getInstance(project).closeFile(it) }
    }

    /**
     * Opens the review's changes in the IDE diff viewer (editor tab, or window per the IDE's diff setting), positioned on
     * [change]. All changed files are in one chain so the viewer's next/previous-file navigation works. Replaces any
     * previously opened review diff tab so tabs do not pile up.
     */
    fun open(project: Project, change: ChangedFile, scrollTo: Pair<DiffSide, Int>? = null) {
        val a = ReviewService.getInstance(project).active ?: return
        val producers = a.changes.map { FileDiffProducer(project, a, it, if (it.path == change.path) scrollTo else null) }
        val chain = SimpleDiffRequestChain.fromProducers(producers, a.changes.indexOfFirst { it.path == change.path }.coerceAtLeast(0))
        val file = ChainDiffVirtualFile(chain, "Review: ${a.header.title}")
        openFiles.remove(project)?.let { FileEditorManager.getInstance(project).closeFile(it) }
        openFiles[project] = file
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
    }
}

internal class FileDiffProducer(
    private val project: Project,
    private val review: ActiveReview,
    private val change: ChangedFile,
    private val scrollTo: Pair<DiffSide, Int>?,
) : DiffRequestProducer {
    override fun getName(): String = change.path

    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
        val blobs = review.blobs ?: throw DiffRequestProducerException(review.problem ?: "The reviewed commits are not available")
        val sideLines = review.lines!!
        val h = review.header
        fun content(side: Side): Pair<DiffContent, Lines?> {
            val blob = change.blob(side) ?: return DiffContentFactoryEx.getInstanceEx().createEmpty() to null
            val bytes = blobs.read(blob) ?: throw DiffRequestProducerException("The file content $blob is not in this repository")
            val path = if (side == Side.OLD) change.oldPath ?: change.path else change.path
            val lines = sideLines.of(change, side)
            if (lines == null) {
                // Not text by the format's rules (ALGORITHMS 1.3): the IDE may still show something, but it has no line numbers to comment on.
                return DiffContentFactoryEx.getInstanceEx().createFromBytes(project, bytes, LocalFilePath(review.root.resolve(path).toString(), false)) to null
            }
            // Feed the viewer the text the line model decoded (no byte order mark, LF line ends), so its numbering is the format's by construction.
            val text = lines.joinToString("\n") + if (bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()) "\n" else ""
            val type = FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/'))
            return DiffContentFactory.getInstance().create(project, text, type) to lines
        }

        val (left, oldLines) = content(Side.OLD)
        val (right, newLines) = content(Side.NEW)
        val request = SimpleDiffRequest(
            change.path, left, right,
            "${h.baseRef} (${h.pin.baseSha.take(8)})${change.oldPath?.takeIf { it != change.path }?.let { " · $it" } ?: ""}",
            "${h.headRef} (${h.pin.headSha.take(8)})",
        )
        val ctx = ReviewFileContext(project, review, change, oldLines, newLines)
        request.putUserData(ReviewDiff.CONTEXT, ctx)
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        // Marks are made from a whitespace-sensitive comparison: a whitespace-only change is part of the change (ALGORITHMS 6).
        request.putUserData(DiffUserDataKeys.DO_NOT_IGNORE_WHITESPACES, true)
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, FileActions.create(project, review, ctx))
        if (scrollTo != null) request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, com.intellij.openapi.util.Pair.create(scrollTo.first, scrollTo.second))
        return request
    }
}
