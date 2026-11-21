package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * Credit usage from `kiro-cli chat --no-interactive "/usage"`. Kiro answers `/usage` locally
 * (verified: output starts with `Estimated Usage`, no credits are spent); the provider requires
 * that header, so any other output (for example a real chat reply) is discarded.
 *
 * Verified only on native Windows (2.20.1). Since 2.13 the command may be sent to the model when stdin is
 * a pipe, which would spend credits, so the provider is available only where it was verified: not on
 * macOS, Linux or in WSL mode.
 */
internal class KiroCliUsageProvider(
    private val runCommand: (String) -> String = { HeadlessCommandSupport.runCommand(it, timeoutSeconds = 30) },
    private val verifiedPlatform: () -> Boolean = AgentRuntime::isWindowsRuntime,
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.HEADLESS_COMMAND
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = verifiedPlatform()

    override fun discover(now: Instant): AgentUsageSnapshot? {
        val output = HeadlessCommandSupport.cleanOutput(runCommand(COMMAND))
        if (output.isBlank()) return null
        return parseUsageOutput(output, now)
    }

    internal fun parseUsageOutput(output: String, now: Instant): AgentUsageSnapshot? {
        val header = HEADER.find(output)
        if (header == null) {
            if (output.contains("not logged in", ignoreCase = true) || output.contains("kiro-cli login", ignoreCase = true)) {
                return HeadlessCommandSupport.authRequired(AGENT_ID, "Kiro CLI is not signed in. Run `kiro-cli login` to authenticate.", now)
            }
            return null
        }
        val credits = CREDITS.find(output) ?: return null
        val used = credits.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val limit = credits.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val resetsAt = runCatching { LocalDate.parse(header.groupValues[1]).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        val plan = header.groupValues[2].trim()
            .takeIf { it.isNotEmpty() && it.length <= 32 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._+ " } }
            ?.lowercase(Locale.ROOT)
            ?.split(' ')
            ?.joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            planLabel = plan,
            observedAt = now,
            windows = listOf(
                UsageWindow(
                    id = "credits",
                    label = "Credits",
                    kind = UsageWindowKind.MONTHLY,
                    usedPercent = (used / limit * 100.0).coerceIn(0.0, 100.0),
                    usedAmount = used,
                    limitAmount = limit,
                    unit = "credits",
                    resetsAt = resetsAt,
                ),
            ),
            statusNote = "Credit usage reported by `kiro-cli /usage`.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    companion object {
        private const val AGENT_ID = "kiro"
        private const val COMMAND = "kiro-cli chat --no-interactive \"/usage\""
        private val HEADER = Regex("""(?im)^\s*Estimated Usage\s*\|\s*resets on\s*(\d{4}-\d{2}-\d{2})\s*\|\s*(.+?)\s*$""")
        private val CREDITS = Regex("""(?i)Credits\s*\(\s*(\d+(?:\.\d+)?)\s+of\s+(\d+(?:\.\d+)?)""")
    }
}
