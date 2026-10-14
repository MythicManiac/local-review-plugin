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
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side as DiffSide
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Document
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
import com.mythic.localreview.core.Anchor
import com.mythic.localreview.core.AnchorScope
import com.mythic.localreview.core.Anchoring
import com.mythic.localreview.core.Comment
import com.mythic.localreview.core.CommentKind
import com.mythic.localreview.core.CommentStatus
import com.mythic.localreview.core.Fingerprints
import com.mythic.localreview.core.Side
import com.mythic.localreview.export.AgentPrompts
import com.mythic.localreview.ui.ReviewService
import com.mythic.localreview.ui.comments.CommentCard
import com.mythic.localreview.ui.comments.CommentCardActions
import com.mythic.localreview.ui.comments.InlineComposer
import com.mythic.localreview.ui.comments.INLINE_WIDTH
import com.mythic.localreview.ui.comments.WidthAdjustable
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.io.IOException
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

private fun DiffSide.toReview(): Side = if (this == DiffSide.LEFT) Side.BASE else Side.HEAD
private fun Side.toDiff(): DiffSide = if (this == Side.BASE) DiffSide.LEFT else DiffSide.RIGHT

/** One editor of a diff viewer plus how its lines map to (side, line) pairs of the compared files. */
private abstract class EditorTarget(val editor: EditorEx) {
    /** Editor line for a 0-based line of [side], or -1 if that side's line is not shown in this editor. */
    abstract fun toEditorLine(side: DiffSide, line: Int): Int

    /** (side, 0-based line) a new comment at [editorLine] would attach to, or null if not commentable. */
    abstract fun fromEditorLine(editorLine: Int): Pair<DiffSide, Int>?

    abstract fun hasSide(side: DiffSide): Boolean
}

private class SideTarget(editor: EditorEx, val side: DiffSide) : EditorTarget(editor) {
    override fun toEditorLine(side: DiffSide, line: Int) = if (side == this.side) line else -1
    override fun fromEditorLine(editorLine: Int) = side to editorLine
    override fun hasSide(side: DiffSide) = side == this.side
}

private class UnifiedTarget(val viewer: UnifiedDiffViewer) : EditorTarget(viewer.editor) {
    override fun toEditorLine(side: DiffSide, line: Int) = viewer.transferLineToOnesideStrict(side, line)
    override fun fromEditorLine(editorLine: Int): Pair<DiffSide, Int>? {
        val r = viewer.transferLineFromOnesideStrict(editorLine) ?: return null
        return r.second to r.first[r.second.index]
    }
    override fun hasSide(side: DiffSide) = true
}

/**
 * Attaches the review UI to one diff viewer: inline comment cards, a hover "+" to add comments, and per-hunk
 * viewed markers. Everything is derived from [ReviewFileContext.review]'s store, so it re-renders on any store change.
 */
