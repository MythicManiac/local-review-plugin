package com.mythic.localreview.ui.comments

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
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.Annotation
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.Reply
import com.mythic.localreview.format.label
import com.mythic.localreview.ui.RuleInput
import com.mythic.localreview.ui.color
import com.mythic.localreview.ui.icon
import java.awt.BorderLayout
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

/** Maximum content width of inline comment widgets (px, unscaled); they shrink to fit narrow diff panes. */
const val INLINE_WIDTH = 720

/** Inline widgets whose width follows the editor they are embedded in. */
interface WidthAdjustable {
    var contentWidth: Int
}

/** The editable fields of a comment: kind, text, and whether it is a recurring pattern for AGENTS.md, with the rule text if the reviewer wrote one. */
class CommentForm(initialKind: Kind, initialBody: String, initialCandidate: Boolean, initialRuleText: String?) : JPanel(BorderLayout()) {
    private val kindButtons = Kind.entries.associateWith { JRadioButton(it.label) }
    val bodyArea = JBTextArea(initialBody, 5, 60).apply { lineWrap = true; wrapStyleWord = true; font = UIUtil.getLabelFont() }
    private val ruleCheck = JBCheckBox("Recurring pattern: propose an AGENTS.md rule", initialCandidate)
    private val ruleText = JBTextField(initialRuleText ?: "").apply {
        emptyText.text = "Rule for agents (optional; leave empty to word it later)"
        isEnabled = initialCandidate
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

    fun kind(): Kind = kindButtons.entries.first { it.value.isSelected }.key
    fun text(): String = bodyArea.text.trim()
    fun rule(): RuleInput = RuleInput(ruleCheck.isSelected, ruleText.text.trim().ifEmpty { null })

    /** Error message if the form cannot be saved, else null. */
    fun validateInput(): String? = if (text().isEmpty()) "Write something first." else null
}

/** A [CommentForm] with Save/Cancel buttons for use inside editor inlays. Ctrl+Enter saves, Esc cancels. */
class InlineComposer(
    initial: Annotation?,
    defaultKind: Kind = Kind.CHANGE_REQUEST,
    private val onSave: (Kind, String, RuleInput) -> String?,
    private val onCancel: () -> Unit,
) : JPanel(BorderLayout()), WidthAdjustable {
    val form = CommentForm(initial?.kind ?: defaultKind, initial?.body ?: "", initial?.isRuleCandidate == true, initial?.ruleText)
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
        bindKeys(form.bodyArea, ::submit, onCancel)
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

private fun bindKeys(area: JBTextArea, save: () -> Unit, cancel: () -> Unit) {
    area.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "lr.save")
    area.actionMap.put("lr.save", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) = save() })
    area.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "lr.cancel")
    area.actionMap.put("lr.cancel", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) = cancel() })
}

/** A single text box with Save/Cancel for writing or editing a reply inside an editor inlay. */
class InlineReplyComposer(
    initial: String,
    saveLabel: String,
    private val onSave: (String) -> String?,
    private val onCancel: () -> Unit,
) : JPanel(BorderLayout()), WidthAdjustable {
    private val area = JBTextArea(initial, 3, 60).apply { lineWrap = true; wrapStyleWord = true; font = UIUtil.getLabelFont() }
    private val error = JBLabel().apply { foreground = JBColor.RED; isVisible = false }

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(6))
        background = UIUtil.getPanelBackground()
        val save = JButton(saveLabel)
        val cancel = JButton("Cancel")
        save.addActionListener { submit() }
        cancel.addActionListener { onCancel() }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(save); add(cancel); add(JBLabel("Ctrl+Enter to save", UIUtil.ComponentStyle.SMALL).apply { foreground = JBColor.GRAY })
        }
        add(JBScrollPane(area), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply { isOpaque = false; add(error, BorderLayout.NORTH); add(buttons, BorderLayout.CENTER) }, BorderLayout.SOUTH)
        bindKeys(area, ::submit, onCancel)
    }

    private fun submit() {
        val text = area.text.trim()
        val problem = if (text.isEmpty()) "Write something first." else onSave(text)
        error.isVisible = problem != null
        error.text = problem ?: ""
        revalidate()
        SwingUtilities.getAncestorOfClass(JComponent::class.java, this)?.revalidate()
    }

    fun focusText() { area.requestFocusInWindow() }

    override var contentWidth: Int = JBUI.scale(INLINE_WIDTH)

    override fun getPreferredSize(): Dimension = Dimension(contentWidth, super.getPreferredSize().height)
}

