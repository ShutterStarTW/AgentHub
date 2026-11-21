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
 * Account limit windows of every provider Oh My Pi is signed in to, from `omp usage --json --redact`.
 * It is a real subcommand (about 2 s, no model call); OMP queries each provider with its own
 * credentials. `--redact` shortens account emails/ids, and only the `limits` are read here, never
 * the `metadata` or the saved reset credits (`omp usage reset` spends one and is never run).
 */
internal class OmpCliUsageProvider(
    private val runCommand: (String) -> String = { HeadlessCommandSupport.runCommand(it, timeoutSeconds = 25) },
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.HEADLESS_COMMAND
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot? {
        val output = HeadlessCommandSupport.cleanOutput(runCommand(COMMAND))
        val start = JSON_START.find(output)?.range?.first ?: return null
        return parseUsageDocument(output.substring(start), now)
    }

    internal fun parseUsageDocument(json: String, now: Instant): AgentUsageSnapshot? {
        val reportsJson = MetadataJsonParser.rawTopLevelField(json, "reports")?.trim()?.takeIf { it.startsWith("[") } ?: return null
        val reports = HeadlessCommandSupport.splitJsonObjects(reportsJson).mapNotNull(::parseReport).take(MAX_REPORTS)
        if (reports.isEmpty()) return null
        val windows = reports.flatMap { it.windows }.distinctBy { it.id }
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            observedAt = now,
            windows = windows,
            statusNote = "Account limits reported by `omp usage` for " + reports.joinToString(", ") { it.name } + ".",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    private data class Report(val name: String, val windows: List<UsageWindow>)

    private fun parseReport(raw: String): Report? {
        val provider = MetadataJsonParser.topLevelStringFields(raw, setOf("provider"))["provider"]?.trim()
            ?.takeIf { it.length in 1..40 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._" } } ?: return null
        val name = PROVIDER_NAMES[provider] ?: provider.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val limitsJson = MetadataJsonParser.rawTopLevelField(raw, "limits")?.trim()?.takeIf { it.startsWith("[") } ?: return null
        val windows = HeadlessCommandSupport.splitJsonObjects(limitsJson)
            .mapIndexedNotNull { index, limit -> parseLimit(limit, provider, name, index) }
            .distinctBy { it.id }
            .take(MAX_WINDOWS)
        if (windows.isEmpty()) return null
        return Report(name, windows)
    }

    private fun parseLimit(raw: String, provider: String, providerName: String, index: Int): UsageWindow? {
        val top = MetadataJsonParser.topLevelStringFields(raw, setOf("id", "label"))
        val label = top["label"]?.trim()?.takeIf { it.isNotEmpty() && it.none { ch -> ch.isISOControl() } }?.take(48) ?: return null
        val amount = MetadataJsonParser.rawTopLevelField(raw, "amount")?.trim() ?: return null
        if (MetadataJsonParser.topLevelStringFields(amount, setOf("unit"))["unit"] != "percent") return null
        val used = MetadataJsonParser.rawTopLevelField(amount, "used")?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(100.0) ?: return null
        val window = MetadataJsonParser.rawTopLevelField(raw, "window")?.trim().orEmpty()
        val windowId = MetadataJsonParser.topLevelStringFields(window, setOf("id"))["id"]?.lowercase(Locale.ROOT)
        val minutes = MetadataJsonParser.rawTopLevelField(window, "durationMs")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }?.let { (it / 60_000L).toInt() }
        val resetsAt = MetadataJsonParser.rawTopLevelField(window, "resetsAt")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val kind = when {
            windowId == "monthly" -> UsageWindowKind.MONTHLY
            minutes == 300 -> UsageWindowKind.ROLLING
            minutes == 10_080 -> UsageWindowKind.WEEKLY
            else -> UsageWindowKind.CUSTOM
        }
        val rawId = top["id"]?.lowercase(Locale.ROOT) ?: "$provider-$index"
        val id = rawId.replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48).ifEmpty { "${provider}_$index" }
        val shownLabel = if (label.contains(providerName, ignoreCase = true)) label else "$providerName · $label"
        return UsageWindow(
            id = id,
            label = shownLabel,
            kind = kind,
            usedPercent = used,
            windowMinutes = minutes,
            resetsAt = resetsAt,
        )
    }

    companion object {
        private const val AGENT_ID = "omp"
        private const val COMMAND = "omp usage --json --redact"
        private const val MAX_REPORTS = 8
        private const val MAX_WINDOWS = 6
        private val PROVIDER_NAMES = mapOf("anthropic" to "Claude", "openai-codex" to "Codex", "cursor" to "Cursor")

        // Log lines may precede the JSON document; it starts at the first line beginning with `{`.
        private val JSON_START = Regex("(?m)^\\{")
    }
}
