package com.mythic.localreview.ui.comments

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mythic.localreview.core.Comment
import com.mythic.localreview.core.CommentKind
import com.mythic.localreview.core.CommentStatus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

val CommentKind.color: Color
    get() = when (this) {
        CommentKind.CHANGE_REQUEST -> JBColor(Color(0xC7, 0x22, 0x22), Color(0xE0, 0x6C, 0x6C))
        CommentKind.QUESTION -> JBColor(Color(0x1F, 0x6F, 0xD0), Color(0x5C, 0xA5, 0xF5))
        CommentKind.NOTE -> JBColor(Color(0x6B, 0x6B, 0x6B), Color(0xA0, 0xA0, 0xA0))
    }

val CommentKind.icon
    get() = when (this) {
        CommentKind.CHANGE_REQUEST -> AllIcons.General.Warning
        CommentKind.QUESTION -> AllIcons.General.ContextHelp
        CommentKind.NOTE -> AllIcons.General.Information
    }

/** Maximum content width of inline comment widgets (px, unscaled); they shrink to fit narrow diff panes. */
const val INLINE_WIDTH = 720

/** Inline widgets whose width follows the editor they are embedded in. */
interface WidthAdjustable {
    var contentWidth: Int
}

/** The editable fields of a comment: kind, text and optional AGENTS.md rule candidate. */
class CommentForm(initialKind: CommentKind, initialBody: String, initialRule: String?) : JPanel(BorderLayout()) {
    private val kindButtons = CommentKind.entries.associateWith { JRadioButton(it.label) }
    val bodyArea = JBTextArea(initialBody, 5, 60).apply { lineWrap = true; wrapStyleWord = true; font = UIUtil.getLabelFont() }
    private val ruleCheck = JBCheckBox("Recurring pattern: propose an AGENTS.md rule", initialRule != null)
    private val ruleText = JBTextField(initialRule ?: "").apply {
        emptyText.text = "Rule for agents, e.g. \"Never catch Exception without logging it\""
        isEnabled = initialRule != null
    }

    init {
        val group = ButtonGroup()
        val kindRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        for ((k, b) in kindButtons) {
            group.add(b)
            kindRow.add(b)
            b.isSelected = k == initialKind
            b.foreground = k.color
        }
        ruleCheck.addActionListener { ruleText.isEnabled = ruleCheck.isSelected; if (ruleCheck.isSelected) ruleText.requestFocusInWindow() }
        val form = FormBuilder.createFormBuilder()
            .addComponent(kindRow)
            .addComponent(JBScrollPane(bodyArea))
            .addComponent(ruleCheck)
            .addComponent(ruleText)
            .panel
        form.isOpaque = false
        isOpaque = false
        add(form, BorderLayout.CENTER)
    }

    fun kind(): CommentKind = kindButtons.entries.first { it.value.isSelected }.key
    fun text(): String = bodyArea.text.trim()
    fun rule(): String? = if (ruleCheck.isSelected) ruleText.text.trim().ifEmpty { null } else null

    /** Error message if the form cannot be saved, else null. */
    fun validateInput(): String? = when {
        text().isEmpty() -> "Write something first."
        ruleCheck.isSelected && ruleText.text.isBlank() -> "Enter the rule text or untick the rule box."
        else -> null
    }
}

/** A [CommentForm] with Save/Cancel buttons for use inside editor inlays. Ctrl+Enter saves, Esc cancels. */
class InlineComposer(
    initial: Comment?,
    defaultKind: CommentKind = CommentKind.CHANGE_REQUEST,
    private val onSave: (CommentKind, String, String?) -> String?,
    private val onCancel: () -> Unit,
) : JPanel(BorderLayout()), WidthAdjustable {
    val form = CommentForm(initial?.kind ?: defaultKind, initial?.body ?: "", initial?.rule)
    private val error = JBLabel().apply { foreground = JBColor.RED; isVisible = false }

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(6))
        background = UIUtil.getPanelBackground()
        val save = JButton(if (initial == null) "Add comment" else "Save")
        val cancel = JButton("Cancel")
        save.addActionListener { submit() }
        cancel.addActionListener { onCancel() }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(save); add(cancel); add(JBLabel("Ctrl+Enter to save", UIUtil.ComponentStyle.SMALL).apply { foreground = JBColor.GRAY })
        }
        val south = JPanel(BorderLayout()).apply { isOpaque = false; add(error, BorderLayout.NORTH); add(buttons, BorderLayout.CENTER) }
        add(form, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)
        val area = form.bodyArea
        area.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "lr.save")
        area.actionMap.put("lr.save", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) = submit() })
        area.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "lr.cancel")
        area.actionMap.put("lr.cancel", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) = onCancel() })

    }

    private fun submit() {
        val problem = form.validateInput() ?: onSave(form.kind(), form.text(), form.rule())
        error.isVisible = problem != null
        error.text = problem ?: ""
        revalidate()
        SwingUtilities.getAncestorOfClass(JComponent::class.java, this)?.revalidate()
    }

    fun focusText() { form.bodyArea.requestFocusInWindow() }

    override var contentWidth: Int = JBUI.scale(INLINE_WIDTH)

    override fun getPreferredSize(): Dimension = Dimension(contentWidth, super.getPreferredSize().height)
}

