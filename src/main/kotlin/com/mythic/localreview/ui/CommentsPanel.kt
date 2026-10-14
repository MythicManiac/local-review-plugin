package com.mythic.localreview.ui

import com.intellij.diff.util.Side as DiffSide
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.DataSink
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
import com.intellij.util.ui.JBUI
import com.mythic.localreview.core.AnchorScope
import com.mythic.localreview.core.Comment
import com.mythic.localreview.core.CommentKind
import com.mythic.localreview.core.CommentStatus
import com.mythic.localreview.core.Side
import com.mythic.localreview.export.AgentPrompts
import com.mythic.localreview.ui.comments.CommentDialog
import com.mythic.localreview.ui.comments.color
import com.mythic.localreview.ui.comments.icon
import com.mythic.localreview.ui.diff.ReviewDiff
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel

/** Every comment of the active review, filterable; double-click jumps to the code. */
class CommentsPanel(private val project: Project, private val service: ReviewService) : JPanel(BorderLayout()), Disposable, UiDataProvider {
    private enum class StatusFilter(val label: String) { OPEN("Open"), CLOSED("Resolved / won't fix"), ALL("All"), DELETED("Deleted") }

    private val model = DefaultListModel<Comment>()
    private val list = JBList(model)
    private val statusBox = ComboBox(StatusFilter.entries.toTypedArray())
    private val kindBox = ComboBox((listOf<CommentKind?>(null) + CommentKind.entries).toTypedArray())
    private val outdatedOnly = JBCheckBox("Outdated only")

    /** Called after each refresh with the number of open comments (for the tab title). */
    var onCountChanged: (Int) -> Unit = {}

    init {
        statusBox.renderer = textListCellRenderer<StatusFilter> { it.label }
        kindBox.renderer = textListCellRenderer<CommentKind?> { it?.label ?: "All kinds" }
        val filters = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { add(statusBox); add(kindBox); add(outdatedOnly) }
        statusBox.addActionListener { refresh() }
        kindBox.addActionListener { refresh() }
        outdatedOnly.addActionListener { refresh() }

        list.cellRenderer = object : ColoredListCellRenderer<Comment>() {
            override fun customizeCellRenderer(l: JList<out Comment>, c: Comment, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = c.kind.icon
                val gray = SimpleTextAttributes.GRAYED_ATTRIBUTES
                if (c.deleted) append("[deleted] ", SimpleTextAttributes.ERROR_ATTRIBUTES)
                if (c.outdated) append("[outdated] ", SimpleTextAttributes.ERROR_ATTRIBUTES)
                if (c.status != CommentStatus.OPEN) append("[${c.status.label}] ", gray)
                val a = c.anchor
                val where = when (a.scope) {
                    AnchorScope.REVIEW -> "Review"
                    AnchorScope.FILE -> a.path!!
                    AnchorScope.LINES -> "${a.path}:${a.startLine}" + if (a.endLine != a.startLine) "-${a.endLine}" else ""
                }
                append(where, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, c.kind.color))
                append("  " + c.body.trim().lineSequence().first().take(120), if (c.status == CommentStatus.OPEN && !c.deleted) SimpleTextAttributes.REGULAR_ATTRIBUTES else gray)
                if (c.rule != null) append("  · rule", gray)
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

    fun refresh() {
        val a = service.active
        val sel = list.selectedValue?.id
        model.clear()
        if (a != null) {
            val status = statusBox.selectedItem as StatusFilter
            val kind = kindBox.selectedItem as CommentKind?
            val all = a.store.comments(includeDeleted = true)
            val shown = all.filter { c ->
                (kind == null || c.kind == kind) &&
                    (!outdatedOnly.isSelected || c.outdated) &&
                    when (status) {
                        StatusFilter.OPEN -> !c.deleted && c.status == CommentStatus.OPEN
                        StatusFilter.CLOSED -> !c.deleted && c.status != CommentStatus.OPEN
                        StatusFilter.ALL -> !c.deleted
                        StatusFilter.DELETED -> c.deleted
                    }
            }.sortedWith(compareBy({ it.anchor.scope != AnchorScope.REVIEW }, { it.anchor.path ?: "" }, { it.anchor.startLine ?: 0 }, { it.createdAt }))
            shown.forEach(model::addElement)
            onCountChanged(all.count { !it.deleted && it.status == CommentStatus.OPEN })
            if (sel != null) shown.indexOfFirst { it.id == sel }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
        } else {
            onCountChanged(0)
        }
    }

    private fun selected(): List<Comment> = list.selectedValuesList

    override fun uiDataSnapshot(sink: DataSink) {
        sink[ReviewDataKeys.SELECTED_COMMENTS] = selected()
    }

    fun navigate(c: Comment) {
        val a = service.active ?: return
        if (c.anchor.scope == AnchorScope.REVIEW) return edit(c)
        val file = a.fileOf(c) ?: return edit(c)
        val scroll = if (c.anchor.scope == AnchorScope.LINES && !c.outdated) {
            (if (c.anchor.side == Side.BASE) DiffSide.LEFT else DiffSide.RIGHT) to (c.anchor.startLine!! - 1)
        } else null
        ReviewDiff.open(project, file, scroll)
    }

    private fun edit(c: Comment) {
        val a = service.active ?: return
        val dlg = CommentDialog(project, "Edit comment", c)
        if (dlg.showAndGet()) save { a.store.updateComment(c.id) { it.copy(kind = dlg.kind, body = dlg.text, rule = dlg.rule) } }
    }

    private fun save(block: () -> Unit) = try {
        block()
    } catch (e: IOException) {
        service.notify("Could not save: ${e.message}", com.intellij.notification.NotificationType.ERROR)
    }

    private fun action(text: String, enabled: (List<Comment>) -> Boolean, run: (Comment) -> Unit): AnAction = object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = selected().let { it.isNotEmpty() && enabled(it) } }
        override fun actionPerformed(e: AnActionEvent) { selected().forEach(run) }
    }

    private fun popupGroup(): DefaultActionGroup {
        val a = { service.active!! }
        return DefaultActionGroup(
            action("Go to Code", { it.size == 1 && !it[0].deleted }) { navigate(it) },
            action("Edit…", { it.size == 1 && !it[0].deleted }) { edit(it) },
            action("Resolve", { l -> l.any { it.status == CommentStatus.OPEN && !it.deleted } }) { c -> if (c.status == CommentStatus.OPEN) save { a().store.updateComment(c.id) { it.copy(status = CommentStatus.RESOLVED) } } },
            action("Won't Fix", { l -> l.any { it.status == CommentStatus.OPEN && !it.deleted } }) { c -> if (c.status == CommentStatus.OPEN) save { a().store.updateComment(c.id) { it.copy(status = CommentStatus.WONT_FIX) } } },
            action("Reopen", { l -> l.any { it.status != CommentStatus.OPEN && !it.deleted } }) { c -> if (c.status != CommentStatus.OPEN) save { a().store.updateComment(c.id) { it.copy(status = CommentStatus.OPEN) } } },
            action("Copy as Agent Prompt", { l -> l.none { it.deleted } }) { c ->
                CopyPasteManager.getInstance().setContents(StringSelection(AgentPrompts.forComment(a().store.review(), c)))
            },
            action("Delete", { l -> l.any { !it.deleted } }) { c -> if (!c.deleted) save { a().store.deleteComment(c.id) } },
            action("Restore", { l -> l.any { it.deleted } }) { c -> if (c.deleted) save { a().store.restoreComment(c.id) } },
        )
    }

    override fun dispose() {}
}
