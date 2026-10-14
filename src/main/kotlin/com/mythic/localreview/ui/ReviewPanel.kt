package com.mythic.localreview.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.mythic.localreview.core.Comment
import com.mythic.localreview.core.CommentStatus
import com.mythic.localreview.core.FileChange
import com.mythic.localreview.ui.diff.ReviewDiff
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.JPanel

object ReviewDataKeys {
    val SELECTED_FILES: DataKey<List<FileChange>> = DataKey.create("LocalReview.SelectedFiles")
    val SELECTED_COMMENTS: DataKey<List<Comment>> = DataKey.create("LocalReview.SelectedComments")
}

/** The tool window content: toolbar, status header, and Changes / Comments tabs (or an empty state). */
class ReviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, UiDataProvider {
    private val service = ReviewService.getInstance(project)
    private val status = JBLabel().apply { border = JBUI.Borders.empty(3, 8) }
    private val changesPanel = ChangesTreePanel(project, service) { ReviewDiff.open(project, it) }
    private val commentsPanel = CommentsPanel(project, service)
    private val tabs = JBTabbedPane()
    private val cards = JPanel(CardLayout())

    init {
        val group = ActionManager.getInstance().getAction("LocalReview.Toolbar") as DefaultActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("LocalReviewToolbar", group, true)
        toolbar.targetComponent = this

        tabs.addTab("Changes", changesPanel)
        tabs.addTab("Comments", commentsPanel)
        commentsPanel.onCountChanged = { n -> tabs.setTitleAt(1, if (n > 0) "Comments ($n open)" else "Comments") }

        val empty = JPanel(FlowLayout(FlowLayout.CENTER, 8, 40)).apply {
            add(JBLabel("No review open."))
            add(ActionLink("New review…") { invoke("LocalReview.NewReview") })
            add(ActionLink("Open review…") { invoke("LocalReview.OpenReview") })
        }
        cards.add(empty, "empty")
        cards.add(tabs, "review")

        val north = JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.NORTH); add(status, BorderLayout.SOUTH) }
        add(north, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        com.intellij.openapi.util.Disposer.register(this, changesPanel)
        com.intellij.openapi.util.Disposer.register(this, commentsPanel)
        service.addListener(this) { refreshHeader() }
        refreshHeader()
    }

    private fun invoke(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId)
        ActionManager.getInstance().tryToExecute(action, null, this, ActionPlaces.TOOLWINDOW_CONTENT, true)
    }

    private fun refreshHeader() {
        val a = service.active
        (cards.layout as CardLayout).show(cards, if (a == null) "empty" else "review")
        if (a == null) {
            status.text = " "
            return
        }
        val r = a.store.review()
        val rev = r.current
        val viewed = a.changes.count { a.isViewed(it) }
        val open = a.store.comments().count { it.status == CommentStatus.OPEN }
        val warn = a.store.commitError
        val saved = if (warn == null) "<span style='color:#59a869'>✓ saved</span>"
        else "<span style='color:#d9a343'>✓ saved to disk · git history: ${warn.take(80).replace("<", "&lt;")}</span>"
        status.text = "<html><b>${r.title.replace("<", "&lt;")}</b> &nbsp; <span style='color:gray'>${r.baseRef} (${rev.effectiveBaseSha.take(7)}) → ${r.headRef} (${rev.headSha.take(7)}) · revision ${rev.n}</span>" +
            "<br>$viewed/${a.changes.size} files viewed · $open open comments · $saved</html>"
        status.foreground = JBColor.foreground()
    }

    override fun uiDataSnapshot(sink: DataSink) {}

    override fun dispose() {}
}