/** Callbacks a [CommentCard] needs from its host. */
interface CommentCardActions {
    fun edit(c: Comment)
    fun setStatus(c: Comment, status: CommentStatus)
    fun delete(c: Comment)
    fun copyPrompt(c: Comment)
}

/** Read-only display of one comment with Edit / Resolve / Delete buttons. */
class CommentCard(val comment: Comment, actions: CommentCardActions) : JPanel(BorderLayout()), WidthAdjustable {
    override var contentWidth: Int = JBUI.scale(INLINE_WIDTH)
    private val bodyArea = JBTextArea(comment.body)

    init {
        val faded = comment.status != CommentStatus.OPEN
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(comment.kind.color, 0, 3, 0, 0),
            JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 1), JBUI.Borders.empty(5, 8)),
        )
        background = UIUtil.getPanelBackground()

        val title = JBLabel(comment.kind.label, comment.kind.icon, JBLabel.LEFT).apply {
            foreground = comment.kind.color
            font = font.deriveFont(java.awt.Font.BOLD)
        }
        val meta = StringBuilder()
        if (comment.status != CommentStatus.OPEN) meta.append("  ·  ").append(comment.status.label)
        if (comment.rule != null) meta.append("  ·  rule candidate")
        if (comment.anchor.startLine != null && comment.anchor.endLine != comment.anchor.startLine) {
            meta.append("  ·  lines ${comment.anchor.startLine}–${comment.anchor.endLine}")
        }
        val metaLabel = JBLabel(meta.toString()).apply { foreground = JBColor.GRAY }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(title); add(metaLabel) }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            add(link("Edit") { actions.edit(comment) })
            if (comment.status == CommentStatus.OPEN) {
                add(link("Resolve") { actions.setStatus(comment, CommentStatus.RESOLVED) })
            } else {
                add(link("Reopen") { actions.setStatus(comment, CommentStatus.OPEN) })
            }
            add(link("Copy prompt") { actions.copyPrompt(comment) })
            add(link("Delete") { actions.delete(comment) })
        }
        val header = JPanel(BorderLayout()).apply { isOpaque = false; add(left, BorderLayout.WEST); add(buttons, BorderLayout.EAST) }

        bodyArea.apply {
            font = UIUtil.getLabelFont()
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.emptyTop(3)
            foreground = if (faded) JBColor.GRAY else UIUtil.getLabelForeground()
        }
        val center = JPanel(BorderLayout()).apply { isOpaque = false; add(bodyArea, BorderLayout.CENTER) }
        if (comment.rule != null) {
            center.add(JBLabel("Rule: ${comment.rule}").apply { foreground = JBColor.GRAY; border = JBUI.Borders.emptyTop(3) }, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
    }

    private fun link(text: String, run: () -> Unit) = com.intellij.ui.components.ActionLink(text) { run() }.apply {
        font = font.deriveFont(font.size2D - 1f)
    }

    override fun getPreferredSize(): Dimension {
        val w = contentWidth
        // A wrapping text area computes its height from its current width, so give it the target width first.
        bodyArea.setSize(w - insets.left - insets.right, Short.MAX_VALUE.toInt())
        return Dimension(w, super.getPreferredSize().height)
    }
}

/** Modal dialog for file-level and review-level comments (and editing them from lists). */
class CommentDialog(
    project: com.intellij.openapi.project.Project,
    title: String,
    private val initial: Comment?,
    defaultKind: CommentKind = CommentKind.NOTE,
) : DialogWrapper(project) {
    private val form = CommentForm(initial?.kind ?: defaultKind, initial?.body ?: "", initial?.rule)

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent = form
    override fun getPreferredFocusedComponent(): JComponent = form.bodyArea

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        form.validateInput()?.let { com.intellij.openapi.ui.ValidationInfo(it, form.bodyArea) }

    val kind get() = form.kind()
    val text get() = form.text()
    val rule get() = form.rule()
}
