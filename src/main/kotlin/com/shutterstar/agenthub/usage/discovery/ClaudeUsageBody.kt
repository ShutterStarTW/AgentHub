package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport
import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind

/**
 * Parser for the Anthropic `oauth/usage` body. The same JSON is returned by the OAuth endpoint and
 * cached by Claude Code in `~/.claude.json` (`cachedUsageUtilization.utilization`), so the live and
 * the local-cache providers share it.
 */
internal object ClaudeUsageBody {
    fun windows(json: String): List<UsageWindow> {
        val windows = mutableListOf<UsageWindow>()
        parseWindow(json, "five_hour", "5-hour window", UsageWindowKind.ROLLING, 300)?.let(windows::add)
        parseWindow(json, "seven_day", "Weekly limit", UsageWindowKind.WEEKLY, 10_080)?.let(windows::add)
        parseWindow(json, "seven_day_opus", "Weekly Opus", UsageWindowKind.WEEKLY, 10_080)?.let(windows::add)
        parseWindow(json, "seven_day_sonnet", "Weekly Sonnet", UsageWindowKind.WEEKLY, 10_080)?.let(windows::add)
        parseExtraUsage(json)?.let(windows::add)
        return windows
    }

    private fun parseWindow(
        json: String,
        field: String,
        label: String,
        kind: UsageWindowKind,
        windowMinutes: Int,
    ): UsageWindow? {
        val raw = MetadataJsonParser.rawTopLevelField(json, field)?.trim() ?: return null
        if (!raw.startsWith("{")) return null
        val utilization = parseDouble(raw, "utilization")?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(100.0)
            ?: return null
        val resetsAt = MetadataJsonParser.topLevelStringFields(raw, setOf("resets_at"))["resets_at"]
            ?.let(LocalSessionSupport::parseTimestamp)
        return UsageWindow(
            id = field,
            label = label,
            kind = kind,
            usedPercent = utilization,
            windowMinutes = windowMinutes,
            resetsAt = resetsAt,
        )
    }

    private fun parseExtraUsage(json: String): UsageWindow? {
        val raw = MetadataJsonParser.rawTopLevelField(json, "extra_usage")?.trim() ?: return null
        if (!raw.startsWith("{")) return null
        val enabled = MetadataJsonParser.topLevelBooleanFields(raw, setOf("is_enabled"))["is_enabled"] == true
        if (!enabled) return null
        val utilization = parseDouble(raw, "utilization")?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(100.0)
        val used = parseDouble(raw, "used_credits")?.takeIf { it.isFinite() && it >= 0.0 }
        val limit = parseDouble(raw, "monthly_limit")?.takeIf { it.isFinite() && it > 0.0 }
        if (utilization == null && used == null && limit == null) return null
        val unit = MetadataJsonParser.topLevelStringFields(raw, setOf("currency"))["currency"]
            ?.trim()?.takeIf { it.length in 1..8 && it.all(Char::isLetter) }
            ?: "credits"
        return UsageWindow(
            id = "extra_usage",
            label = "Extra usage",
            kind = UsageWindowKind.CREDITS,
            usedPercent = utilization ?: if (used != null && limit != null) (used / limit * 100.0).coerceIn(0.0, 100.0) else null,
            usedAmount = used,
            limitAmount = limit,
            unit = unit,
        )
    }

    private fun parseDouble(json: String, field: String): Double? {
        val raw = MetadataJsonParser.rawTopLevelField(json, field)?.trim() ?: return null
        if (raw == "null" || raw.isEmpty()) return null
        val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
        return unquoted.toDoubleOrNull()
    }
}
