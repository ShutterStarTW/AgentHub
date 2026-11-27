package com.shutterstar.agenthub.environment.skills.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The checkbox list of a bulk skill dialog: an intro text, "Select all" / "Clear", an "N of M selected" counter and
 * one scrollable row per item (its checkbox, the first line of its description and the dialog's own [details]).
 * What a selection does stays with the dialog; [onChange] tells it that the selection moved.
 */
internal class SkillSelectionList<T>(
    items: List<T>,
    label: (T) -> String,
    private val description: (T) -> String?,
    private val details: (T) -> List<JComponent>,
    private val onChange: () -> Unit,
) {
    private class Entry<T>(val item: T, val box: JCheckBox)

    private val entries = items.map { item ->
        Entry(item, JCheckBox(label(item), true).apply { font = font.deriveFont(Font.BOLD) })
    }
    private val countLabel = JBLabel().apply { foreground = JBColor.GRAY }

    val selected: List<T> get() = entries.filter { it.box.isSelected }.map { it.item }
    val selectedCount: Int get() = entries.count { it.box.isSelected }
    val total: Int get() = entries.size

    init {
        entries.forEach { it.box.addItemListener { refreshCount(); onChange() } }
        refreshCount()
    }

    private fun refreshCount() {
        countLabel.text = "$selectedCount of ${entries.size} selected"
    }

    private fun setAll(selected: Boolean) = entries.forEach { it.box.isSelected = selected }

    /** [intro] is an HTML text; the list sits in a scroll pane under the controls. */
    fun createPanel(intro: String): JComponent = JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.CONTROL_GAP))).apply {
        border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET)
        val top = JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
        top.add(JBLabel(intro), BorderLayout.NORTH)
        top.add(
            JPanel(BorderLayout()).apply {
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0)).apply {
                        add(ActionLink("Select all") { setAll(true) })
                        add(ActionLink("Clear") { setAll(false) })
                    },
                    BorderLayout.WEST,
                )
                add(countLabel, BorderLayout.EAST)
            },
            BorderLayout.SOUTH,
        )
        add(top, BorderLayout.NORTH)
        val list = AgentHubUiComponents.verticalBox()
        entries.forEachIndexed { index, entry ->
            if (index > 0) list.add(AgentHubUiComponents.detailDivider())
            list.add(row(entry))
        }
        add(
            JBScrollPane(JPanel(BorderLayout()).apply { add(list, BorderLayout.NORTH) }).apply {
                border = JBUI.Borders.customLine(JBColor.border())
                preferredSize = Dimension(JBUI.scale(ROW_WIDTH + 40), JBUI.scale(300))
            },
            BorderLayout.CENTER,
        )
    }

    private fun row(entry: Entry<T>): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AgentHubUiComponents.CONTROL_GAP)
        add(entry.box.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        description(entry.item)?.takeIf(String::isNotBlank)?.let { text ->
            add(AgentHubUiComponents.singleLineText(text.lineSequence().first().take(160), ROW_WIDTH).apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyLeft(INDENT)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }
        details(entry.item).forEach(::add)
    }

    companion object {
        /** The indent of the lines under a checkbox. */
        const val INDENT = 24

        /** The text width of a row, the same as the dialogs around it use. */
        const val ROW_WIDTH = 460
    }
}
