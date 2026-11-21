package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.time.Instant
import java.util.Locale

/**
 * Quota windows from `agy -p "/usage"`. The CLI prints tab-separated rows
 * (`model group`, `window`, `NN%`, ISO reset time) and handles the command locally (verified: no
 * model call). Rows are percentages *remaining*, so the used share is `100 - value`.
 */
internal class AntigravityCliUsageProvider(
    private val runCommand: (String) -> String = { HeadlessCommandSupport.runCommand(it, timeoutSeconds = 25) },
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.HEADLESS_COMMAND
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot? {
        val output = HeadlessCommandSupport.cleanOutput(runCommand(COMMAND))
        if (output.isBlank()) return null
        return parseUsageOutput(output, now)
    }

    internal fun parseUsageOutput(output: String, now: Instant): AgentUsageSnapshot? {
        val windows = output.lineSequence()
            .mapNotNull(::parseRow)
            .distinctBy { it.id }
            .take(MAX_WINDOWS)
            .toList()
        if (windows.isEmpty()) {
            if (output.contains("sign in", ignoreCase = true) || output.contains("log in", ignoreCase = true)) {
                return HeadlessCommandSupport.authRequired(AGENT_ID, "Antigravity CLI is not signed in. Run `agy` to sign in.", now)
            }
            return null
        }
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            observedAt = now,
            windows = windows,
            statusNote = "Account limits reported by `agy /usage`.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    private fun parseRow(line: String): UsageWindow? {
        val cells = line.split('\t').map { it.trim() }
        if (cells.size < 4) return null
        val group = cells[0].takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
        val windowLabel = cells[1].takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
        val percent = cells[2].removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        if (!cells[2].endsWith("%")) return null
        val resetsAt = LocalSessionSupport.parseTimestamp(cells[3])

        val lower = windowLabel.lowercase(Locale.ROOT)
        val (kind, minutes) = when {
            "five hour" in lower || "5 hour" in lower || "5-hour" in lower -> UsageWindowKind.ROLLING to 300
            "weekly" in lower || "week" in lower -> UsageWindowKind.WEEKLY to 10_080
            "daily" in lower || "day" in lower -> UsageWindowKind.DAILY to 1440
            else -> UsageWindowKind.CUSTOM to null
        }
        // "Remaining" is explicit in most rows; a few rows omit it, and a fresh window still reads 100%.
        val used = if ("used" in lower) percent else 100.0 - percent
        val shortLabel = when (minutes) {
            300 -> "5-hour window"
            10_080 -> "Weekly limit"
            1440 -> "Daily limit"
            else -> windowLabel.removeSuffix(" Remaining").trim()
        }
        return UsageWindow(
            id = slug("$group $shortLabel"),
            label = "$group · $shortLabel".take(80),
            kind = kind,
            usedPercent = used.coerceIn(0.0, 100.0),
            windowMinutes = minutes,
            resetsAt = resetsAt,
        )
    }

    private fun slug(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(60).ifEmpty { "window" }

    companion object {
        private const val AGENT_ID = "antigravity"
        private const val COMMAND = "agy -p \"/usage\""
        private const val MAX_WINDOWS = 12
    }
}
