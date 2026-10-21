package com.mythic.localreview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.ChangedFile
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Directory node; checkbox state is derived from its descendants. */
class DirNode(val label: String) : CheckedTreeNode(label)

class FileNode(val change: ChangedFile) : CheckedTreeNode(change)

/**
 * The review's changed files as a directory tree (file-structure changes are visible as A/D/R status + rename arrows), with a "reviewed" checkbox
 * per file/directory, progress by changed lines and comment counts. Progress is computed in the background; until it is there the tree says so,
 * and when the code is not available here it says "unverified" instead of showing marks as valid or stale.
 */
class ChangesTreePanel(
    private val project: Project,
    private val service: ReviewService,
    private val onOpen: (ChangedFile) -> Unit,
) : JPanel(BorderLayout()), Disposable, com.intellij.openapi.actionSystem.UiDataProvider {

    override fun uiDataSnapshot(sink: com.intellij.openapi.actionSystem.DataSink) {
        sink[ReviewDataKeys.SELECTED_FILES] = selectedFiles()
    }

    private val root = CheckedTreeNode(null)
    private var rebuilding = false

    private val renderer = object : CheckboxTree.CheckboxTreeCellRenderer(true, true) {
        override fun customizeRenderer(
            tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val r = textRenderer
            when (value) {
                is DirNode -> {
                    r.icon = AllIcons.Nodes.Folder
                    r.append(value.label)
                }
                is FileNode -> renderFile(r, value.change)
            }
        }
    }

    private val tree = object : CheckboxTree(renderer, root, CheckPolicy(true, true, false, false)) {
        override fun onNodeStateChanged(node: CheckedTreeNode?) {
            if (rebuilding || node == null) return
            applyViewed(node, node.isChecked)
        }
    }

    private val summary = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) openSelected()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
            }
        })
        PopupHandler.installPopupMenu(tree, "LocalReview.ChangesPopup", "LocalReview.ChangesPopupPlace")
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        add(summary, BorderLayout.NORTH)
        service.addListener(this) { rebuild() }
        rebuild()
    }

    val component get() = tree

    fun selectedFiles(): List<ChangedFile> =
        tree.selectionPaths.orEmpty().flatMap { leaves(it.lastPathComponent as DefaultMutableTreeNode) }.map { it.change }.distinctBy { it.path }

    private fun leaves(n: DefaultMutableTreeNode): List<FileNode> =
        if (n is FileNode) listOf(n) else n.children().toList().flatMap { leaves(it as DefaultMutableTreeNode) }

    private fun openSelected() {
        val n = tree.lastSelectedPathComponent as? FileNode ?: return
        onOpen(n.change)
    }

    fun select(path: String) {
        val node = leaves(root).firstOrNull { it.change.path == path } ?: return
        TreeUtil.selectNode(tree, node)
    }

    /** Checking a file or folder marks every file below it: the file mark and a region mark per hunk. Off the UI thread: it reads blobs. */
    private fun applyViewed(node: CheckedTreeNode, viewed: Boolean) {
        val files = leaves(node).map { it.change }
        ApplicationManager.getApplication().executeOnPooledThread { service.setFilesReviewed(files, viewed) }
    }

    // ---- model ------------------------------------------------------------------------------------------------

    fun rebuild() {
        val a = service.active
        rebuilding = true
        try {
            val state = com.intellij.ide.util.treeView.TreeState.createOn(tree, root)
            root.removeAllChildren()
            if (a == null) {
                summary.text = "No review open"
            } else {
                val changes = a.changes.filter { !(ReviewUiState.hideViewed && a.isReviewed(it)) }
                build(changes)
                val viewed = a.changes.count { a.isReviewed(it) }
                val counts = a.changes.groupingBy { it.status }.eachCount()
                val parts = listOfNotNull(
                    counts["added"]?.let { "+$it added" },
                    counts["modified"]?.let { "~$it modified" },
                    counts["deleted"]?.let { "-$it deleted" },
                    counts["renamed"]?.let { "$it renamed" },
                )
                val note = when {
                    !a.verified -> " &nbsp;·&nbsp; <span style='color:#d9a343'>unverified: the code is not on this machine</span>"
                    a.analysis == null -> " &nbsp;·&nbsp; <span style='color:gray'>calculating…</span>"
                    else -> ""
                }
                summary.text = "<html>${if (a.verified) "$viewed / ${a.changes.size} files reviewed" else "${a.changes.size} files"}" +
                    (if (parts.isNotEmpty()) " &nbsp;·&nbsp; <span style='color:gray'>${parts.joinToString(", ")}</span>" else "") + note + "</html>"
            }
            (tree.model as DefaultTreeModel).reload()
            if (!state.isEmpty) state.applyTo(tree, root) else TreeUtil.expandAll(tree)
            if (a != null) syncChecks(root)
            tree.repaint()
        } finally {
            rebuilding = false
        }
    }

    private fun syncChecks(node: DefaultMutableTreeNode): Boolean {
        val a = service.active
        if (node is FileNode) {
            val viewed = a?.isReviewed(node.change) == true
            node.isChecked = viewed
            return viewed
        }
        var all = node.childCount > 0
        for (child in node.children()) all = syncChecks(child as DefaultMutableTreeNode) && all
        if (node is CheckedTreeNode && node !== root) node.isChecked = all
        return all
    }

    private fun build(changes: List<ChangedFile>) {
        val dirs = HashMap<String, DefaultMutableTreeNode>()
        fun dirNode(path: String): DefaultMutableTreeNode {
            if (path.isEmpty()) return root
            return dirs.getOrPut(path) {
                val parentPath = path.substringBeforeLast('/', "")
                val parent = dirNode(parentPath)
                DirNode(path.substringAfterLast('/')).also { parent.add(it) }
            }
        }
        for (c in changes) dirNode(c.path.substringBeforeLast('/', "")).add(FileNode(c))
        sortAndCompact(root)
    }

    /** Dirs first, alphabetical; chains of single-child directories are merged ("src/main/kotlin"). */
    private fun sortAndCompact(node: DefaultMutableTreeNode) {
        val kids = node.children().toList().map { it as DefaultMutableTreeNode }
        node.removeAllChildren()
        val sorted = kids.sortedWith(
            compareBy<DefaultMutableTreeNode>({ it is FileNode }, {
                when (it) { is DirNode -> it.label.lowercase(); is FileNode -> it.change.path.substringAfterLast('/').lowercase(); else -> "" }
            }),
        )
        for (k in sorted) {
            var cur = k
            while (cur is DirNode && cur.childCount == 1 && cur.getChildAt(0) is DirNode) {
                val only = cur.getChildAt(0) as DirNode
                cur.remove(only)
                val merged = DirNode(cur.label + "/" + only.label)
                only.children().toList().forEach { merged.add(it as DefaultMutableTreeNode) }
                cur = merged
            }
            node.add(cur)
            if (cur is DirNode) sortAndCompact(cur)
        }
    }

    // ---- rendering ---------------------------------------------------------------------------------------------

    private fun renderFile(r: ColoredTreeCellRenderer, c: ChangedFile) {
        val a = service.active
        val name = c.path.substringAfterLast('/')
        r.icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
        val viewed = a?.isReviewed(c) == true
        val color = when (c.status) {
            "added" -> FileStatus.ADDED.color
            "deleted" -> FileStatus.DELETED.color
            else -> FileStatus.MODIFIED.color
        }
        val style = if (viewed) SimpleTextAttributes.STYLE_PLAIN else SimpleTextAttributes.STYLE_BOLD
        val base = SimpleTextAttributes(style, if (viewed) null else color)
        r.append(name, if (viewed) SimpleTextAttributes.GRAYED_ATTRIBUTES else base)
        r.append("  ${c.letter}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
        if (c.oldPath != null && (c.status == "renamed" || c.status == "copied")) {
            r.append("  ← ${c.oldPath}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        if (a != null) {
            if (a.isStale(c)) r.append("  changed since reviewed", SimpleTextAttributes.ERROR_ATTRIBUTES)
            else {
                val p = a.progress(c.path)
                if (!viewed && p != null && p.reviewed > 0) r.append("  ${p.reviewed}/${p.changed} lines", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            val open = a.comments().count { it.status == AnnotationStatus.OPEN && a.fileOf(it)?.path == c.path }
            if (open > 0) r.append("  💬 $open", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    override fun dispose() {}
}
