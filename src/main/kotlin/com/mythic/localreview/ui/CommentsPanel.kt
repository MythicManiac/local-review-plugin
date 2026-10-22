package com.mythic.localreview.ui

import com.intellij.diff.util.Side as DiffSide
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.mythic.localreview.export.AgentPrompts
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.Annotation
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.CommentState
import com.mythic.localreview.format.Decision
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.label
import com.mythic.localreview.ui.comments.CommentDialog
import com.mythic.localreview.ui.comments.ReplyDialog
import com.mythic.localreview.ui.diff.ReviewDiff
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel

/** Every comment of the active review, filterable; double-click jumps to the code. */
class CommentsPanel(private val project: Project, private val service: ReviewService) : JPanel(BorderLayout()), Disposable, UiDataProvider {
    private enum class StatusFilter(val label: String) { OPEN("Open"), CLOSED("Resolved / won't fix"), ALL("All"), DELETED("Deleted") }

    private val model = DefaultListModel<Annotation>()
    private val list = JBList(model)
    private val statusBox = ComboBox(StatusFilter.entries.toTypedArray())
    private val kindBox = ComboBox((listOf<Kind?>(null) + Kind.entries).toTypedArray())
    private val outdatedOnly = JBCheckBox("Outdated only")

    /** Called after each refresh with the number of open comments (for the tab title). */
    var onCountChanged: (Int) -> Unit = {}

