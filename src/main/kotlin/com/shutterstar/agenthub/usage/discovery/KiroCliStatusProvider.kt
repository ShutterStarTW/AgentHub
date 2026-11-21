package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.AgentDetector
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import java.time.Instant

/**
 * Local CLI status provider for Kiro CLI (runs regardless of the live-quota opt-in).
 * Uses `kiro-cli whoami` only (zero credit cost, zero credential reading). Credits come from
 * [KiroCliUsageProvider]; this provider is its sign-in-state fallback.
 */
internal class KiroCliStatusProvider(
    private val shellOutput: (String) -> String = { cmd -> AgentDetector.shellOutputAnyExit(cmd, timeoutSeconds = 6) },
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.CLI_QUERY

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot? {
        val output = shellOutput("kiro-cli whoami")
        if (output.isBlank()) return null
        return parseWhoamiOutput(output, now)
    }

    internal fun parseWhoamiOutput(output: String, now: Instant): AgentUsageSnapshot {
        val loggedIn = output.contains("Logged in", ignoreCase = true) &&
            !output.contains("not logged in", ignoreCase = true)
        if (!loggedIn) {
            return AgentUsageSnapshot(
                agentId = AGENT_ID,
                status = UsageStatus.AUTH_REQUIRED,
                sourceKind = sourceKind,
                observedAt = now,
                statusNote = "Kiro CLI is not signed in. Run `kiro-cli login` to authenticate.",
                billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
            )
        }
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.LOCAL_ONLY,
            sourceKind = sourceKind,
            observedAt = now,
            statusNote = "Signed in to Kiro CLI. Credits could not be read; run /usage inside Kiro CLI to check them.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    companion object {
        private const val AGENT_ID = "kiro"
    }
}