/** Callbacks a [CommentCard] needs from its host. */
interface CommentCardActions {
    fun edit(c: Annotation)
    fun setStatus(c: Annotation, status: AnnotationStatus)
    fun delete(c: Annotation)
    fun copyPrompt(c: Annotation)
    fun reply(c: Annotation)
    fun editReply(c: Annotation, r: Reply)
    fun deleteReply(c: Annotation, r: Reply)
}

/** Read-only display of one comment with its replies, and Edit / Resolve / Reply / Delete buttons. */
class CommentCard(val comment: Annotation, actions: CommentCardActions) : JPanel(BorderLayout()), WidthAdjustable {
    override var contentWidth: Int = JBUI.scale(INLINE_WIDTH)
    private val bodyArea = JBTextArea(comment.body)

    init {
        val faded = comment.status != AnnotationStatus.OPEN
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
        if (comment.status != AnnotationStatus.OPEN) meta.append("  ·  ").append(comment.status.label)
        if (comment.isRuleCandidate) meta.append("  ·  rule candidate")
        (comment.anchor as? Anchor.Lines)?.ranges?.filter { it.endLine != it.startLine }?.forEach {
            meta.append("  ·  lines ${it.startLine}–${it.endLine}")
        }
        val metaLabel = JBLabel(meta.toString()).apply { foreground = JBColor.GRAY }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(title); add(metaLabel) }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(link("Edit") { actions.edit(comment) })
            if (comment.status == AnnotationStatus.OPEN) {
                add(link("Resolve") { actions.setStatus(comment, AnnotationStatus.RESOLVED) })
            } else {
                add(link("Reopen") { actions.setStatus(comment, AnnotationStatus.OPEN) })
            }
            add(link("Reply") { actions.reply(comment) })
            add(link("Copy prompt") { actions.copyPrompt(comment) })
            add(link("Delete") { actions.delete(comment) })
        }

        bodyArea.apply {
            font = UIUtil.getLabelFont()
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.emptyTop(3)
            foreground = if (faded) JBColor.GRAY else UIUtil.getLabelForeground()
        }
        val bottom = JPanel().apply {
            isOpaque = false
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            if (!comment.ruleText.isNullOrBlank()) {
                add(JBLabel("Rule: ${comment.ruleText}").apply { foreground = JBColor.GRAY; border = JBUI.Borders.emptyTop(3); alignmentX = 0f })
            } else if (comment.isRuleCandidate) {
                add(JBLabel("Rule candidate: needs wording").apply { foreground = JBColor.GRAY; border = JBUI.Borders.emptyTop(3); alignmentX = 0f })
            }
            for (r in comment.replies.filter { !it.deleted }) add(replyRow(r, actions))
            buttons.alignmentX = 0f
            add(buttons)
        }
        add(left, BorderLayout.NORTH)
        add(bodyArea, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
    }

    private fun replyRow(r: Reply, actions: CommentCardActions): JComponent {
        val text = JBTextArea(r.body).apply {
            font = UIUtil.getLabelFont()
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
        }
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = 0f
            border = JBUI.Borders.compound(JBUI.Borders.emptyTop(4), JBUI.Borders.customLine(JBColor.border(), 0, 2, 0, 0))
        }
        val links = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JBLabel("Reply").apply { foreground = JBColor.GRAY })
            add(link("Edit") { actions.editReply(comment, r) })
            add(link("Delete") { actions.deleteReply(comment, r) })
        }
        row.add(links, BorderLayout.NORTH)
        row.add(text, BorderLayout.CENTER)
        return row
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
    initial: Annotation?,
    defaultKind: Kind = Kind.COMMENT,
) : DialogWrapper(project) {
    private val form = CommentForm(initial?.kind ?: defaultKind, initial?.body ?: "", initial?.isRuleCandidate == true, initial?.ruleText)

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

/** Modal dialog for writing or editing a reply from a list. */
class ReplyDialog(project: com.intellij.openapi.project.Project, title: String, initial: String = "") : DialogWrapper(project) {
    private val area = JBTextArea(initial, 5, 60).apply { lineWrap = true; wrapStyleWord = true; font = UIUtil.getLabelFont() }

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(area)
    override fun getPreferredFocusedComponent(): JComponent = area
    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        if (area.text.isBlank()) com.intellij.openapi.ui.ValidationInfo("Write something first.", area) else null

    val text get() = area.text.trim()
}
