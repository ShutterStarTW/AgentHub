package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.AgentDetector
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import java.time.Instant

/**
 * Local CLI status provider for Cursor CLI (runs regardless of the live-quota opt-in: no AgentHub
 * credential access, no AgentHub network request).
 * Runs `cursor-agent about` (zero credential reading, zero prompt cost) to surface the active
 * subscription tier alongside local session activity and the Cursor usage dashboard link.
 */
internal class CursorCliStatusProvider(
    private val shellOutput: (String) -> String = { cmd -> AgentDetector.shellOutputAnyExit(cmd, timeoutSeconds = 8) },
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.CLI_QUERY

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot? {
        val output = shellOutput("cursor-agent about")
        if (output.isBlank()) return null
        return parseAboutOutput(output, now)
    }

    internal fun parseAboutOutput(output: String, now: Instant): AgentUsageSnapshot? {
        val tier = TIER_LINE.find(output)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 32 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._+ " } }
        val notLoggedIn = SIGNED_OUT.containsMatchIn(output)
        if (notLoggedIn && tier == null) {
            return AgentUsageSnapshot(
                agentId = AGENT_ID,
                status = UsageStatus.AUTH_REQUIRED,
                sourceKind = sourceKind,
                observedAt = now,
                statusNote = "Cursor CLI is not signed in. Sign in to see the plan and usage.",
                billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
            )
        }
        if (tier == null) return null
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.LOCAL_ONLY,
            sourceKind = sourceKind,
            planLabel = tier,
            observedAt = now,
            statusNote = "Cursor CLI reports $tier plan. Cursor does not expose live request quota percentages to CLI tools; open the Cursor usage dashboard below.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    companion object {
        private const val AGENT_ID = "cursor"
        private val SIGNED_OUT = Regex("""(?i)not\s+(logged|signed)\s+in|not\s+authenticated|log\s*in\s+required|please\s+(log|sign)\s*in|sign\s+in\s+to""")
        private val TIER_LINE = Regex("""(?im)^\s*Subscription\s+Tier\s{2,}(.+?)\s*$""")
    }
}
