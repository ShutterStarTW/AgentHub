package com.shutterstar.agenthub.usage.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import com.shutterstar.agenthub.projects.ui.ListHoverTracker
import com.shutterstar.agenthub.projects.ui.RoundedSelectionPanel
import com.shutterstar.agenthub.projects.ui.WrappedRowText
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageWindow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.Instant
import javax.swing.Box
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Renders one agent entry in the Usage tab list:
 *   [favicon] [displayName]               [badge]
 *   5h  [██████░░░░]  78% · in 1h 12m
 *   7d  [███░░░░░░░]  31% · in 4d 3h
 * or, for local-only agents, 1–2 wrapped gray local-activity lines.
 */
internal class UsageListRenderer(
    private val hover: ListHoverTracker,
    private val displayName: (String) -> String = AgentHubUiComponents::displayName,
    private val agentIcon: (String) -> Icon? = AgentHubUiComponents::faviconFor,
    private val now: () -> Instant = Instant::now,
    /** True in the narrow layout, where the list is the only thing on screen and the hint about more details is needed. */
    private val compactLayout: () -> Boolean = { false },
) : ListCellRenderer<AgentUsageSnapshot> {
    private val nameLabel = JBLabel().apply {
        putClientProperty("html.disable", true)
        font = font.deriveFont(font.style or Font.BOLD)
    }
    private val badgeLabel = JBLabel().apply {
        putClientProperty("html.disable", true)
    }
    private val topRow = JPanel(BorderLayout(JBUI.scale(AgentHubUiComponents.SMALL_GAP), 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(nameLabel, BorderLayout.CENTER)
        add(badgeLabel, BorderLayout.EAST)
    }
    private val windowRows = List(MAX_WINDOW_ROWS) { CompactWindowRow() }
    private val moreLabel = JBLabel().apply {
        putClientProperty("html.disable", true)
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(AgentHubUiComponents.SMALL_GAP)
    }
    private val detailLines = List(MAX_DETAIL_LINES) {
        WrappedRowText().apply {
            foreground = JBColor.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }
    private val content = AgentHubUiComponents.verticalBox(
        opaque = false,
        border = AgentHubUiComponents.listRowBorder(),
    ).apply {
        add(topRow)
        windowRows.forEach { add(it) }
        add(moreLabel)
        detailLines.forEach { add(it) }
    }
    private val wrapper = RoundedSelectionPanel.wrap(content).apply {
        selectionArc = JBUI.scale(AgentHubUiComponents.SELECTION_ARC)
        selectionInsets = AgentHubUiComponents.listSelectionInsets()
    }

    override fun getListCellRendererComponent(
        list: JList<out AgentUsageSnapshot>,
        value: AgentUsageSnapshot,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val currentTime = now()
        val colors = AgentHubUiComponents.rowTextColors(list.foreground, isSelected)
        val severity = UsageUiModel.snapshotSeverity(value, currentTime)
        val name = displayName(value.agentId)

        nameLabel.text = name
        nameLabel.icon = agentIcon(value.agentId)
        nameLabel.foreground = colors.foreground

        badgeLabel.text = UsageUiModel.badgeText(value, currentTime)
        badgeLabel.foreground = if (isSelected) {
            colors.foreground
        } else {
            UsageProgressBar.badgeForeground(severity, colors.secondaryForeground)
        }

        val allPercentWindows = value.windows.filter { it.clampedPercent() != null || it.unlimited }
        val percentWindows = allPercentWindows.take(MAX_WINDOW_ROWS)
        val hiddenWindows = allPercentWindows.size - percentWindows.size
        val extraText = if (compactLayout()) extraInfoText(hiddenWindows, value.resetCredits?.availableCount ?: 0) else ""
        moreLabel.isVisible = extraText.isNotEmpty()
        moreLabel.text = extraText
        moreLabel.foreground = colors.secondaryForeground
        if (percentWindows.isNotEmpty()) {
            windowRows.forEachIndexed { idx, row ->
                val window = percentWindows.getOrNull(idx)
                row.isVisible = window != null
                if (window != null) {
                    row.bind(window, value.status, currentTime, isSelected, colors.secondaryForeground)
                }
            }
            detailLines.forEach { it.isVisible = false }
        } else {
            windowRows.forEach { it.isVisible = false }
            val lines = UsageUiModel.compactLocalActivityLines(value)
            detailLines.forEachIndexed { idx, label ->
                val line = lines.getOrNull(idx).orEmpty()
                label.isVisible = line.isNotEmpty()
                label.text = line
                label.foreground = colors.secondaryForeground
            }
        }

        wrapper.background = list.background
        wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(index))
        wrapper.accessibleContext.accessibleName = "$name, ${badgeLabel.text}"
        return wrapper
    }

    private class CompactWindowRow : JPanel(GridBagLayout()) {
        private val prefixLabel = JBLabel().apply {
            putClientProperty("html.disable", true)
        }
        private val progressBar = UsageProgressBar(barHeight = 6, preferredBarWidth = 80)
        private val metaLabel = JBLabel().apply {
            putClientProperty("html.disable", true)
        }

        init {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(AgentHubUiComponents.SMALL_GAP)

            val leftConstraints = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 0.0
                anchor = GridBagConstraints.WEST
            }
            val barConstraints = GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(0, AgentHubUiComponents.CONTROL_GAP, 0, AgentHubUiComponents.CONTROL_GAP)
            }
            val metaConstraints = GridBagConstraints().apply {
                gridx = 2
                gridy = 0
                weightx = 0.0
                anchor = GridBagConstraints.EAST
            }
            add(prefixLabel, leftConstraints)
            add(progressBar, barConstraints)
            add(metaLabel, metaConstraints)
        }

        fun bind(
            window: UsageWindow,
            snapshotStatus: com.shutterstar.agenthub.usage.model.UsageStatus,
            now: Instant,
            isSelected: Boolean,
            secondaryForeground: Color,
        ) {
            prefixLabel.text = UsageUiModel.shortWindowLabel(window)
            prefixLabel.foreground = secondaryForeground
            val labelWidth = JBUI.scale(34)
            prefixLabel.preferredSize = Dimension(maxOf(labelWidth, prefixLabel.getFontMetrics(prefixLabel.font).stringWidth(prefixLabel.text)), prefixLabel.preferredSize.height)

            val percent = window.clampedPercent()
            progressBar.isVisible = percent != null
            if (percent != null) {
                progressBar.percent = percent
                progressBar.severity = UsageUiModel.windowSeverity(window, snapshotStatus, now)
                progressBar.onSelectedRow = isSelected
            }

            metaLabel.text = UsageUiModel.windowCompactMeta(window, now)
            metaLabel.foreground = secondaryForeground
        }
    }

    companion object {
        /** What the narrow list says beyond the two rows: more limit windows and saved resets, both in the details. */
        internal fun extraInfoText(hiddenWindows: Int, savedResets: Int): String {
            val parts = buildList {
                if (hiddenWindows > 0) add("+$hiddenWindows more ${if (hiddenWindows == 1) "limit" else "limits"}")
                if (savedResets > 0) add("$savedResets saved ${if (savedResets == 1) "reset" else "resets"}")
            }
            return if (parts.isEmpty()) "" else parts.joinToString(" · ") + " · open for details"
        }

        private const val MAX_WINDOW_ROWS = 2
        private const val MAX_DETAIL_LINES = 2
    }
}
