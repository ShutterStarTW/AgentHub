package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport
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
 * Account limit windows from `hermes usage --json`. Hermes resolves the credential of its own
 * configured provider (Codex, Anthropic OAuth, OpenRouter) and prints a documented, stable JSON
 * document (`provider`, `plan`, `windows[{label, used_percent, resets_at}]`, `unavailable_reason`).
 * Without a credential Hermes exits 1 with a plain message, which yields `null` here.
 */
internal class HermesCliUsageProvider(
    private val runCommand: (String) -> String = { HeadlessCommandSupport.runCommand(it, timeoutSeconds = 25) },
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.HEADLESS_COMMAND
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot? {
        val output = HeadlessCommandSupport.cleanOutput(runCommand(COMMAND))
        val start = output.indexOf('{')
        if (start < 0) return null
        return parseUsageDocument(output.substring(start), now)
    }

    internal fun parseUsageDocument(json: String, now: Instant): AgentUsageSnapshot? {
        val windowsJson = MetadataJsonParser.rawTopLevelField(json, "windows")?.trim()?.takeIf { it.startsWith("[") } ?: return null
        val windows = HeadlessCommandSupport.splitJsonObjects(windowsJson).mapIndexedNotNull { index, raw -> parseWindow(raw, index) }
            .distinctBy { it.id }
            .take(MAX_WINDOWS)
        if (windows.isEmpty()) return null
        val top = MetadataJsonParser.topLevelStringFields(json, setOf("plan", "provider", "unavailable_reason"))
        if (!top["unavailable_reason"].isNullOrBlank()) return null
        val provider = top["provider"]?.trim()?.takeIf { it.length in 1..32 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._" } }
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            planLabel = planLabel(top["plan"]),
            observedAt = now,
            windows = windows,
            statusNote = "Account limits reported by `hermes usage`" + (provider?.let { " for provider $it." } ?: "."),
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    private fun parseWindow(raw: String, index: Int): UsageWindow? {
        val label = MetadataJsonParser.topLevelStringFields(raw, setOf("label"))["label"]?.trim()
            ?.takeIf { it.isNotEmpty() && it.none { ch -> ch.isISOControl() } }?.take(48) ?: return null
        val percentRaw = MetadataJsonParser.rawTopLevelField(raw, "used_percent")?.trim()
        val used = percentRaw?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(100.0) ?: return null
        val resetsAt = MetadataJsonParser.topLevelStringFields(raw, setOf("resets_at"))["resets_at"]
            ?.let(LocalSessionSupport::parseTimestamp)
        val lower = label.lowercase(Locale.ROOT)
        val (kind, minutes) = when {
            "session" in lower || "5h" in lower || "5-hour" in lower || "5 hour" in lower -> UsageWindowKind.ROLLING to 300
            "week" in lower || "7d" in lower || "7-day" in lower -> UsageWindowKind.WEEKLY to 10_080
            "month" in lower -> UsageWindowKind.MONTHLY to null
            "credit" in lower -> UsageWindowKind.CREDITS to null
            else -> UsageWindowKind.CUSTOM to null
        }
        val id = lower.replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifEmpty { "window_$index" }
        return UsageWindow(
            id = id,
            label = label,
            kind = kind,
            usedPercent = used,
            windowMinutes = minutes,
            resetsAt = resetsAt,
        )
    }

    private fun planLabel(raw: String?): String? = raw?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 32 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._+ " } }
        ?.replaceFirstChar { it.titlecase(Locale.ROOT) }


    companion object {
        private const val AGENT_ID = "hermes"
        private const val COMMAND = "hermes usage --json"
        private const val MAX_WINDOWS = 12
    }
}
