package com.mythic.localreview.ui.diff

import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side as DiffSide
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import com.mythic.localreview.export.AgentPrompts
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.AnchorRange
import com.mythic.localreview.format.Annotation
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.CommentState
import com.mythic.localreview.format.Interval
import com.mythic.localreview.format.Reply
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.captureSnapshot
import com.mythic.localreview.ui.Hunk
import com.mythic.localreview.ui.ReviewListener
import com.mythic.localreview.ui.ReviewService
import com.mythic.localreview.ui.comments.CommentCard
import com.mythic.localreview.ui.comments.CommentCardActions
import com.mythic.localreview.ui.comments.INLINE_WIDTH
import com.mythic.localreview.ui.comments.InlineComposer
import com.mythic.localreview.ui.comments.InlineReplyComposer
import com.mythic.localreview.ui.comments.WidthAdjustable
import com.mythic.localreview.ui.exportInput
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

private fun DiffSide.toReview(): Side = if (this == DiffSide.LEFT) Side.OLD else Side.NEW
private fun Side.toDiff(): DiffSide = if (this == Side.OLD) DiffSide.LEFT else DiffSide.RIGHT

/** One editor of a diff viewer plus how its lines map to (side, line) pairs of the compared files. */
private abstract class EditorTarget(val editor: EditorEx) {
    /** Editor line for a 0-based line of [side], or -1 if that side's line is not shown in this editor. */
    abstract fun toEditorLine(side: DiffSide, line: Int): Int

    /** (side, 0-based line) a new comment at [editorLine] would attach to, or null if not commentable. */
    abstract fun fromEditorLine(editorLine: Int): Pair<DiffSide, Int>?

    /** The 0-based line of each side shown at [editorLine] (-1 for a side that does not have it): old, new. */
    abstract fun bothSides(editorLine: Int): IntArray?

    abstract fun hasSide(side: DiffSide): Boolean
}

private class SideTarget(editor: EditorEx, val side: DiffSide) : EditorTarget(editor) {
    override fun toEditorLine(side: DiffSide, line: Int) = if (side == this.side) line else -1
    override fun fromEditorLine(editorLine: Int) = side to editorLine
    override fun bothSides(editorLine: Int) = IntArray(2) { if (it == side.index) editorLine else -1 }
    override fun hasSide(side: DiffSide) = side == this.side
}

private class UnifiedTarget(val viewer: UnifiedDiffViewer) : EditorTarget(viewer.editor) {
    override fun toEditorLine(side: DiffSide, line: Int) = viewer.transferLineToOnesideStrict(side, line)
    override fun fromEditorLine(editorLine: Int): Pair<DiffSide, Int>? {
        val r = viewer.transferLineFromOnesideStrict(editorLine) ?: return null
        return r.second to r.first[r.second.index]
    }
    override fun bothSides(editorLine: Int): IntArray? = viewer.transferLineFromOnesideStrict(editorLine)?.first
    override fun hasSide(side: DiffSide) = true
}

/**
 * Attaches the review UI to one diff viewer: inline comment cards, a hover "+" to add comments, and per-hunk
 * reviewed markers. Everything is derived from the review's state, so it re-renders on any change.
 *
 * Line comments and region marks are offered only on a side whose lines are known by the format's line model and whose editor shows exactly those
 * lines (SPEC: a tool whose viewer shows other line numbers than the blob's must not offer them).
 */
