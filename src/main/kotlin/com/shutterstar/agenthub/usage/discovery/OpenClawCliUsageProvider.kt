package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.time.Instant
import java.util.Locale

/**
 * Account limit windows of the model providers OpenClaw is signed in to, from the `usage` block of
 * `openclaw status --usage --json`. OpenClaw queries each provider itself with its own credentials.
 *
 * The command takes about a minute (startup plus gateway and service checks), so this is a [slow]
 * provider. A provider's `--timeout` below ~30 s makes every provider report `Timeout` with empty
 * `windows`, which yields `null` here. The document also carries `accountEmail`, which is never read.
 */
internal class OpenClawCliUsageProvider(
    private val runCommand: (String) -> String = { HeadlessCommandSupport.runCommand(it, timeoutSeconds = COMMAND_TIMEOUT_SECONDS) },
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.HEADLESS_COMMAND
    override val requiresNetwork: Boolean = true
    override val slow: Boolean = true

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot? {
        val output = HeadlessCommandSupport.cleanOutput(runCommand(COMMAND))
        val start = JSON_START.find(output)?.range?.first ?: return null
        return parseStatusDocument(output.substring(start), now)
    }

    internal fun parseStatusDocument(json: String, now: Instant): AgentUsageSnapshot? {
        val usage = MetadataJsonParser.rawTopLevelField(json, "usage")?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val providersJson = MetadataJsonParser.rawTopLevelField(usage, "providers")?.trim()?.takeIf { it.startsWith("[") } ?: return null
        val providers = HeadlessCommandSupport.splitJsonObjects(providersJson).mapNotNull(::parseProvider).take(MAX_PROVIDERS)
        if (providers.isEmpty()) return null
        val single = providers.size == 1
        val windows = providers.flatMap { provider ->
            provider.windows.map { window ->
                if (single) window else window.copy(id = "${provider.id}_${window.id}", label = "${provider.name} · ${window.label}")
            }
        }.distinctBy { it.id }
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            planLabel = providers.singleOrNull()?.plan,
            observedAt = now,
            windows = windows,
            statusNote = "Account limits reported by `openclaw status --usage` for " + providers.joinToString(", ") { it.name } + ".",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    private data class ProviderWindows(val id: String, val name: String, val plan: String?, val windows: List<UsageWindow>)

    private fun parseProvider(raw: String): ProviderWindows? {
        val top = MetadataJsonParser.topLevelStringFields(raw, setOf("provider", "displayName", "plan"))
        val id = top["provider"]?.trim()?.takeIf { it.length in 1..32 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._" } } ?: return null
        val name = top["displayName"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32 && it.none { ch -> ch.isISOControl() } } ?: id
        val windowsJson = MetadataJsonParser.rawTopLevelField(raw, "windows")?.trim()?.takeIf { it.startsWith("[") } ?: return null
        val windows = HeadlessCommandSupport.splitJsonObjects(windowsJson).mapIndexedNotNull { index, window -> parseWindow(window, index) }
            .distinctBy { it.id }
            .take(MAX_WINDOWS)
        if (windows.isEmpty()) return null
        val plan = top["plan"]?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 32 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._+ " } }
            ?.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return ProviderWindows(id, name, plan, windows)
    }

    private fun parseWindow(raw: String, index: Int): UsageWindow? {
        val label = MetadataJsonParser.topLevelStringFields(raw, setOf("label"))["label"]?.trim()
            ?.takeIf { it.isNotEmpty() && it.none { ch -> ch.isISOControl() } }?.take(48) ?: return null
        val used = MetadataJsonParser.rawTopLevelField(raw, "usedPercent")?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(100.0) ?: return null
        val resetsAt = MetadataJsonParser.rawTopLevelField(raw, "resetAt")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val lower = label.lowercase(Locale.ROOT)
        val (kind, minutes) = when {
            "5h" in lower || "5-hour" in lower || "5 hour" in lower || "session" in lower -> UsageWindowKind.ROLLING to 300
            "week" in lower || "7d" in lower || "7-day" in lower -> UsageWindowKind.WEEKLY to 10_080
            "month" in lower -> UsageWindowKind.MONTHLY to null
            else -> UsageWindowKind.CUSTOM to null
        }
        val id = lower.replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifEmpty { "window_$index" }
        return UsageWindow(id = id, label = label, kind = kind, usedPercent = used, windowMinutes = minutes, resetsAt = resetsAt)
    }


    companion object {
        private const val AGENT_ID = "openclaw"
        private const val COMMAND = "openclaw status --usage --json --timeout 30000"
        internal const val COMMAND_TIMEOUT_SECONDS = 80L
        private const val MAX_PROVIDERS = 8
        private const val MAX_WINDOWS = 6

        // Log lines may precede the JSON document; it starts at the first line beginning with `{`.
        private val JSON_START = Regex("(?m)^\\{")
    }
}
