package com.mythic.localreview.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.Disposable
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
import com.mythic.localreview.core.ChangeStatus
import com.mythic.localreview.core.FileChange
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Directory node; checkbox state is derived from its descendants. */
class DirNode(val label: String) : CheckedTreeNode(label)

class FileNode(val change: FileChange) : CheckedTreeNode(change)

/**
 * The review's changed files as a directory tree (file-structure changes are visible as A/D/R status + rename arrows),
 * with a "viewed" checkbox per file/directory, hunk progress and comment counts.
 */
class ChangesTreePanel(
    private val project: Project,
    private val service: ReviewService,
    private val onOpen: (FileChange) -> Unit,
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

    fun selectedFiles(): List<FileChange> =
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

    private fun applyViewed(node: CheckedTreeNode, viewed: Boolean) {
        val a = service.active ?: return
        for (leaf in leaves(node)) {
            val c = leaf.change
            // Hunk fingerprints are only known once a file has been opened; marking by file is enough, hunks follow
            // from the file-level mark when the diff is opened (see DiffReviewController).
            a.store.setFileViewed(c.path, c.fingerprint, viewed)
        }
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
                val changes = a.changes.filter { !(ReviewUiState.hideViewed && a.isViewed(it)) }
                build(changes)
                val viewed = a.changes.count { a.isViewed(it) }
                val counts = a.changes.groupingBy { it.status }.eachCount()
                val parts = listOfNotNull(
                    counts[ChangeStatus.ADDED]?.let { "+$it added" },
                    counts[ChangeStatus.MODIFIED]?.let { "~$it modified" },
                    counts[ChangeStatus.DELETED]?.let { "-$it deleted" },
                    counts[ChangeStatus.RENAMED]?.let { "$it renamed" },
                )
                summary.text = "<html>$viewed / ${a.changes.size} files viewed" +
                    (if (parts.isNotEmpty()) " &nbsp;·&nbsp; <span style='color:gray'>${parts.joinToString(", ")}</span>" else "") + "</html>"
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
            val viewed = a?.isViewed(node.change) == true
            node.isChecked = viewed
            return viewed
        }
        var all = node.childCount > 0
        for (child in node.children()) all = syncChecks(child as DefaultMutableTreeNode) && all
        if (node is CheckedTreeNode && node !== root) node.isChecked = all
        return all
    }

    private fun build(changes: List<FileChange>) {
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

    private fun renderFile(r: ColoredTreeCellRenderer, c: FileChange) {
        val a = service.active
        val name = c.path.substringAfterLast('/')
        r.icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
        val viewed = a?.isViewed(c) == true
        val color = when (c.status) {
            ChangeStatus.ADDED -> FileStatus.ADDED.color
            ChangeStatus.DELETED -> FileStatus.DELETED.color
            ChangeStatus.MODIFIED, ChangeStatus.TYPE_CHANGED -> FileStatus.MODIFIED.color
            ChangeStatus.RENAMED, ChangeStatus.COPIED -> FileStatus.MODIFIED.color
        }
        val style = when {
            viewed -> SimpleTextAttributes.STYLE_PLAIN
            else -> SimpleTextAttributes.STYLE_BOLD
        }
        val base = SimpleTextAttributes(style, if (viewed) null else color)
        r.append(name, if (viewed) SimpleTextAttributes.GRAYED_ATTRIBUTES else base)
        r.append("  ${c.status.letter}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
        if (c.status == ChangeStatus.RENAMED || c.status == ChangeStatus.COPIED) {
            r.append("  ← ${c.oldPath}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        if (a != null) {
            val p = a.store.progress(c.path)
            if (a.isStale(c, p)) r.append("  changed since viewed", SimpleTextAttributes.ERROR_ATTRIBUTES)
            else if (!viewed && p?.hunkCount != null && p.hunkCountFingerprint == c.fingerprint && p.viewedHunks.isNotEmpty()) {
                val done = p.viewedHunks.size.coerceAtMost(p.hunkCount)
                r.append("  $done/${p.hunkCount} hunks", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            val open = a.store.comments().count { it.status == com.mythic.localreview.core.CommentStatus.OPEN && a.fileOf(it)?.path == c.path }
            if (open > 0) r.append("  💬 $open", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    override fun dispose() {}
}