class DiffReviewController(
    private val viewer: FrameDiffTool.DiffViewer,
    private val ctx: ReviewFileContext,
    private val request: ContentDiffRequest,
) : Disposable {
    private val project = ctx.project
    private val review = ctx.review
    private val change = ctx.change
    private val service = ReviewService.getInstance(project)

    private val targets: List<EditorTarget> = when (viewer) {
        is TwosideTextDiffViewer -> listOf(SideTarget(viewer.editor1, DiffSide.LEFT), SideTarget(viewer.editor2, DiffSide.RIGHT))
        is UnifiedDiffViewer -> listOf(UnifiedTarget(viewer))
        is OnesideTextDiffViewer -> listOf(SideTarget(viewer.editor, viewer.side))
        else -> emptyList()
    }

    private class Entry(val comment: Annotation, val inline: Inline?, val host: JPanel)

    private val entries = HashMap<String, Entry>()
    private val drafts = ArrayList<Inline>()
    private val hoverResets = ArrayList<() -> Unit>()

    /** A line of [t]'s editor that carries a hunk checkbox (else line 0), used to reserve the "+" gutter lane. */
    private fun spacerLineFor(t: EditorTarget): Int {
        val doc = t.editor.document
        return (hunks.firstOrNull { it.target === t }?.line ?: 0).coerceIn(0, doc.lineCount - 1)
    }
    private val hunkMarkers = ArrayList<Pair<EditorEx, RangeHighlighter>>()
    private var hunks: List<ViewerHunk> = emptyList()

    @Volatile
    private var disposed = false

    /** A hunk as the viewer shows it: its changed lines on each side that has line features, and the editor line that carries its checkbox. */
    private class ViewerHunk(val hunk: Hunk, val target: EditorTarget, val line: Int)

    /** Whether the editor of [side] shows exactly the lines of the format's line model (the document has one phantom line after a final newline). */
    private val sideOk: Map<Side, Boolean> = Side.entries.associateWith { side ->
        val lines = ctx.lines(side) ?: return@associateWith false
        val doc = documentOf(side.toDiff()) ?: return@associateWith false
        doc.lineCount == lines.size || doc.lineCount == lines.size + 1 || (lines.isEmpty() && doc.lineCount == 1)
    }

    private fun documentOf(side: DiffSide) = when (viewer) {
        is TwosideTextDiffViewer -> viewer.getContent(side)?.document
        is UnifiedDiffViewer -> viewer.getDocument(side)
        is OnesideTextDiffViewer -> if (viewer.side == side) viewer.content.document else null
        else -> null
    }

    private val listener = ReviewListener { SwingUtilities.invokeLater { if (!disposed) sync() } }

    init {
        Disposer.register(viewer, this)
        for (t in targets) {
            installHover(t)
            t.editor.scrollPane.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) = refitAll()
            })
        }
        service.addListener(this, listener)
        (viewer as? DiffViewerBase)?.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                computeHunks()
                sync()
            }
        })
    }

    override fun dispose() {
        disposed = true
        clearRendered()
    }

    private fun clearRendered() {
        entries.values.forEach { it.inline?.dispose() }
        entries.clear()
        drafts.forEach { it.dispose() }
        drafts.clear()
        hunkMarkers.forEach { (ed, hl) -> if (!ed.isDisposed) ed.markupModel.removeHighlighter(hl) }
        hunkMarkers.clear()
    }

    private fun refitAll() {
        entries.values.forEach { it.inline?.refresh() }
        drafts.forEach { it.refresh() }
    }

    // ---- sync ---------------------------------------------------------------------------------------------

    /** The comments to draw in this file: about this path, and still where they were written (current, or unchanged since). */
    private fun desired(): List<Annotation> = review.comments().filter { c ->
        val path = when (val a = c.anchor) {
            Anchor.Review -> null
            is Anchor.File -> a.path
            is Anchor.Lines -> a.path
        }
        path == change.path && when (review.commentState(c)) {
            CommentState.Current, CommentState.Unchanged -> true
            else -> false
        }
    }

    private fun sync() {
        if (disposed || targets.isEmpty()) return
        val desired = desired()
        val byId = desired.associateBy { it.id }
        for (id in entries.keys.toList()) {
            val e = entries[id]!!
            if (id !in byId || e.comment != byId[id]) {
                e.inline?.dispose()
                entries.remove(id)
            }
        }
        for ((i, c) in desired.withIndex()) if (c.id !in entries) render(c, i)
        syncHunkMarkers()
    }

    /** Where a comment is drawn: the end of its range on the new side if it has one there, else on the old side; a file comment goes above the first line. */
    private fun render(c: Annotation, priority: Int) {
        val target: EditorTarget
        val line: Int
        val above: Boolean
        when (val a = c.anchor) {
            Anchor.Review -> return
            is Anchor.File -> {
                val side = if (change.blob(Side.NEW) != null) DiffSide.RIGHT else DiffSide.LEFT
                target = targets.firstOrNull { it.hasSide(side) } ?: targets.first()
                line = 0
                above = true
            }
            is Anchor.Lines -> {
                val r = a.ranges.firstOrNull { it.side == Side.NEW && sideOk[Side.NEW] == true } ?: a.ranges.firstOrNull { sideOk[it.side] == true } ?: return
                val side = r.side.toDiff()
                target = targets.firstOrNull { it.hasSide(side) && it.toEditorLine(side, r.endLine - 1) >= 0 } ?: return
                line = target.toEditorLine(side, r.endLine - 1)
                above = false
            }
        }
        if (line < 0 || line >= target.editor.document.lineCount) return
        val host = JPanel(BorderLayout()).apply { isOpaque = false }
        showCard(host, c)
        val inline = addInlay(target.editor, line, above, host, priority)
        entries[c.id] = Entry(c, inline, host)
    }

    private fun showCard(host: JPanel, c: Annotation) {
        host.removeAll()
        host.add(CommentCard(c, cardActions), BorderLayout.CENTER)
        host.revalidate()
    }

    private val cardActions = object : CommentCardActions {
        override fun edit(c: Annotation) {
            val e = entries[c.id] ?: return
            e.host.removeAll()
            val composer = InlineComposer(
                initial = c,
                onSave = { kind, body, rule -> if (service.editComment(c, kind, body, rule)) null else "Not saved: see the notification." },
                onCancel = { showCard(e.host, c); e.inline?.refresh() },
            )
            e.host.add(composer, BorderLayout.CENTER)
            e.inline?.refresh()
            composer.focusText()
        }

        override fun setStatus(c: Annotation, status: AnnotationStatus) {
            service.setStatus(c, status)
        }

        override fun delete(c: Annotation) {
            if (service.deleteComment(c)) service.notify("Comment deleted.", NotificationType.INFORMATION, "Undo" to { service.restoreComment(c) })
        }

        override fun copyPrompt(c: Annotation) {
            val a = review
            com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(StringSelection(AgentPrompts.forComment(exportInput(a), c)))
            service.notify("Prompt for this comment copied to the clipboard.")
        }

        override fun reply(c: Annotation) {
            val e = entries[c.id] ?: return
            val composer = InlineReplyComposer(
                initial = "",
                saveLabel = "Reply",
                onSave = { text -> if (service.addReply(c, text)) null else "Not saved: see the notification." },
                onCancel = { showCard(e.host, c); e.inline?.refresh() },
            )
            e.host.add(composer, BorderLayout.SOUTH)
            e.inline?.refresh()
            composer.focusText()
        }

        override fun editReply(c: Annotation, r: Reply) {
            val e = entries[c.id] ?: return
            e.host.removeAll()
            val composer = InlineReplyComposer(
                initial = r.body,
                saveLabel = "Save",
                onSave = { text -> if (service.editReply(c, r.id, text)) null else "Not saved: see the notification." },
                onCancel = { showCard(e.host, c); e.inline?.refresh() },
            )
            e.host.add(composer, BorderLayout.CENTER)
            e.inline?.refresh()
            composer.focusText()
        }

        override fun deleteReply(c: Annotation, r: Reply) {
            if (service.deleteReply(c, r.id)) service.notify("Reply deleted.", NotificationType.INFORMATION, "Undo" to { service.restoreReply(c, r.id) })
        }
    }

    /** An inline component embedded in an editor; its content width follows the editor's visible width. */
    private class Inline(val inlay: Inlay<*>, val host: JComponent, val editor: EditorEx) {
        fun fit() {
            val w = (editor.scrollPane.viewport.width - JBUI.scale(56)).coerceIn(JBUI.scale(260), JBUI.scale(INLINE_WIDTH))
            (host as? JPanel)?.components?.forEach { if (it is WidthAdjustable) it.contentWidth = w }
        }

        fun refresh() {
            fit()
            host.revalidate()
            inlay.update()
        }

        fun dispose() = Disposer.dispose(inlay)
    }

    private fun addInlay(editor: EditorEx, line: Int, above: Boolean, component: JPanel, priority: Int): Inline? {
        val doc = editor.document
        val offset = if (above) doc.getLineStartOffset(line) else doc.getLineEndOffset(line)
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 12)
            add(component, BorderLayout.WEST)
        }
        val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editor, wrapper,
            EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(), null, false, above, false, true, priority, offset),
        ) ?: return null
        return Inline(inlay, component, editor).also { it.fit() }
    }

    // ---- adding comments --------------------------------------------------------------------------------------------

    /** Whether the 0-based [line] of [side] can carry a comment: that side has line features, and the line exists (there is none after a final newline). */
    private fun commentable(side: DiffSide, line: Int): Boolean {
        val s = side.toReview()
        val lines = ctx.lines(s) ?: return false
        return sideOk[s] == true && line in 0 until lines.size
    }

    private fun installHover(t: EditorTarget) {
        val editor = t.editor
        var hoverLine = -1
        var highlighter: RangeHighlighter? = null

        fun place(line: Int, renderer: GutterIconRenderer) {
            highlighter?.let { if (!editor.isDisposed) editor.markupModel.removeHighlighter(it) }
            val doc = editor.document
            highlighter = editor.markupModel.addRangeHighlighter(
                null, doc.getLineStartOffset(line), doc.getLineEndOffset(line), HighlighterLayer.LAST, HighlighterTargetArea.LINES_IN_RANGE,
            ).also { it.gutterIconRenderer = renderer }
        }

        // An invisible placeholder keeps the gutter lane reserved so showing/hiding the "+" never shifts the layout.
        fun clear() {
            hoverLine = -1
            if (!editor.isDisposed && editor.document.lineCount > 0) place(spacerLineFor(t), LaneSpacer)
        }

        fun moveTo(line: Int) {
            if (line == hoverLine) return
            val doc = editor.document
            if (line < 0 || line >= doc.lineCount) return clear()
            val at = t.fromEditorLine(line)
            if (at == null || !commentable(at.first, at.second)) return clear()
            hoverLine = line
            place(line, AddCommentRenderer(line) { startComposer(t, line) })
        }

        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                moveTo(editor.xyToLogicalPosition(e.mouseEvent.point).line)
            }
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(e: EditorMouseEvent) {
                // Keep the icon while the pointer is over the gutter: only clear when it leaves the editor entirely.
                val p = SwingUtilities.convertPoint(e.mouseEvent.component, e.mouseEvent.point, editor.component)
                if (!editor.component.contains(p)) clear()
            }
        }, this)
        hoverResets += { clear() }
        clear()
    }

    /**
     * The ranges a comment on the selected editor lines gets (ALGORITHMS 6): in a side-by-side diff one range on that side; in a unified diff one range
     * per side, each from the first to the last selected line that exists on that side (an unchanged line is on both). A single click is one line, on the
     * new side if the line is there.
     */
    private fun rangesFor(t: EditorTarget, clickedLine: Int, first: Int, last: Int, hasSelection: Boolean): List<Pair<Side, IntRange>> {
        if (t is SideTarget) {
            val side = t.side.toReview()
            return listOf(side to (first..last))
        }
        val perSide = Side.entries.associateWith { ArrayList<Int>() }
        val range = if (hasSelection) first..last else clickedLine..clickedLine
        for (l in range) {
            val both = t.bothSides(l) ?: continue
            if (both[0] >= 0) perSide.getValue(Side.OLD) += both[0]
            if (both[1] >= 0) perSide.getValue(Side.NEW) += both[1]
        }
        if (!hasSelection) {
            // One click: one line on one side; prefer the new side.
            val n = perSide.getValue(Side.NEW)
            return if (n.isNotEmpty()) listOf(Side.NEW to (n.first()..n.first())) else perSide.getValue(Side.OLD).firstOrNull()?.let { listOf(Side.OLD to (it..it)) }.orEmpty()
        }
        return Side.entries.mapNotNull { s -> perSide.getValue(s).takeIf { it.isNotEmpty() }?.let { s to (it.min()..it.max()) } }
    }

    private fun startComposer(t: EditorTarget, clickedLine: Int) {
        val editor = t.editor
        val doc = editor.document
        var startLine = clickedLine
        var endLine = clickedLine
        var hasSelection = false
        val sel = editor.selectionModel
        if (sel.hasSelection()) {
            val s = doc.getLineNumber(sel.selectionStart)
            var e = doc.getLineNumber(sel.selectionEnd)
            if (e > s && sel.selectionEnd == doc.getLineStartOffset(e)) e-- // a selection that ends at column 1 does not include that line
            if (clickedLine in s..e) {
                startLine = s
                endLine = e
                hasSelection = s != e || sel.selectionEnd - sel.selectionStart > 0
            }
        }
        val raw = if (t is SideTarget) {
            val a = t.fromEditorLine(startLine) ?: return
            val b = t.fromEditorLine(endLine) ?: a
            listOf(a.first.toReview() to (a.second..maxOf(a.second, b.second)))
        } else rangesFor(t, clickedLine, startLine, endLine, hasSelection)
        val ranges = raw.mapNotNull { (side, r) ->
            val lines = ctx.lines(side) ?: return@mapNotNull null
            val start = r.first + 1
            val end = minOf(r.last + 1, lines.size)
            if (sideOk[side] != true || start > end) null else AnchorRange(side, start, end, captureSnapshot(lines, start, end))
        }
        if (ranges.isEmpty()) return
        val anchor = Anchor.Lines(change.path, change.oldPath, review.pin, ranges)

        val host = JPanel(BorderLayout()).apply { isOpaque = false }
        var handle: Inline? = null
        fun dismiss() {
            handle?.let { drafts.remove(it); it.dispose() }
        }
        val composer = InlineComposer(
            initial = null,
            onSave = { kind, body, rule ->
                if (service.addComment(kind, body, anchor, rule) != null) { dismiss(); null } else "Not saved: see the notification."
            },
            onCancel = ::dismiss,
        )
        host.add(composer, BorderLayout.CENTER)
        handle = addInlay(editor, endLine, false, host, Int.MAX_VALUE)
        if (handle != null) drafts += handle
        composer.focusText()
    }

    private class AddCommentRenderer(val line: Int, val onClick: () -> Unit) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.InlineAdd
        override fun getTooltipText() = "Add review comment (selected lines, if any)"
        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = onClick()
        }
        override fun isNavigateAction() = true
        override fun getAlignment() = Alignment.LEFT
        override fun equals(other: Any?) = other is AddCommentRenderer && other.line == line
        override fun hashCode() = line
    }

    // ---- hunks --------------------------------------------------------------------------------------------------

    private fun interval(start0: Int, endExclusive0: Int): Interval? = if (endExclusive0 > start0) Interval(start0 + 1, endExclusive0) else null

    /** The hunk of the viewer with the sides that have line features: a region mark exists only for lines of the format's line model. */
    private fun hunkOf(old: Interval?, new: Interval?): Hunk? {
        val h = Hunk(old.takeIf { sideOk[Side.OLD] == true }, new.takeIf { sideOk[Side.NEW] == true })
        return h.takeIf { it.old != null || it.new != null }
    }

    private fun computeHunks() {
        val out = ArrayList<ViewerHunk>()
        when (viewer) {
            is SimpleDiffViewer -> {
                val target = targets.last()
                for (c in viewer.diffChanges) {
                    if (!c.isValid) continue
                    val h = hunkOf(
                        interval(c.getStartLine(DiffSide.LEFT), c.getEndLine(DiffSide.LEFT)),
                        interval(c.getStartLine(DiffSide.RIGHT), c.getEndLine(DiffSide.RIGHT)),
                    ) ?: continue
                    out += ViewerHunk(h, target, c.getStartLine(DiffSide.RIGHT))
                }
            }
            is UnifiedDiffViewer -> {
                val target = targets.first()
                for (c in viewer.diffChanges.orEmpty()) {
                    val f = c.lineFragment
                    val h = hunkOf(interval(f.startLine1, f.endLine1), interval(f.startLine2, f.endLine2)) ?: continue
                    out += ViewerHunk(h, target, c.line1)
                }
            }
            is OnesideTextDiffViewer -> {
                val doc = viewer.content.document
                val n = ctx.lines(viewer.side.toReview())?.size ?: 0
                if (n > 0) {
                    val whole = Interval(1, n)
                    val h = if (viewer.side == DiffSide.LEFT) hunkOf(whole, null) else hunkOf(null, whole)
                    if (h != null && doc.lineCount > 0) out += ViewerHunk(h, targets.first(), 0)
                }
            }
        }
        hunks = out
    }

    private fun syncHunkMarkers() {
        hunkMarkers.forEach { (ed, hl) -> if (!ed.isDisposed) ed.markupModel.removeHighlighter(hl) }
        hunkMarkers.clear()
        for ((i, h) in hunks.withIndex()) {
            val editor = h.target.editor
            val doc = editor.document
            if (doc.lineCount == 0) continue
            val line = h.line.coerceIn(0, doc.lineCount - 1)
            val hl = editor.markupModel.addRangeHighlighter(
                null, doc.getLineStartOffset(line), doc.getLineEndOffset(line), HighlighterLayer.LAST, HighlighterTargetArea.LINES_IN_RANGE,
            )
            val reviewed = service.isHunkReviewed(change, h.hunk)
            hl.gutterIconRenderer = HunkRenderer("$i:${h.hunk}", reviewed) { toggleHunk(h) }
            hunkMarkers += editor to hl
        }
        // Hunk lines changed: re-seat the lane spacer so it shares a line with a hunk checkbox (gutter width is per line).
        hoverResets.forEach { it() }
    }

    private fun toggleHunk(h: ViewerHunk) {
        val reviewedNow = service.isHunkReviewed(change, h.hunk)
        service.setHunkReviewed(change, hunks.map { it.hunk }, h.hunk, !reviewedNow)
    }

    private class HunkRenderer(val key: String, val viewed: Boolean, val onClick: () -> Unit) : GutterIconRenderer() {
        override fun getIcon(): Icon = ViewedIcon(viewed)
        override fun getTooltipText() = if (viewed) "Hunk reviewed. Click to unmark" else "Mark this hunk as reviewed"
        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = onClick()
        }
        override fun isNavigateAction() = true
        override fun getAlignment() = Alignment.RIGHT
        override fun equals(other: Any?) = other is HunkRenderer && other.key == key && other.viewed == viewed
        override fun hashCode() = key.hashCode() * 31 + viewed.hashCode()
    }
}

