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
    private var titleEdited = false

    val root: Path get() = Path.of(rootBox.selectedItem as String)
    val reviewTitle: String get() = titleField.text.trim()
    val baseRef: String get() = (baseBox.editor.item as String).trim()
    val headRef: String get() = (headBox.editor.item as String).trim()
    val useMergeBase: Boolean get() = mergeBase.isSelected

    init {
        title = "New Local Review"
        rootBox.addActionListener { loadRefs() }
        loadRefs()
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
        if (roots.size > 1) b.addLabeledComponent("Repository:", rootBox)
        b.addLabeledComponent("Title:", titleField)
            .addLabeledComponent("Base (compare against):", baseBox)
            .addLabeledComponent("Head (changes to review):", headBox)
            .addComponent(mergeBase)
        return b.panel.apply { preferredSize = Dimension(JBUI.scale(520), preferredSize.height) }
    }

    override fun doValidate(): ValidationInfo? {
        if (reviewTitle.isEmpty()) return ValidationInfo("Enter a title", titleField)
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
        preferredSize = Dimension(JBUI.scale(900), JBUI.scale(520))
    }

    override fun dispose() {
        Disposer.dispose(panelDisposable)
        super.dispose()
    }
}
