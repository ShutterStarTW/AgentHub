package com.shutterstar.agenthub.usage.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import com.shutterstar.agenthub.projects.ui.AgentHubUiFormat
import com.shutterstar.agenthub.projects.ui.DetailsHeader
import com.shutterstar.agenthub.projects.ui.DetailsTitle
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.LocalUsageBucket
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import java.time.Instant
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable

/**
 * Right-hand details view for a single agent on the Usage tab:
 *   - [DetailsHeader] (favicon, name, plan/source/observed timestamp)
 *   - Usage Limits section (progress bars, reset timestamps, or local-only explanation)
 *   - Local Activity section (5h / 24h / 7d / 30d breakdown + models)
 *   - Action buttons (Open Usage Dashboard, Show in Agents)
 */
internal class UsageDetailsPanel(
    private val displayName: (String) -> String = AgentHubUiComponents::displayName,
    private val agentIcon: (String) -> Icon? = AgentHubUiComponents::faviconFor,
    private val openAgent: (String) -> Unit = {},
    private val signIn: (String) -> Unit = {},
    private val openUrl: (String) -> Unit = BrowserUtil::browse,
    private val now: () -> Instant = Instant::now,
    /** Shown as a tab of the Agents details, which already has the agent header and the Agents list around it. */
    private val embedded: Boolean = false,
) : JPanel(BorderLayout()) {
    private val header = DetailsHeader()
    private val body = AgentHubUiComponents.verticalBox(
        opaque = false,
        border = JBUI.Borders.empty(
            AgentHubUiComponents.CONTROL_GAP,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.PANEL_INSET,
            AgentHubUiComponents.PANEL_INSET,
        ),
    )
    private val trackingBody = object : JPanel(BorderLayout()), Scrollable {
        init {
            isOpaque = false
            add(body, BorderLayout.NORTH)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            maxOf(JBUI.scale(16), visibleRect.height - JBUI.scale(16))
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
    private val scrollPane = JBScrollPane(trackingBody).apply {
        border = JBUI.Borders.empty()
        viewportBorder = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    private var shownAgentId: String? = null

    init {
        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        if (!embedded) add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        setSnapshot(null)
    }

    fun setSnapshot(snapshot: AgentUsageSnapshot?) {
        val agentChanged = snapshot?.agentId != shownAgentId
        shownAgentId = snapshot?.agentId
        val currentTime = now()

        if (snapshot == null) {
            header.set(null, emptyList(), placeholder = "Select an agent")
            body.removeAll()
            if (embedded) {
                body.add(AgentHubUiComponents.wrappingStatusText().apply {
                    text = "No usage data for this agent yet."
                    foreground = JBColor.GRAY
                })
            }
            body.revalidate()
            body.repaint()
            return
        }

        val name = displayName(snapshot.agentId)
        val headerDetails = buildList {
            val topMeta = listOfNotNull(
                snapshot.planLabel,
                UsageUiModel.sourceDescription(snapshot),
            ).joinToString(" · ")
            if (topMeta.isNotEmpty()) add(topMeta)
            snapshot.observedAt?.let { observed ->
                add("Observed ${AgentHubUiFormat.dateTime.format(observed)} (${UsageUiModel.formatRelativeAge(observed, currentTime)})")
            }
        }
        body.removeAll()
        if (embedded) {
            headerDetails.forEach { line ->
                body.add(AgentHubUiComponents.wrappingStatusText().apply {
                    text = line
                    foreground = JBColor.GRAY
                })
            }
            if (headerDetails.isNotEmpty()) body.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.PANEL_INSET)))
        } else {
            header.set(DetailsTitle(name, agentIcon(snapshot.agentId)), headerDetails)
        }
        addLimitsSection(snapshot, currentTime)
        addResetCreditsSection(snapshot, currentTime)
        body.add(AgentHubUiComponents.detailDivider())
        addLocalActivitySection(snapshot)
        body.add(AgentHubUiComponents.detailDivider())
        addActionsSection(snapshot)

        body.revalidate()
        body.repaint()
        if (agentChanged) AgentHubUiComponents.scrollToTop(trackingBody)
    }

    private fun addLimitsSection(snapshot: AgentUsageSnapshot, currentTime: Instant) {
        body.add(AgentHubUiComponents.sectionHeader("Usage Limits"))
        body.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))

        if (snapshot.windows.isEmpty()) {
            val note = snapshot.statusNote ?: "No local quota telemetry available."
            body.add(AgentHubUiComponents.wrappingStatusText().apply {
                text = note
                foreground = JBColor.GRAY
            })
            return
        }

        snapshot.statusNote?.takeIf { it.isNotBlank() }?.let { note ->
            body.add(AgentHubUiComponents.wrappingStatusText().apply {
                text = note
                foreground = JBColor.GRAY
            })
            body.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
        }

        snapshot.windows.forEachIndexed { index, window ->
            if (index > 0) body.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.PANEL_INSET)))
            body.add(windowBlock(window, snapshot, currentTime))
        }
    }

    /** Thin bar for the elapsed validity of a saved reset. */
    private fun timeBar(elapsedPercent: Double, severity: UsageSeverity, description: String): UsageProgressBar =
        UsageProgressBar(barHeight = 4, preferredBarWidth = 220).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            percent = elapsedPercent
            this.severity = severity
            toolTipText = description
            accessibleContext.accessibleName = description
        }

    private fun addResetCreditsSection(snapshot: AgentUsageSnapshot, currentTime: Instant) {
        val credits = snapshot.resetCredits ?: return
        val headline = UsageUiModel.resetCreditsHeadline(credits) ?: return
        body.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.PANEL_INSET)))
        body.add(AgentHubUiComponents.sectionHeader("Saved Resets"))
        body.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
        body.add(AgentHubUiComponents.wrappingStatusText().apply {
            text = headline
            font = font.deriveFont(font.style or Font.BOLD)
        })
        credits.credits.forEach { credit ->
            body.add(Box.createVerticalStrut(JBUI.scale(2)))
            body.add(AgentHubUiComponents.wrappingStatusText().apply {
                text = UsageUiModel.resetCreditLine(credit, currentTime)
                foreground = JBColor.GRAY
            })
            UsageUiModel.resetCreditElapsedPercent(credit, currentTime)?.let { elapsed ->
                body.add(Box.createVerticalStrut(JBUI.scale(2)))
                body.add(timeBar(elapsed, UsageUiModel.resetCreditSeverity(credit, currentTime), "Saved reset validity: ${UsageUiModel.formatPercent(elapsed)} has passed"))
            }
        }
        if (credits.credits.size < credits.availableCount) {
            body.add(Box.createVerticalStrut(JBUI.scale(2)))
            body.add(AgentHubUiComponents.wrappingStatusText().apply {
                text = "Details for the other ${credits.availableCount - credits.credits.size} are not reported."
                foreground = JBColor.GRAY
            })
        }
    }

    private fun windowBlock(
        window: UsageWindow,
        snapshot: AgentUsageSnapshot,
        currentTime: Instant,
    ): JPanel {
        val severity = UsageUiModel.windowSeverity(window, snapshot.status, currentTime)
        val valueText = UsageUiModel.windowDetailValue(window)
        val titleRow = JPanel(BorderLayout(JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            val label = JBLabel(window.label).apply {
                putClientProperty("html.disable", true)
                font = font.deriveFont(font.style or Font.BOLD)
            }
            val value = JBLabel(valueText).apply {
                putClientProperty("html.disable", true)
                foreground = UsageProgressBar.badgeForeground(severity, JBColor.GRAY)
            }
            add(label, BorderLayout.WEST)
            add(value, BorderLayout.EAST)
        }

        return AgentHubUiComponents.detailBlock().apply {
            isOpaque = false
            add(titleRow)
            window.clampedPercent()?.let { percent ->
                add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
                add(UsageProgressBar(barHeight = 9, preferredBarWidth = 220).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    this.percent = percent
                    this.severity = severity
                    paceMarker = UsageUiModel.pace(window, currentTime)?.elapsedPercent
                    val resetSuffix = window.resetsAt?.let { ", ${UsageUiModel.formatResetCountdown(it, currentTime)}" }.orEmpty()
                    accessibleContext.accessibleName = "${window.label}: $valueText$resetSuffix"
                })
            }
            UsageUiModel.paceLine(window, currentTime)?.let { paceLine ->
                add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
                add(AgentHubUiComponents.wrappingStatusText().apply {
                    text = "Pace: $paceLine"
                    toolTipText = "The mark on the bar is the even-pace point; fill past it means you are ahead."
                    foreground = JBColor.GRAY
                })
            }
            UsageUiModel.windowResetLine(window, currentTime)?.let { resetLine ->
                add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
                add(AgentHubUiComponents.wrappingStatusText().apply {
                    text = resetLine
                    foreground = JBColor.GRAY
                })
            }
        }
    }

    private fun addLocalActivitySection(snapshot: AgentUsageSnapshot) {
        body.add(AgentHubUiComponents.sectionHeader("Local Activity"))
        body.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
        body.add(AgentHubUiComponents.wrappingStatusText().apply {
            text = "Measured locally from indexed sessions; may differ from provider billing."
            foreground = JBColor.GRAY
        })
        body.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))

        val local = snapshot.localSummary
        if (local == null || !local.hasAnyActivity) {
            body.add(AgentHubUiComponents.wrappingStatusText().apply {
                text = "No indexed sessions in the last 30 days."
                foreground = JBColor.GRAY
            })
            return
        }

        addBucketRow("Last 5 hours", local.last5Hours)
        addBucketRow("Last 24 hours", local.last24Hours)
        addBucketRow("Last 7 days", local.last7Days)
        addBucketRow("Last 30 days", local.last30Days)

        if (local.models.isNotEmpty()) {
            body.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
            body.add(AgentHubUiComponents.wrappingStatusText().apply {
                text = "Models: ${local.models.joinToString(", ")}"
                foreground = JBColor.GRAY
            })
        }
    }

    private fun addBucketRow(label: String, bucket: LocalUsageBucket) {
        body.add(AgentHubUiComponents.wrappingStatusText().apply {
            text = "$label: ${UsageUiModel.formatBucketDetailed(bucket)}"
            foreground = if (bucket.isEmpty) JBColor.GRAY else JBColor.foreground()
        })
        body.add(Box.createVerticalStrut(JBUI.scale(2)))
    }

    private fun addActionsSection(snapshot: AgentUsageSnapshot) {
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        if (snapshot.status == UsageStatus.AUTH_REQUIRED && UsageSourceCatalog.loginCommand(snapshot.agentId) != null) {
            actions.add(JButton("Sign in").apply {
                toolTipText = "Run `${UsageSourceCatalog.loginCommand(snapshot.agentId)}` in a terminal, then refresh"
                addActionListener { signIn(snapshot.agentId) }
            })
        }
        val billingUrl = snapshot.billingUrl?.takeIf { it.isNotBlank() }
        if (billingUrl != null) {
            actions.add(JButton(UsageSourceCatalog.linkLabel(billingUrl), AllIcons.Ide.External_link_arrow).apply {
                addActionListener { openUrl(billingUrl) }
            })
        }
        if (!embedded) {
            actions.add(JButton("Show in Agents").apply {
                addActionListener { openAgent(snapshot.agentId) }
            })
        }
        body.add(actions)
    }
}