/** Invisible gutter-lane placeholder: keeps the "+" lane reserved so the gutter never changes width on hover. */
private object LaneSpacer : GutterIconRenderer() {
    override fun getIcon(): Icon = com.intellij.util.ui.EmptyIcon.create(AllIcons.General.InlineAdd)
    override fun getAlignment() = Alignment.LEFT
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 1
}

/** A checkbox: empty outline when not reviewed, green with a tick when reviewed. */
private class ViewedIcon(private val viewed: Boolean) : Icon {
    override fun getIconWidth() = JBUI.scale(14)
    override fun getIconHeight() = JBUI.scale(14)
    override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            val s = iconWidth
            val arc = JBUI.scale(4)
            if (viewed) {
                g2.color = com.intellij.ui.JBColor(java.awt.Color(0x3E9B4F), java.awt.Color(0x4EA55F))
                g2.fillRoundRect(x, y, s, s, arc, arc)
                g2.color = java.awt.Color.WHITE
                g2.stroke = java.awt.BasicStroke(JBUI.scale(2).toFloat(), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g2.drawPolyline(
                    intArrayOf(x + s * 22 / 100, x + s * 42 / 100, x + s * 78 / 100),
                    intArrayOf(y + s * 52 / 100, y + s * 72 / 100, y + s * 28 / 100), 3,
                )
            } else {
                g2.color = com.intellij.ui.JBColor(java.awt.Color(0x8C8C8C), java.awt.Color(0x8A8A8A))
                g2.stroke = java.awt.BasicStroke(JBUI.scale(1).toFloat() * 1.3f)
                g2.drawRoundRect(x, y, s - 1, s - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
    }
}
