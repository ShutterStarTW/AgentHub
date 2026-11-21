package com.shutterstar.agenthub.usage.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import com.shutterstar.agenthub.projects.ui.CollapsibleWarningBar
import com.shutterstar.agenthub.projects.ui.DetailsTitle
import com.shutterstar.agenthub.projects.ui.ListHoverTracker
import com.shutterstar.agenthub.projects.ui.RendererToolTipList
import com.shutterstar.agenthub.projects.ui.ResponsiveMasterDetail
import com.shutterstar.agenthub.projects.ui.RowContextMenus
import com.shutterstar.agenthub.projects.ui.SharedSplitProportion
import com.shutterstar.agenthub.projects.ui.WrapFlowLayout
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

/**
 * Top-level "Usage" tab panel inside [com.shutterstar.agenthub.projects.ui.AgentHubToolWindowPanel].
 * Shares [ResponsiveMasterDetail] and [SharedSplitProportion] with Projects, Agents and Skills.
 */
internal class UsagePanel(
    private val displayName: (String) -> String = AgentHubUiComponents::displayName,
    private val agentIcon: (String) -> Icon? = AgentHubUiComponents::faviconFor,
    private val openAgent: (String) -> Unit = {},
    private val signIn: (String) -> Unit = {},
    private val onRefreshRequested: () -> Unit = {},
    private val onLiveQueriesToggled: (Boolean) -> Unit = {},
    private val onOnlyWithLimitsToggled: (Boolean) -> Unit = {},
    private val openUrl: (String) -> Unit = BrowserUtil::browse,
    private val copyText: (String) -> Unit = { text ->
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    },
    splitProportion: SharedSplitProportion = SharedSplitProportion(),
    private val now: () -> Instant = Instant::now,
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<AgentUsageSnapshot>()
    private val list = RendererToolTipList(model)
    private val detailsPanel = UsageDetailsPanel(
        displayName = displayName,
        agentIcon = agentIcon,
        openAgent = openAgent,
        signIn = signIn,
        openUrl = openUrl,
        now = now,
    )
    private val sortCombo = ComboBox(UsageSortOrder.entries.toTypedArray()).apply {
        selectedItem = UsageSortOrder.HIGHEST_USAGE
        accessibleContext.accessibleName = "Sort agents by"
    }
    private val liveQueriesCheckbox = JBCheckBox("Live quotas").apply {
        toolTipText = "Query live provider quotas (Claude, Codex, Copilot, Cursor, Grok) and CLI plan status using your existing CLI sign-in. Tokens are used in memory only and never saved or logged."
        accessibleContext.accessibleName = "Query live provider quotas"
    }
    private var updatingLiveCheckbox = false
    private val onlyWithLimitsCheckbox = JBCheckBox("Only with limits").apply {
        toolTipText = "Hide agents that report no usage limit. Agents that need a sign-in stay listed."
        accessibleContext.accessibleName = "Show only agents with usage limits"
    }
    private var onlyWithLimits = false
    private val controlsRow = JPanel(WrapFlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), JBUI.scale(2))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, AgentHubUiComponents.LIST_LEFT_INSET, AgentHubUiComponents.CONTROL_GAP, 0)
        add(JBLabel("Sort:").apply { labelFor = sortCombo })
        add(sortCombo)
        add(liveQueriesCheckbox)
        add(onlyWithLimitsCheckbox)
    }
    private val warningBar = CollapsibleWarningBar()
    private val headerPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(controlsRow, BorderLayout.NORTH)
        add(warningBar, BorderLayout.CENTER)
    }
    private val responsive = ResponsiveMasterDetail(
        list = AgentHubUiComponents.alignedBorderlessScrollPane(list),
        details = detailsPanel,
        backButtonText = "Usage",
        hasSelection = { list.selectedValue != null },
        onBack = { list.requestFocusInWindow() },
        selectionTitle = {
            list.selectedValue?.let { DetailsTitle(displayName(it.agentId), agentIcon(it.agentId)) }
        },
        splitProportion = splitProportion,
    )

    private var allSnapshots: List<AgentUsageSnapshot> = emptyList()
    private var query: String = ""
    private var refreshing: Boolean = false
    private var refreshedAt: Instant? = null
    private var liveEnabled: Boolean = false
    private var updatingModel: Boolean = false

    /** Invoked by the empty-list "Clear search" link; the tool window clears its shared search field. */
    var onClearSearch: () -> Unit = {}

    /** Fires while a narrow layout shows one agent's details alone — the tool window hides the search box then. */
    var onDetailsOnlyChanged: (Boolean) -> Unit = {}

    /** Fires whenever the summary string for the tool window status bar changes. */
    var onSummaryChanged: () -> Unit = {}

    val summaryText: String
        get() = UsageUiModel.statusSummary(
            allSnapshots = allSnapshots,
            visibleCount = model.size(),
            filtered = query.isNotBlank() || onlyWithLimits,
            refreshing = refreshing,
            refreshedAt = refreshedAt,
            liveEnabled = liveEnabled,
            now = now(),
        )

    init {
        responsive.onDetailsOnlyChanged = { detailsOnly ->
            headerPanel.isVisible = !detailsOnly
            onDetailsOnlyChanged(detailsOnly)
        }
        list.cellRenderer = UsageListRenderer(
            hover = ListHoverTracker(list),
            displayName = displayName,
            agentIcon = agentIcon,
            now = now,
            compactLayout = { responsive.isCompact },
        )
        list.emptyText.text = "No installed agents discovered"
        ToolTipManager.sharedInstance().registerComponent(list)
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !updatingModel) {
                detailsPanel.setSnapshot(list.selectedValue)
                responsive.selectionChanged()
            }
        }
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "show-usage-details")
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "show-usage-details")
        list.actionMap.put("show-usage-details", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = responsive.showDetailsNow()
        })
        RowContextMenus.install(list, ::contextMenu)
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val index = list.locationToIndex(event.point)
                if (index < 0 || list.getCellBounds(index, index)?.contains(event.point) != true) return
                responsive.showDetailsNow()
            }
        })
        // The narrow layout adds a line to the rows, so crossing the threshold must rebuild them.
        responsive.component.addComponentListener(object : java.awt.event.ComponentAdapter() {
            private var wasCompact = responsive.isCompact
            override fun componentResized(event: java.awt.event.ComponentEvent) {
                if (responsive.isCompact == wasCompact) return
                wasCompact = responsive.isCompact
                renderList()
            }
        })
        // The wrapped control row's height depends on its width, so a resize must lay the header out again.
        controlsRow.addComponentListener(object : java.awt.event.ComponentAdapter() {
            private var lastWidth = -1
            override fun componentResized(event: java.awt.event.ComponentEvent) {
                if (controlsRow.width == lastWidth) return
                lastWidth = controlsRow.width
                headerPanel.revalidate()
            }
        })
        sortCombo.addActionListener { renderList() }
        onlyWithLimitsCheckbox.addActionListener {
            onlyWithLimits = onlyWithLimitsCheckbox.isSelected
            onOnlyWithLimitsToggled(onlyWithLimits)
            renderList()
        }
        liveQueriesCheckbox.addActionListener {
            if (!updatingLiveCheckbox) {
                liveEnabled = liveQueriesCheckbox.isSelected
                onLiveQueriesToggled(liveEnabled)
            }
        }

        add(headerPanel, BorderLayout.NORTH)
        add(responsive.component, BorderLayout.CENTER)
    }

    fun setLiveQueriesEnabled(enabled: Boolean) {
        liveEnabled = enabled
        updatingLiveCheckbox = true
        try {
            liveQueriesCheckbox.isSelected = enabled
        } finally {
            updatingLiveCheckbox = false
        }
        onSummaryChanged()
    }

    fun setOnlyWithLimits(enabled: Boolean) {
        if (onlyWithLimits == enabled && onlyWithLimitsCheckbox.isSelected == enabled) return
        onlyWithLimits = enabled
        onlyWithLimitsCheckbox.isSelected = enabled
        renderList()
    }

    fun showDefaultContent() {
        responsive.showList()
        if (model.size > 0) {
            list.selectedIndex = 0
            list.ensureIndexIsVisible(0)
        }
    }

    fun setState(
        snapshots: List<AgentUsageSnapshot>,
        warnings: List<String>,
        refreshing: Boolean,
        refreshedAt: Instant?,
    ) {
        this.allSnapshots = snapshots
        this.refreshing = refreshing
        this.refreshedAt = refreshedAt
        warningBar.setWarnings(warnings)
        renderList()
    }

    fun setQuery(value: String) {
        if (query == value) return
        query = value
        renderList()
    }

    fun selectAgent(agentId: String): Boolean {
        val index = (0 until model.size()).firstOrNull { model.getElementAt(it).agentId == agentId } ?: return false
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        detailsPanel.setSnapshot(list.selectedValue)
        responsive.selectionChanged()
        return true
    }

    internal fun displayedAgentIds(): List<String> =
        (0 until model.size()).map { model.getElementAt(it).agentId }

    internal fun selectedAgentId(): String? = list.selectedValue?.agentId

    private fun renderList() {
        val selectedId = list.selectedValue?.agentId
        val sortOrder = (sortCombo.selectedItem as? UsageSortOrder) ?: UsageSortOrder.HIGHEST_USAGE
        val filtered = UsageUiModel.filterAndSort(
            snapshots = allSnapshots,
            query = query,
            sortOrder = sortOrder,
            displayName = displayName,
            now = now(),
            onlyWithLimits = onlyWithLimits,
        )
        updatingModel = true
        try {
            model.removeAllElements()
            // Batch addAll avoids O(n^2) variable-height re-measurement on every row.
            model.addAll(filtered)
            val restoredIndex = filtered.indexOfFirst { it.agentId == selectedId }
            list.selectedIndex = when {
                restoredIndex >= 0 -> restoredIndex
                filtered.isNotEmpty() -> 0
                else -> -1
            }
        } finally {
            updatingModel = false
        }
        detailsPanel.setSnapshot(list.selectedValue)
        responsive.selectionChanged()
        val isFiltered = query.isNotBlank()
        list.emptyText.text = when {
            refreshing -> if (liveEnabled) "Refreshing live usage limits…" else "Refreshing local usage limits…"
            isFiltered -> "No agents match the current search"
            onlyWithLimits && allSnapshots.isNotEmpty() -> "No agent reports a usage limit"
            else -> "No installed agents discovered"
        }
        if (isFiltered && !refreshing) {
            list.emptyText.appendLine("Clear search", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { onClearSearch() }
        } else if (onlyWithLimits && allSnapshots.isNotEmpty() && !refreshing) {
            list.emptyText.appendLine("Show all agents", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                setOnlyWithLimits(false)
                onOnlyWithLimitsToggled(false)
            }
        }
        onSummaryChanged()
    }

    private fun contextMenu(snapshot: AgentUsageSnapshot): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Show details").apply { addActionListener { responsive.showDetailsNow() } })
        add(JMenuItem("Refresh usage limits").apply { addActionListener { onRefreshRequested() } })
        val billingUrl = snapshot.billingUrl?.takeIf { it.isNotBlank() }
        if (billingUrl != null) {
            add(JMenuItem(UsageSourceCatalog.linkLabel(billingUrl)).apply {
                addActionListener { openUrl(billingUrl) }
            })
        }
        add(JMenuItem("Show in Agents").apply {
            addActionListener { openAgent(snapshot.agentId) }
        })
        addSeparator()
        add(JMenuItem("Copy Summary").apply {
            addActionListener { copyText(UsageUiModel.copySummaryText(snapshot, displayName, now())) }
        })
    }
}
