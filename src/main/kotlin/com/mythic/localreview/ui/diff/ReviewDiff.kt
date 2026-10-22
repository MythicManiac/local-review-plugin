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
class ReviewFileContext(
    val project: Project,
    val review: ActiveReview,
    val change: ChangedFile,
    val oldLines: Lines?,
    val newLines: Lines?,
    /** The text each viewer pane must show for [oldLines] / [newLines]; a pane that shows anything else has other line numbers than the format. */
    val oldText: String? = null,
    val newText: String? = null,
) {
    fun lines(side: Side): Lines? = if (side == Side.OLD) oldLines else newLines
    fun text(side: Side): String? = if (side == Side.OLD) oldText else newText
}

object ReviewDiff {
    val CONTEXT: Key<ReviewFileContext> = Key.create("LocalReview.FileContext")

    /** The tab that opening from the Changes tree or the Comments tab reuses, so those do not pile up tabs. */
    private val mainTab = HashMap<Project, VirtualFile>()

    /** Tabs opened with [open] `newTab = true` (following a reference to a changed file): they stay until the user closes them. */
    private val extraTabs = HashMap<Project, MutableList<VirtualFile>>()

    /** Closes every review diff tab of [project]. EDT only. */
    fun close(project: Project) {
        if (project.isDisposed) return
        val fem = FileEditorManager.getInstance(project)
        mainTab.remove(project)?.let { fem.closeFile(it) }
        extraTabs.remove(project)?.forEach { fem.closeFile(it) }
    }

    /** Whether [file] is one of the review diff tabs of [project]. */
    fun isReviewTab(project: Project, file: VirtualFile): Boolean = file === mainTab[project] || extraTabs[project]?.any { it === file } == true

    /**
     * Opens the review's changes in the IDE diff viewer (editor tab, or window per the IDE's diff setting), positioned on
     * [change]. All changed files are in one chain so the viewer's next/previous-file navigation works. By default the main review
     * tab is replaced so tabs do not pile up; with [newTab] a further tab is opened and every other tab, with its editor state, is left alone.
     */
    fun open(project: Project, change: ChangedFile, scrollTo: Pair<DiffSide, Int>? = null, newTab: Boolean = false) {
        val a = ReviewService.getInstance(project).active ?: return
        val producers = a.changes.map { FileDiffProducer(project, a, it, if (it.path == change.path) scrollTo else null) }
        val chain = SimpleDiffRequestChain.fromProducers(producers, a.changes.indexOfFirst { it.path == change.path }.coerceAtLeast(0))
        val title = "Review: ${a.header.title}"
        val fem = FileEditorManager.getInstance(project)
        val file = ChainDiffVirtualFile(chain, if (newTab) "$title · ${change.path.substringAfterLast('/')}" else title)
        if (newTab) {
            val list = extraTabs.getOrPut(project) { ArrayList() }
            list.removeAll { !fem.isFileOpen(it) } // tabs the user closed
            list += file
        } else {
            mainTab.remove(project)?.let { fem.closeFile(it) }
            mainTab[project] = file
        }
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
        /** The content of one side, its lines, and the text the viewer must show for them (the format's decoding of the blob). */
        class Side3(val content: DiffContent, val lines: Lines?, val text: String?)

        fun content(side: Side): Side3 {
            val blob = change.blob(side) ?: return Side3(DiffContentFactoryEx.getInstanceEx().createEmpty(), null, null)
            val bytes = blobs.read(blob) ?: throw DiffRequestProducerException("The file content $blob is not in this repository")
            val path = if (side == Side.OLD) change.oldPath ?: change.path else change.path
            val lines = sideLines.of(change, side)
            if (lines == null) {
                // Not text by the format's rules (ALGORITHMS 1.3): the IDE may still show something, but it has no line numbers to comment on.
                return Side3(DiffContentFactoryEx.getInstanceEx().createFromBytes(project, bytes, LocalFilePath(review.root.resolve(path).toString(), false)), null, null)
            }
            // Feed the viewer the text the line model decoded (no byte order mark, LF line ends), so its numbering is the format's by construction.
            val text = lines.joinToString("\n") + if (bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()) "\n" else ""
            // The head side shows the project's own file when it holds exactly this text: the editor is then a real one (Ctrl+click, Find Usages, docs).
            val real = if (side == Side.NEW) projectFileWithText(path, text) else null
            if (real != null) return Side3(DiffContentFactory.getInstance().create(project, real), lines, text)
            val type = FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/'))
            return Side3(DiffContentFactory.getInstance().create(project, text, type), lines, text)
        }

        val left = content(Side.OLD)
        val right = content(Side.NEW)
        val request = SimpleDiffRequest(
            change.path, left.content, right.content,
            "${h.baseRef} (${h.pin.baseSha.take(8)})${change.oldPath?.takeIf { it != change.path }?.let { " · $it" } ?: ""}",
            "${h.headRef} (${h.pin.headSha.take(8)})",
        )
        val ctx = ReviewFileContext(project, review, change, left.lines, right.lines, left.text, right.text)
        request.putUserData(ReviewDiff.CONTEXT, ctx)
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        // Marks are made from a whitespace-sensitive comparison: a whitespace-only change is part of the change (ALGORITHMS 6).
        request.putUserData(DiffUserDataKeys.DO_NOT_IGNORE_WHITESPACES, true)
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, FileActions.create(project, review, ctx))
        if (scrollTo != null) request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, com.intellij.openapi.util.Pair.create(scrollTo.first, scrollTo.second))
        return request
    }

    /**
     * The project's file at [path] (relative to the repository root) if its document holds exactly [text], else null: a checkout of another commit, or
     * uncommitted edits, must not be passed off as the reviewed code.
     */
    private fun projectFileWithText(path: String, text: String): VirtualFile? {
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(review.root.resolve(path)) ?: return null
        return com.intellij.openapi.application.ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
            if (!vf.isValid || vf.isDirectory) return@computeBlocking null
            val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf) ?: return@computeBlocking null
            if (doc.text == text) vf else null
        }
    }
}