    init {
        statusBox.renderer = textListCellRenderer<StatusFilter> { it.label }
        kindBox.renderer = textListCellRenderer<Kind?> { it?.label ?: "All kinds" }
        val filters = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { add(statusBox); add(kindBox); add(outdatedOnly) }
        statusBox.addActionListener { refresh() }
        kindBox.addActionListener { refresh() }
        outdatedOnly.addActionListener { refresh() }

        list.cellRenderer = object : ColoredListCellRenderer<Annotation>() {
            override fun customizeCellRenderer(l: JList<out Annotation>, c: Annotation, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = c.kind.icon
                val gray = SimpleTextAttributes.GRAYED_ATTRIBUTES
                val a = service.active
                val state = a?.commentState(c)
                if (c.deleted) append("[deleted] ", SimpleTextAttributes.ERROR_ATTRIBUTES)
                if (state is CommentState.Outdated) append(if (state.suggestion != null) "[outdated, suggestion] " else "[outdated] ", SimpleTextAttributes.ERROR_ATTRIBUTES)
                else if (state == null && a != null && c.anchor !is Anchor.Review) append(if (a.verified) "[checking] " else "[unverified] ", gray)
                if (c.status != AnnotationStatus.OPEN) append("[${c.status.label}] ", gray)
                append(whereText(c), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, c.kind.color))
                append("  " + c.body.trim().lineSequence().first().take(120), if (c.status == AnnotationStatus.OPEN && !c.deleted) SimpleTextAttributes.REGULAR_ATTRIBUTES else gray)
                if (c.isRuleCandidate) append(if (c.ruleText.isNullOrBlank()) "  · rule (needs wording)" else "  · rule", gray)
                val replies = c.replies.count { !it.deleted }
                if (replies > 0) append("  · $replies repl${if (replies == 1) "y" else "ies"}", gray)
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) list.selectedValue?.let(::navigate)
            }
        })
        PopupHandler.installPopupMenu(list, popupGroup(), "LocalReview.CommentsPopup")

        add(filters, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        service.addListener(this) { refresh() }
        refresh()
    }

    private fun whereText(c: Annotation): String = when (val a = c.anchor) {
        Anchor.Review -> "Review"
        is Anchor.File -> a.path
        is Anchor.Lines -> a.path + ":" + a.ranges.joinToString("/") { if (it.endLine != it.startLine) "${it.startLine}-${it.endLine}" else "${it.startLine}" }
    }

    private fun sortKey(c: Annotation): Triple<String, Int, Long> = when (val a = c.anchor) {
        Anchor.Review -> Triple("", 0, c.createdAt)
        is Anchor.File -> Triple(a.path, 0, c.createdAt)
        is Anchor.Lines -> Triple(a.path, a.ranges.minOf { it.startLine }, c.createdAt)
    }

    fun refresh() {
        val a = service.active
        val sel = list.selectedValue?.id
        model.clear()
        if (a != null) {
            val status = statusBox.selectedItem as StatusFilter
            val kind = kindBox.selectedItem as Kind?
            val all = a.comments(includeDeleted = true)
            val shown = all.filter { c ->
                (kind == null || c.kind == kind) &&
                    (!outdatedOnly.isSelected || a.commentState(c) is CommentState.Outdated) &&
                    when (status) {
                        StatusFilter.OPEN -> !c.deleted && c.status == AnnotationStatus.OPEN
                        StatusFilter.CLOSED -> !c.deleted && c.status != AnnotationStatus.OPEN
                        StatusFilter.ALL -> !c.deleted
                        StatusFilter.DELETED -> c.deleted
                    }
            }.sortedWith(compareBy({ it.anchor !is Anchor.Review }, { sortKey(it).first }, { sortKey(it).second }, { sortKey(it).third }, { it.id }))
            shown.forEach(model::addElement)
            onCountChanged(all.count { !it.deleted && it.status == AnnotationStatus.OPEN })
            if (sel != null) shown.indexOfFirst { it.id == sel }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
        } else {
            onCountChanged(0)
        }
    }

    private fun selected(): List<Annotation> = list.selectedValuesList

    override fun uiDataSnapshot(sink: DataSink) {
        sink[ReviewDataKeys.SELECTED_COMMENTS] = selected()
    }

    /** Opens the diff at the comment: where it was written if the code there is unchanged, else where the suggestion says it went. */
    fun navigate(c: Annotation) {
        val a = service.active ?: return
        if (c.anchor is Anchor.Review) return com.mythic.localreview.ui.notes.ReviewNotes.open(project, reveal = c.id)
        val file = a.fileOf(c) ?: return edit(c)
        val lines = c.anchor as? Anchor.Lines
        val scroll = when {
            lines == null -> null
            a.commentState(c) is CommentState.Outdated -> a.suggestionOf(c)?.ranges?.let { r -> (r.firstOrNull { it.side == Side.NEW } ?: r.first()).let { x -> x.side.toDiff() to (x.startLine - 1) } }
            else -> (lines.ranges.firstOrNull { it.side == Side.NEW } ?: lines.ranges.first()).let { x -> x.side.toDiff() to (x.startLine - 1) }
        }
        ReviewDiff.open(project, file, scroll)
    }

    private fun Side.toDiff() = if (this == Side.OLD) DiffSide.LEFT else DiffSide.RIGHT

    private fun edit(c: Annotation) {
        val dlg = CommentDialog(project, "Edit comment", c)
        if (dlg.showAndGet()) service.editComment(c, dlg.kind, dlg.text, dlg.rule)
    }

    private fun action(text: String, enabled: (List<Annotation>) -> Boolean, run: (Annotation) -> Unit): AnAction = object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = selected().let { it.isNotEmpty() && enabled(it) } }
        override fun actionPerformed(e: AnActionEvent) { selected().forEach(run) }
    }

    private fun popupGroup(): DefaultActionGroup {
        val a = { service.active!! }
        return DefaultActionGroup(
            action("Go to Code", { it.size == 1 && !it[0].deleted }) { navigate(it) },
            action("Edit…", { it.size == 1 && !it[0].deleted }) { edit(it) },
            action("Reply…", { it.size == 1 && !it[0].deleted }) { c ->
                val dlg = ReplyDialog(project, "Reply to comment")
                if (dlg.showAndGet()) service.addReply(c, dlg.text)
            },
            action("Accept Suggested Position", { l -> l.size == 1 && !l[0].deleted && a().suggestionOf(l[0]) != null }) { service.acceptSuggestion(it) },
            action("Resolve", { l -> l.any { it.status == AnnotationStatus.OPEN && !it.deleted } }) { c -> if (c.status == AnnotationStatus.OPEN) service.setStatus(c, AnnotationStatus.RESOLVED) },
            action("Won't Fix", { l -> l.any { it.status == AnnotationStatus.OPEN && !it.deleted } }) { c -> if (c.status == AnnotationStatus.OPEN) service.setStatus(c, AnnotationStatus.WONT_FIX) },
            action("Reopen", { l -> l.any { it.status != AnnotationStatus.OPEN && !it.deleted } }) { c -> if (c.status != AnnotationStatus.OPEN) service.setStatus(c, AnnotationStatus.OPEN) },
            action("Copy as Agent Prompt", { l -> l.none { it.deleted } }) { c ->
                CopyPasteManager.getInstance().setContents(StringSelection(AgentPrompts.forComment(exportInput(a()), c)))
            },
            action("Dismiss Rule Candidate", { l -> l.any { it.isRuleCandidate && it.ruleDecision == null && !it.deleted } }) { c ->
                if (c.isRuleCandidate && c.ruleDecision == null) service.decideRules(listOf(c), AGENTS_MD, Decision.DISMISSED)
            },
            action("Delete", { l -> l.any { !it.deleted } }) { c -> if (!c.deleted) service.deleteComment(c) },
            action("Restore", { l -> l.any { it.deleted } }) { c -> if (c.deleted) service.restoreComment(c) },
        )
    }

    override fun dispose() {}

    private companion object {
        const val AGENTS_MD = "AGENTS.md"
    }
}