class DiffReviewController(
    private val viewer: FrameDiffTool.DiffViewer,
    private val ctx: ReviewFileContext,
    private val request: ContentDiffRequest,
) : Disposable {
    private val project = ctx.project
    private val review = ctx.review
    private val store = review.store
    private val change = ctx.change
    private val service = ReviewService.getInstance(project)

    private val targets: List<EditorTarget> = when (viewer) {
        is TwosideTextDiffViewer -> listOf(SideTarget(viewer.editor1, DiffSide.LEFT), SideTarget(viewer.editor2, DiffSide.RIGHT))
        is UnifiedDiffViewer -> listOf(UnifiedTarget(viewer))
        is OnesideTextDiffViewer -> listOf(SideTarget(viewer.editor, viewer.side))
        else -> emptyList()
    }

    private class Entry(val comment: Comment, val inline: Inline?, val host: JPanel)

    private val entries = HashMap<String, Entry>()
    private val drafts = ArrayList<Inline>()
    private val hunkMarkers = ArrayList<Pair<EditorEx, RangeHighlighter>>()
    private var hunks: List<Hunk> = emptyList()
    private val storeListener: () -> Unit = { SwingUtilities.invokeLater { if (!disposed) sync() } }

    @Volatile
    private var disposed = false

    private class Hunk(val fingerprint: String, val target: EditorTarget, val line: Int)

    init {
        Disposer.register(viewer, this)
        for (t in targets) {
            installHover(t)
            t.editor.scrollPane.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) = refitAll()
            })
        }
        store.addListener(storeListener)
        (viewer as? DiffViewerBase)?.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                computeHunks()
                sync()
            }
        })
    }

    override fun dispose() {
        disposed = true
        store.removeListener(storeListener)
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

    private fun matches(a: Anchor) =
        (a.side == Side.HEAD && a.path == change.newPath) || (a.side == Side.BASE && a.path == change.oldPath)

    private fun sync() {
        if (disposed || targets.isEmpty()) return
        val rev = store.review().current.n
        val desired = store.comments().filter { it.anchor.scope != AnchorScope.REVIEW && !it.outdated && it.anchor.revision == rev && matches(it.anchor) }
        val desiredIds = desired.map { it.id }.toSet()
        for (id in entries.keys.toList()) {
            val e = entries[id]!!
            if (id !in desiredIds || e.comment != desired.first { it.id == id }) {
                e.inline?.dispose()
                entries.remove(id)
            }
        }
        for ((i, c) in desired.withIndex()) if (c.id !in entries) render(c, i)
        syncHunkMarkers()
    }

    private fun render(c: Comment, priority: Int) {
        val a = c.anchor
        val side = a.side.toDiff()
        val target: EditorTarget
        val line: Int
        val above: Boolean
        if (a.scope == AnchorScope.FILE) {
            target = targets.firstOrNull { it.hasSide(side) } ?: targets.first()
            line = 0
            above = true
        } else {
            target = targets.firstOrNull { it.hasSide(side) && it.toEditorLine(side, a.endLine!! - 1) >= 0 } ?: return
            line = target.toEditorLine(side, a.endLine!! - 1)
            above = false
            if (line >= target.editor.document.lineCount) return
        }
        val host = JPanel(BorderLayout()).apply { isOpaque = false }
        showCard(host, c)
        val inline = addInlay(target.editor, line, above, host, priority)
        entries[c.id] = Entry(c, inline, host)
    }

    private fun showCard(host: JPanel, c: Comment) {
        host.removeAll()
        host.add(CommentCard(c, cardActions), BorderLayout.CENTER)
        host.revalidate()
    }

    private val cardActions = object : CommentCardActions {
        override fun edit(c: Comment) {
            val e = entries[c.id] ?: return
            e.host.removeAll()
            val composer = InlineComposer(
                initial = c,
                onSave = { kind, body, rule ->
                    saving { store.updateComment(c.id) { it.copy(kind = kind, body = body, rule = rule) } }
                },
                onCancel = { showCard(e.host, c); e.inline?.refresh() },
            )
            e.host.add(composer, BorderLayout.CENTER)
            e.inline?.refresh()
            composer.focusText()
        }

        override fun setStatus(c: Comment, status: CommentStatus) {
            saving { store.updateComment(c.id) { it.copy(status = status) } }
        }

        override fun delete(c: Comment) {
            saving { store.deleteComment(c.id) }
            service.notify("Comment deleted.", NotificationType.INFORMATION, "Undo" to { store.restoreComment(c.id) })
        }

        override fun copyPrompt(c: Comment) {
            com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(StringSelection(AgentPrompts.forComment(store.review(), c)))
            service.notify("Prompt for this comment copied to the clipboard.")
        }
    }

    /** Runs a store mutation, reporting I/O failure to the user. Returns an error message or null. */
    private fun saving(block: () -> Any?): String? = try {
        block()
        null
    } catch (e: IOException) {
        service.notify("Could not save to the review folder: ${e.message}", NotificationType.ERROR)
        "Could not save: ${e.message}"
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

    private fun sideLines(side: DiffSide): List<String> {
        val content = request.contents.getOrNull(side.index) as? DocumentContent ?: return emptyList()
        return Anchoring.splitLines(content.document.text)
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
            if (!editor.isDisposed && editor.document.lineCount > 0) place(0, LaneSpacer)
        }

        fun moveTo(line: Int) {
            if (line == hoverLine) return
            val doc = editor.document
            if (line < 0 || line >= doc.lineCount || t.fromEditorLine(line) == null) return clear()
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
        clear()
    }

    private fun startComposer(t: EditorTarget, clickedLine: Int) {
        val editor = t.editor
        val doc = editor.document
        var startLine = clickedLine
        var endLine = clickedLine
        val sel = editor.selectionModel
        if (sel.hasSelection()) {
            val s = doc.getLineNumber(sel.selectionStart)
            var e = doc.getLineNumber(sel.selectionEnd)
            if (e > s && sel.selectionEnd == doc.getLineStartOffset(e)) e--
            if (clickedLine in s..e) { startLine = s; endLine = e }
        }
        val from = t.fromEditorLine(startLine) ?: return
        var to = t.fromEditorLine(endLine) ?: from
        var fromLine = from
        if (from.first != to.first) { // selection spans both sides of a unified diff: keep just the clicked line
            fromLine = t.fromEditorLine(clickedLine) ?: return
            to = fromLine
            endLine = clickedLine
        }
        val side = fromLine.first
        val first = fromLine.second
        val last = maxOf(first, to.second)
        val path = (if (side == DiffSide.LEFT) change.oldPath else change.newPath) ?: return
        val lines = sideLines(side)
        val cap = Anchoring.capture(lines, first + 1, last + 1)
        val anchor = Anchor(
            AnchorScope.LINES, path, side.toReview(), first + 1, last + 1, cap.snippet, cap.before, cap.after, store.review().current.n,
        )

        val host = JPanel(BorderLayout()).apply { isOpaque = false }
        var handle: Inline? = null
        fun dismiss() {
            handle?.let { drafts.remove(it); it.dispose() }
        }
        val composer = InlineComposer(
            initial = null,
            onSave = { kind, body, rule ->
                saving { store.addComment(kind, body, anchor, rule) }.also { err -> if (err == null) dismiss() }
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

    private fun lines(doc: Document, from: Int, to: Int): List<String> =
        if (to <= from) emptyList() else DiffUtil.getLines(doc, from, to)

    private fun computeHunks() {
        val raw = ArrayList<Triple<Pair<List<String>, List<String>>, EditorTarget, Int>>()
        when (viewer) {
            is SimpleDiffViewer -> {
                val target = targets.last()
                for (c in viewer.diffChanges) {
                    if (!c.isValid) continue
                    val l = lines(viewer.document1(), c.getStartLine(DiffSide.LEFT), c.getEndLine(DiffSide.LEFT))
                    val r = lines(viewer.document2(), c.getStartLine(DiffSide.RIGHT), c.getEndLine(DiffSide.RIGHT))
                    raw += Triple(l to r, target, c.getStartLine(DiffSide.RIGHT))
                }
            }
            is UnifiedDiffViewer -> {
                val target = targets.first()
                for (c in viewer.diffChanges.orEmpty()) {
                    val f = c.lineFragment
                    val l = lines(viewer.getDocument(DiffSide.LEFT), f.startLine1, f.endLine1)
                    val r = lines(viewer.getDocument(DiffSide.RIGHT), f.startLine2, f.endLine2)
                    raw += Triple(l to r, target, c.line1)
                }
            }
            is OnesideTextDiffViewer -> {
                val doc = viewer.content.document
                val all = lines(doc, 0, doc.lineCount)
                val pair = if (viewer.side == DiffSide.LEFT) all to emptyList() else emptyList<String>() to all
                raw += Triple(pair, targets.first(), 0)
            }
        }
        val fps = Fingerprints.hunks(raw.map { it.first })
        hunks = raw.mapIndexed { i, t -> Hunk(fps[i], t.second, t.third) }
        ctx.hunkFingerprints = fps
        store.reportHunks(change.path, change.fingerprint, fps.size)
    }

    private fun SimpleDiffViewer.document1() = getContent1().document
    private fun SimpleDiffViewer.document2() = getContent2().document

    private fun isHunkViewed(h: Hunk): Boolean {
        val p = store.progress(change.path)
        return review.isViewed(change, p) || (p?.viewedHunks?.contains(h.fingerprint) == true)
    }

    private fun syncHunkMarkers() {
        hunkMarkers.forEach { (ed, hl) -> if (!ed.isDisposed) ed.markupModel.removeHighlighter(hl) }
        hunkMarkers.clear()
        for (h in hunks) {
            val editor = h.target.editor
            val doc = editor.document
            if (doc.lineCount == 0) continue
            val line = h.line.coerceIn(0, doc.lineCount - 1)
            val hl = editor.markupModel.addRangeHighlighter(
                null, doc.getLineStartOffset(line), doc.getLineEndOffset(line), HighlighterLayer.LAST, HighlighterTargetArea.LINES_IN_RANGE,
            )
            hl.gutterIconRenderer = HunkRenderer(h.fingerprint, isHunkViewed(h)) { toggleHunk(h) }
            hunkMarkers += editor to hl
        }
    }

    private fun toggleHunk(h: Hunk) {
        val viewedNow = isHunkViewed(h)
        val all = ctx.hunkFingerprints
        saving {
            // A file-level "viewed" mark implies all hunks; materialise that before un-viewing a single one.
            if (viewedNow && review.isViewed(change)) store.setFileViewed(change.path, change.fingerprint, true, all)
            store.setHunkViewed(change.path, change.fingerprint, h.fingerprint, !viewedNow, all)
        }
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
