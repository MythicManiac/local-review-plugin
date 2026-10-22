package com.mythic.localreview.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.mythic.localreview.core.GitException
import com.mythic.localreview.store.GroupInfo
import com.mythic.localreview.store.ReviewGroups
import com.mythic.localreview.core.SshKeys
import com.mythic.localreview.core.SyncResult
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.ScrollPaneFactory
import javax.swing.DefaultListModel
import javax.swing.JList
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.io.path.name

class NewReviewDialog(private val project: Project, private val service: ReviewService) : DialogWrapper(project) {
    private val roots = service.gitRoots()
    private val rootBox = ComboBox(roots.map { it.toString() }.toTypedArray())
    private val titleField = JBTextField()
    private val baseBox = ComboBox<String>().apply { isEditable = true }
    private val headBox = ComboBox<String>().apply { isEditable = true }
    private val mergeBase = JBCheckBox("Compare from the merge-base (like a pull request; ignores later commits on the base)", true)
    private val groupBox = ComboBox<String>()
    private var titleEdited = false

    val root: Path get() = Path.of(rootBox.selectedItem as String)
    val reviewTitle: String get() = titleField.text.trim()
    val baseRef: String get() = (baseBox.editor.item as String).trim()
    val headRef: String get() = (headBox.editor.item as String).trim()
    val useMergeBase: Boolean get() = mergeBase.isSelected
    val groupName: String get() = groupBox.selectedItem as String

    init {
        title = "New Review"
        rootBox.renderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(l: JList<out String>, v: String?, i: Int, s: Boolean, f: Boolean) {
                val p = Path.of(v ?: return)
                append(p.name.ifEmpty { p.toString() }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val where = describeParent(p)
                if (where.isNotEmpty()) append("   $where", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = p.toString()
            }
        }
        rootBox.addActionListener { loadRefs(); loadGroups(null) }
        loadRefs()
        loadGroups(null)
        titleField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { if (titleField.hasFocus()) titleEdited = true }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { if (titleField.hasFocus()) titleEdited = true }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {}
        })
        val retitle = java.awt.event.ActionListener { if (!titleEdited) titleField.text = "$headRef vs $baseRef" }
        baseBox.addActionListener(retitle)
        headBox.addActionListener(retitle)
        retitle.actionPerformed(null)
        init()
    }

    /** What identifies a repository besides its folder name: relative to this project, else where it is, with the home folder as `~`. */
    private fun describeParent(p: Path): String {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        val abs = p.toAbsolutePath().normalize()
        if (base != null && abs == base) return "(this project)"
        if (base != null && abs.startsWith(base)) return "in " + base.relativize(abs).parent?.toString().orEmpty().ifEmpty { "(this project)" }
        val parent = abs.parent?.toString() ?: return ""
        val home = System.getProperty("user.home")
        return if (home != null && parent.startsWith(home)) "~" + parent.removePrefix(home) else parent
    }

    /** Fills the group list; preselects [select], else the group last used for this project, else "default". */
    private fun loadGroups(select: String?) {
        val groups = service.groups()
        if (groups.list().isEmpty()) groups.ensureDefault()
        val names = groups.list().map { it.name }
        groupBox.model = DefaultComboBoxModel(names.toTypedArray())
        val preferred = select ?: groups.groupFor(service.repoRefOf(root)) ?: ReviewGroups.DEFAULT_GROUP
        groupBox.selectedItem = names.firstOrNull { it == preferred } ?: names.first()
    }

    private fun newGroup() {
        val d = CreateGroupDialog(project)
        if (d.showAndGet() && createGroupModal(project, service, d.groupName, d.remoteUrl, d.sshKey)) loadGroups(d.groupName)
    }

    private fun loadRefs() {
        val git = service.gitFor(root)
        val refs = try { git.listRefs() } catch (e: GitException) { emptyList() }
        val current = try { git.currentBranch() } catch (e: GitException) { null }
        baseBox.model = DefaultComboBoxModel(refs.toTypedArray())
        headBox.model = DefaultComboBoxModel(refs.toTypedArray())
        val head = current ?: "HEAD"
        val base = listOf("origin/main", "origin/master", "main", "master").firstOrNull { it in refs && it != head } ?: refs.firstOrNull { it != head } ?: "HEAD~1"
        baseBox.selectedItem = base
        headBox.selectedItem = head
    }

    override fun createCenterPanel(): JComponent {
        val b = FormBuilder.createFormBuilder()
        if (roots.size > 1) {
            // BorderLayout makes the box use all the width; a bare combo box keeps the width of its longest item's prefix.
            b.addLabeledComponent("Repository:", JPanel(java.awt.BorderLayout()).apply { add(rootBox, java.awt.BorderLayout.CENTER) })
        }
        val groupRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
            add(groupBox)
            add(com.intellij.ui.components.ActionLink("New group…") { newGroup() })
        }
        b.addLabeledComponent("Review group:", groupRow)
        b.addLabeledComponent("Title:", titleField)
            .addLabeledComponent("Base (compare against):", baseBox)
            .addLabeledComponent("Head (changes to review):", headBox)
            .addComponent(mergeBase)
        return b.panel.apply { preferredSize = Dimension(JBUI.scale(520), preferredSize.height) }
    }

    override fun doValidate(): ValidationInfo? {
        if (reviewTitle.isEmpty()) return ValidationInfo("Enter a title", titleField)
        if (groupBox.selectedItem == null) return ValidationInfo("Choose a review group", groupBox)
        val git = service.gitFor(root)
        try { git.resolveCommit(baseRef) } catch (e: GitException) { return ValidationInfo("Cannot resolve base '$baseRef'", baseBox) }
        try { git.resolveCommit(headRef) } catch (e: GitException) { return ValidationInfo("Cannot resolve head '$headRef'", headBox) }
        if (baseRef == headRef) return ValidationInfo("Base and head are the same", headBox)
        return null
    }
}

