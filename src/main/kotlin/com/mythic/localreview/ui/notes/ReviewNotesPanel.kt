package com.mythic.localreview.ui.notes

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.mythic.localreview.export.AgentPrompts
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.Annotation
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.Reply
import com.mythic.localreview.ui.ReviewService
import com.mythic.localreview.ui.comments.CommentCard
import com.mythic.localreview.ui.comments.CommentCardActions
import com.mythic.localreview.ui.comments.InlineComposer
import com.mythic.localreview.ui.comments.InlineReplyComposer
import com.mythic.localreview.ui.comments.WidthAdjustable
import com.mythic.localreview.ui.exportInput
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities

/**
 * The notes of the whole review: comments with `scope: review`, which belong to no lines (an architectural concern across several files). They are
 * ordinary comments with the same threads as any other: add, edit, reply, resolve, delete with undo. The list follows the review's state live, and a
 * note being edited is left alone until its comment actually changes.
 */
class ReviewNotesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = ReviewService.getInstance(project)

    /** A note as drawn: the comment it shows (to tell when it changed), and the panel that holds its card or its composer. */
    private class Row(var comment: Annotation, val host: JPanel)

    private val rows = LinkedHashMap<String, Row>()
    private val list = ListPanel()
    private val addHost = JPanel(BorderLayout()).apply { isOpaque = false; alignmentX = 0f }
    private val summary = JBLabel().apply { border = JBUI.Borders.empty(2, 4) }
    private val showClosed = JBCheckBox("Show resolved")
    private val addLink = ActionLink("Add a note…") { startAdding() }
    private val empty = JBLabel().apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(12, 4); alignmentX = 0f }
    private var composer: InlineComposer? = null
    private var disposed = false

    /** A vertical stack whose width follows the viewport, so the cards wrap instead of scrolling sideways. */
    private class ListPanel : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 10)
        }

        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(24)
        override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height - JBUI.scale(24)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    init {
        val top = JPanel(FlowLayout(FlowLayout.LEFT, 10, 4)).apply {
            add(addLink)
            add(showClosed)
            add(summary)
        }
        showClosed.addActionListener { sync() }
        add(top, BorderLayout.NORTH)
        val scroll = ScrollPaneFactory.createScrollPane(list, true)
        add(scroll, BorderLayout.CENTER)
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = fit()
        })
    }

    /** The ids of the notes drawn now, in order (for tests). */
    internal fun shownNoteIds(): List<String> = list.components.mapNotNull { c -> rows.entries.firstOrNull { it.value.host === c }?.key }

    fun preferredFocus(): JComponent? = composer?.form?.bodyArea ?: addLink

    override fun dispose() {
        disposed = true
    }

    // ---- the list ---------------------------------------------------------------------------------------------

    private fun notes(): List<Annotation> {
        val a = service.active ?: return emptyList()
        return a.comments().filter { it.anchor is Anchor.Review && (showClosed.isSelected || it.status == AnnotationStatus.OPEN) }
    }

    private fun sync() {
        val a = service.active
        addLink.isVisible = a != null
        val all = a?.comments()?.filter { it.anchor is Anchor.Review }.orEmpty()
        val open = all.count { it.status == AnnotationStatus.OPEN }
        summary.text = when {
            a == null -> "No review open"
            all.isEmpty() -> ""
            else -> "$open open" + if (all.size > open) ", ${all.size - open} resolved" else ""
        }
        val shown = notes()
        val byId = shown.associateBy { it.id }
        for (id in rows.keys.toList()) if (id !in byId) rows.remove(id)
        for (c in shown) {
            val row = rows[c.id]
            if (row == null) rows[c.id] = Row(c, JPanel(BorderLayout()).apply { isOpaque = false; alignmentX = 0f }).also { showCard(it.host, c) }
            else if (row.comment != c) {
                row.comment = c
                showCard(row.host, c)
            }
        }
        list.removeAll()
        list.add(addHost)
        empty.text = when {
            a == null -> "Open a review to write notes about it."
            shown.isEmpty() && all.isEmpty() -> "No notes yet. Use \"Add a note…\" for a concern that belongs to the whole review rather than to any lines."
            shown.isEmpty() -> "No open notes."
            else -> ""
        }
        if (empty.text.isNotEmpty()) list.add(empty)
        for (c in shown) {
            rows.getValue(c.id).host.also {
                list.add(it)
                list.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
            }
        }
        fit()
        list.revalidate()
        list.repaint()
    }

    private fun showCard(host: JPanel, c: Annotation) {
        host.removeAll()
        host.add(CommentCard(c, actions), BorderLayout.CENTER)
        fit()
        host.revalidate()
    }

    /** Gives every card the width of the viewport. */
    private fun fit() {
        val viewport = SwingUtilities.getAncestorOfClass(javax.swing.JViewport::class.java, list) ?: return
        val w = (viewport.width - JBUI.scale(40)).coerceIn(JBUI.scale(150), JBUI.scale(1000))
        if (empty.text.isNotEmpty() && !empty.text.startsWith("<html>")) empty.text = "<html><body style='width: " + w + "px'>" + empty.text.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>"
        fun apply(c: java.awt.Container) {
            for (child in c.components) {
                if (child is WidthAdjustable) child.contentWidth = w
                if (child is java.awt.Container) apply(child)
            }
        }
        apply(list)
        list.revalidate()
    }

    /** Scrolls to a note and flashes its border. */
    fun reveal(annotationId: String) {
        val row = rows[annotationId] ?: return
        SwingUtilities.invokeLater {
            list.scrollRectToVisible(row.host.bounds)
            val before = row.host.border
            row.host.border = javax.swing.BorderFactory.createLineBorder(JBColor(Color(0xF0, 0xA0, 0x20), Color(0xF0, 0xA0, 0x20)), 2)
            javax.swing.Timer(1500) { row.host.border = before; row.host.repaint() }.apply { isRepeats = false; start() }
        }
    }

    // ---- adding ---------------------------------------------------------------------------------------------

    private fun startAdding() {
        if (composer != null) {
            composer?.focusText()
            return
        }
        val c = InlineComposer(
            initial = null,
            defaultKind = Kind.COMMENT,
            onSave = { kind, body, rule ->
                if (service.addComment(kind, body, Anchor.Review, rule) != null) {
                    stopAdding()
                    null
                } else "Not saved: see the notification."
            },
            onCancel = ::stopAdding,
            compact = true,
        )
        composer = c
        addHost.add(c, BorderLayout.CENTER)
        addHost.border = JBUI.Borders.emptyBottom(10)
        fit()
        addHost.revalidate()
        c.focusText()
    }

    private fun stopAdding() {
        composer?.let { addHost.remove(it) }
        composer = null
        addHost.border = null
        addHost.revalidate()
        addHost.repaint()
    }

    // ---- what the card's buttons do ---------------------------------------------------------------------------------

    private val actions = object : CommentCardActions {
        override fun edit(c: Annotation) {
            val row = rows[c.id] ?: return
            row.host.removeAll()
            val ed = InlineComposer(
                initial = c,
                onSave = { kind, body, rule -> if (service.editComment(c, kind, body, rule)) null else "Not saved: see the notification." },
                onCancel = { showCard(row.host, c) },
                compact = true,
            )
            row.host.add(ed, BorderLayout.CENTER)
            fit()
            row.host.revalidate()
            ed.focusText()
        }

        override fun setStatus(c: Annotation, status: AnnotationStatus) {
            service.setStatus(c, status)
        }

        override fun delete(c: Annotation) {
            if (service.deleteComment(c)) service.notify("Note deleted.", NotificationType.INFORMATION, "Undo" to { service.restoreComment(c) })
        }

        override fun copyPrompt(c: Annotation) {
            val a = service.active ?: return
            CopyPasteManager.getInstance().setContents(StringSelection(AgentPrompts.forComment(exportInput(a), c)))
            service.notify("Prompt for this note copied to the clipboard.")
        }

        override fun reply(c: Annotation) {
            val row = rows[c.id] ?: return
            val ed = InlineReplyComposer(
                initial = "",
                saveLabel = "Reply",
                onSave = { text -> if (service.addReply(c, text)) null else "Not saved: see the notification." },
                onCancel = { showCard(row.host, c) },
            )
            row.host.add(ed, BorderLayout.SOUTH)
            fit()
            row.host.revalidate()
            ed.focusText()
        }

        override fun editReply(c: Annotation, r: Reply) {
            val row = rows[c.id] ?: return
            row.host.removeAll()
            val ed = InlineReplyComposer(
                initial = r.body,
                saveLabel = "Save",
                onSave = { text -> if (service.editReply(c, r.id, text)) null else "Not saved: see the notification." },
                onCancel = { showCard(row.host, c) },
            )
            row.host.add(ed, BorderLayout.CENTER)
            fit()
            row.host.revalidate()
            ed.focusText()
        }

        override fun deleteReply(c: Annotation, r: Reply) {
            if (service.deleteReply(c, r.id)) service.notify("Reply deleted.", NotificationType.INFORMATION, "Undo" to { service.restoreReply(c, r.id) })
        }
    }
    // Last, so that everything `sync` uses (the card actions above) exists before the first card is built.
    init {
        service.addListener(this) { SwingUtilities.invokeLater { if (!disposed) sync() } }
        sync()
    }
}