class ExportDialog(project: Project, defaultDir: Path, hasRules: Boolean) : DialogWrapper(project) {
    private val dir = TextFieldWithBrowseButton().apply {
        text = defaultDir.toString()
        addBrowseFolderListener(project, com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Export Folder"))
    }
    private val md = JBCheckBox("Markdown report (report.md)", true)
    private val json = JBCheckBox("Machine-readable JSON (report.json)", true)
    private val prompt = JBCheckBox("Agent prompt (agent-prompt.md)" + if (hasRules) " incl. AGENTS.md instruction" else "", true)

    val outputDir: Path get() = Path.of(dir.text.trim())
    val wantMarkdown get() = md.isSelected
    val wantJson get() = json.isSelected
    val wantPrompt get() = prompt.isSelected

    init {
        title = "Export Review"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Export to:", dir)
        .addComponent(md).addComponent(json).addComponent(prompt)
        .panel.apply { preferredSize = Dimension(JBUI.scale(520), preferredSize.height) }

    override fun doValidate(): ValidationInfo? = when {
        dir.text.isBlank() -> ValidationInfo("Choose a folder", dir)
        !md.isSelected && !json.isSelected && !prompt.isSelected -> ValidationInfo("Pick at least one format")
        else -> null
    }
}

/** Shows `existing` vs an editable `proposed` AGENTS.md; OK writes whatever is in the right-hand side. */
class AgentsMdDialog(private val project: Project, private val fileName: String, existing: String, proposed: String) : DialogWrapper(project) {
    private val document = EditorFactory.getInstance().createDocument(proposed)
    private val panelDisposable = Disposer.newDisposable("AgentsMdDialog")
    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, panelDisposable, null)

    val finalText: String get() = document.text

    init {
        title = "Proposed update to $fileName"
        setOKButtonText("Write $fileName")
        val f = DiffContentFactory.getInstance()
        val left = f.create(project, existing, PlainTextFileType.INSTANCE)
        val right = f.create(project, document)
        diffPanel.setRequest(SimpleDiffRequest("Proposed update to $fileName", left, right, "Current", "Proposed (editable)"))
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(diffPanel.component, BorderLayout.CENTER)
        preferredSize = Dimension(JBUI.scale(1080), JBUI.scale(520))
    }

    override fun dispose() {
        Disposer.dispose(panelDisposable)
        super.dispose()
    }
}


/** Runs [block] under a modal progress dialog; returns its result or the failure. */
fun <T> runModal(project: Project, title: String, block: () -> T): Result<T> {
    var result: Result<T>? = null
    ProgressManager.getInstance().runProcessWithProgressSynchronously({
        result = runCatching(block)
    }, title, false, project)
    return result!!
}

/** Creates a group (attaching/downloading its remote when given), reporting errors in a dialog. */
fun createGroupModal(project: Project, service: ReviewService, name: String, remoteUrl: String?, sshKey: String?): Boolean {
    val r = runModal(project, "Creating review group") { service.groups().create(name, remoteUrl, sshKey) }
    r.exceptionOrNull()?.let {
        Messages.showErrorDialog(project, it.message ?: it.toString(), "Could Not Create Group")
        return false
    }
    return true
}

fun describe(r: SyncResult, group: String): String = when (r) {
    SyncResult.NoRemote -> "Group '$group' has no remote. Set one in Manage Review Groups."
    is SyncResult.Ok -> buildString {
        append(
            when {
                r.pulled && r.pushed -> "Synced group '$group': received and sent changes."
                r.pulled -> "Synced group '$group': received changes from the remote."
                r.pushed -> "Synced group '$group': sent your changes to the remote."
                else -> "Group '$group' is up to date."
            },
        )
        if (r.conflicts.isNotEmpty()) append(" ${r.conflicts.size} file(s) were edited on two devices; both versions were kept (*.conflict-*).")
    }
    is SyncResult.Failed -> "Sync of group '$group' failed: ${r.message}"
}

class CreateGroupDialog(project: Project) : DialogWrapper(project) {
    private val name = JBTextField()
    private val remote = JBTextField().apply {
        emptyText.text = "Optional, e.g. git@github.com:me/my-reviews.git"
    }

    private val key = SshKeyPicker(project)

    val groupName: String get() = name.text.trim()
    val remoteUrl: String? get() = remote.text.trim().ifEmpty { null }
    val sshKey: String? get() = key.selected()

    init {
        title = "New Review Group"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", name)
        .addLabeledComponent("Remote (optional):", remote)
        .addLabeledComponent("SSH key for the remote:", key)
        .addComponent(
            com.intellij.ui.components.JBLabel(
                "<html>A group is one git repository holding the reviews of any number of projects.<br>" +
                    "Give it a remote to sync it between your devices. If the remote already contains<br>" +
                    "a group made on another device, it is downloaded. Use separate groups to keep<br>" +
                    "unrelated work (e.g. different organisations) isolated.</html>",
            ).apply { foreground = com.intellij.ui.JBColor.GRAY },
        )
        .panel.apply { preferredSize = Dimension(JBUI.scale(560), preferredSize.height) }

    override fun getPreferredFocusedComponent(): JComponent = name

    override fun doValidate(): ValidationInfo? = when {
        !ReviewGroups.validName(groupName) -> ValidationInfo("Letters, digits, space, '.', '_' or '-' (max 64, must not start with '.')", name)
        else -> null
    }
}

/** Lists review groups with their remote and sync state; create groups, set the remote, toggle auto-sync, sync now. */
class ManageGroupsDialog(private val project: Project, private val service: ReviewService) : DialogWrapper(project) {
    private val sync = GroupSyncService.getInstance()
    private val model = DefaultListModel<GroupInfo>()
    private val list = JBList(model)
    private val auto = JBCheckBox("Auto-sync this group")
    private val setRemoteBtn = javax.swing.JButton("Set Remote…")
    private val sshKeyBtn = javax.swing.JButton("SSH Key…")
    private val syncBtn = javax.swing.JButton("Sync Now")

    init {
        title = "Review Groups"
        list.cellRenderer = object : ColoredListCellRenderer<GroupInfo>() {
            override fun customizeCellRenderer(l: JList<out GroupInfo>, g: GroupInfo, i: Int, sel: Boolean, focus: Boolean) {
                append(g.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("   " + (g.remoteUrl ?: "local only"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                g.sshKey?.let { append("   key: ${it.substringAfterLast('/')}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                val st = sync.status(g.name)
                if (g.remoteUrl != null && st != null) {
                    when (val r = st.result) {
                        is SyncResult.Ok -> append("   synced ${ago(st.at)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        is SyncResult.Failed -> append("   sync failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        else -> {}
                    }
                }
            }
        }
        list.addListSelectionListener { updateButtons() }
        auto.addActionListener {
            val g = list.selectedValue ?: return@addActionListener
            service.groups().setAutoSync(g.name, auto.isSelected)
            refresh(g.name)
        }
        setRemoteBtn.addActionListener { setRemote() }
        sshKeyBtn.addActionListener { setSshKey() }
        syncBtn.addActionListener { syncNow() }
        refresh(null)
        init()
    }

    private fun refresh(select: String?) {
        val keep = select ?: list.selectedValue?.name
        model.clear()
        service.groups().list().forEach(model::addElement)
        val idx = (0 until model.size()).firstOrNull { model[it].name == keep } ?: if (model.size() > 0) 0 else -1
        if (idx >= 0) list.selectedIndex = idx
        updateButtons()
        service.refreshGroup()
    }

    private fun updateButtons() {
        val g = list.selectedValue
        setRemoteBtn.isEnabled = g != null
        sshKeyBtn.isEnabled = g != null
        syncBtn.isEnabled = g?.remoteUrl != null
        auto.isEnabled = g?.remoteUrl != null
        auto.isSelected = g?.autoSync ?: false
    }

    private fun newGroup() {
        val d = CreateGroupDialog(project)
        if (d.showAndGet() && createGroupModal(project, service, d.groupName, d.remoteUrl, d.sshKey)) refresh(d.groupName)
    }

    private fun setRemote() {
        val g = list.selectedValue ?: return
        val url = Messages.showInputDialog(
            project, "Git remote URL for group '${g.name}' (leave empty to remove the remote):", "Set Remote", null, g.remoteUrl ?: "", null,
        ) ?: return
        val r = runModal(project, "Updating remote") { service.groups().setRemote(g.name, url) }
        r.exceptionOrNull()?.let { Messages.showErrorDialog(project, it.message ?: it.toString(), "Local Diff Review"); return }
        refresh(g.name)
        if (url.isNotBlank()) syncNow()
    }

    private fun setSshKey() {
        val g = list.selectedValue ?: return
        val d = SshKeyDialog(project, g)
        if (!d.showAndGet()) return
        val r = runModal(project, "Updating SSH key") { service.groups().setSshKey(g.name, d.key) }
        r.exceptionOrNull()?.let { Messages.showErrorDialog(project, it.message ?: it.toString(), "Local Diff Review"); return }
        refresh(g.name)
        if (g.remoteUrl != null) syncNow() // the usual reason to change the key: make the remote reachable
    }

    private fun syncNow() {
        val g = list.selectedValue ?: return
        val r = runModal(project, "Syncing review group '${g.name}'") { sync.syncNow(g.name) }.getOrElse { SyncResult.Failed(it.message ?: it.toString()) }
        refresh(g.name)
        val text = describe(r, g.name)
        if (r is SyncResult.Failed) Messages.showErrorDialog(project, text, "Local Diff Review") else Messages.showInfoMessage(project, text, "Local Diff Review")
    }

    override fun createActions() = arrayOf(okAction.also { it.putValue(javax.swing.Action.NAME, "Close") })

    override fun createCenterPanel(): JComponent {
        val buttons = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            val newBtn = javax.swing.JButton("New Group…").apply { addActionListener { newGroup() } }
            for (c in listOf<JComponent>(newBtn, setRemoteBtn, sshKeyBtn, syncBtn, auto)) {
                c.alignmentX = 0f
                add(c)
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            }
        }
        return JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
            preferredSize = Dimension(JBUI.scale(680), JBUI.scale(280))
        }
    }
}

fun ago(at: java.time.Instant): String {
    val s = java.time.Duration.between(at, java.time.Instant.now()).seconds
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${(s / 60).coerceAtLeast(1)} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}


/**
 * Chooses the SSH private key a group uses to reach its remote: "default" (use the user's own git/ssh configuration),
 * a key found in `~/.ssh`, or any file via Browse.
 */
class SshKeyPicker(private val project: Project?, initial: String? = null) : JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)) {
    private val combo = ComboBox<String>()

    init {
        val items = LinkedHashSet<String>()
        items += ""
        items += SshKeys.detect()
        initial?.let { items += it }
        combo.model = DefaultComboBoxModel(items.toTypedArray())
        combo.selectedItem = initial ?: ""
        combo.renderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(l: JList<out String>, v: String?, i: Int, sel: Boolean, focus: Boolean) {
                if (v.isNullOrEmpty()) {
                    append("Default (use my git/ssh configuration)")
                } else {
                    append(v.substringAfterLast('/'))
                    append("   " + v.substringBeforeLast('/', ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
        val browse = javax.swing.JButton("Browse…").apply { addActionListener { browse() } }
        add(combo)
        add(browse)
    }

    private fun browse() {
        val ssh = java.nio.file.Path.of(System.getProperty("user.home"), ".ssh")
        val start = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(ssh)
        val d = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleFile()
            .withTitle("Select SSH Private Key").withShowHiddenFiles(true)
        val f = com.intellij.openapi.fileChooser.FileChooser.chooseFile(d, project, start) ?: return
        val path = f.path
        if (!(0 until combo.itemCount).any { combo.getItemAt(it) == path }) combo.addItem(path)
        combo.selectedItem = path
    }

    /** The chosen private key path, or null for "default". */
    fun selected(): String? = (combo.selectedItem as String?)?.ifEmpty { null }
}

class SshKeyDialog(project: Project, group: GroupInfo) : DialogWrapper(project) {
    private val picker = SshKeyPicker(project, group.sshKey)
    private val groupName = group.name

    val key: String? get() = picker.selected()

    init {
        title = "SSH Key for Group '$groupName'"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("SSH key:", picker)
        .addComponent(
            com.intellij.ui.components.JBLabel(
                "<html><body style='width: ${JBUI.scale(460)}px'>Used as <code>ssh -i &lt;key&gt;</code> only for this group's remote. It is saved in the " +
                    "group's local git config on this machine (not synced), like a <code>core.sshCommand</code> include. Choose \"Default\" to rely on " +
                    "your own git/ssh configuration. Keys that need a passphrase must be loaded in ssh-agent.</body></html>",
            ).apply { foreground = com.intellij.ui.JBColor.GRAY },
        )
        .panel.apply { preferredSize = Dimension(JBUI.scale(560), preferredSize.height) }
}
