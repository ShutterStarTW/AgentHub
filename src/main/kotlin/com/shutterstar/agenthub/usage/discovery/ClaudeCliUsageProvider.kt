package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Quota windows from `claude -p "/usage"`. The slash command is handled locally by Claude Code
 * (verified: no model call); AgentHub reads no OAuth token. The output is plain text, so a layout
 * change yields `null` and the OAuth provider takes over.
 */
internal class ClaudeCliUsageProvider(
    private val runCommand: (String) -> String = { HeadlessCommandSupport.runCommand(it, timeoutSeconds = 25) },
    private val defaultZone: ZoneId = ZoneId.systemDefault(),
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
        val windows = LINE.findAll(output).mapNotNull { match ->
            val (title, percent, reset) = match.destructured
            buildWindow(title.trim(), percent.toDoubleOrNull() ?: return@mapNotNull null, reset.trim(), now)
        }.distinctBy { it.id }.toList()
        if (windows.isEmpty()) {
            if (output.contains("log in", ignoreCase = true) || output.contains("not logged in", ignoreCase = true)) {
                return HeadlessCommandSupport.authRequired(AGENT_ID, "Claude Code is not signed in. Run `claude` to sign in.", now)
            }
            return null
        }
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            observedAt = now,
            windows = windows,
            statusNote = "Account usage reported by `claude /usage`.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    private fun buildWindow(title: String, percent: Double, reset: String, now: Instant): UsageWindow? {
        val lower = title.lowercase(Locale.ROOT)
        val resetsAt = parseReset(reset, now)
        val used = percent.coerceIn(0.0, 100.0)
        return when {
            "session" in lower -> UsageWindow("five_hour", "5-hour window", UsageWindowKind.ROLLING, used, windowMinutes = 300, resetsAt = resetsAt)
            "week" in lower -> {
                val model = MODEL_IN_PARENS.find(title)?.groupValues?.get(1)?.trim()
                val (id, label) = when {
                    model == null || model.equals("all models", ignoreCase = true) -> "seven_day" to "Weekly limit"
                    model.equals("Opus", ignoreCase = true) -> "seven_day_opus" to "Weekly Opus"
                    model.equals("Sonnet", ignoreCase = true) -> "seven_day_sonnet" to "Weekly Sonnet"
                    else -> "seven_day_${slug(model)}" to "Weekly ${model.take(24)}"
                }
                UsageWindow(id, label, UsageWindowKind.WEEKLY, used, windowMinutes = 10_080, resetsAt = resetsAt)
            }
            else -> null
        }
    }

    /** Parses `Nov 25, 5am (Europe/Budapest)`, `1:10pm (UTC)` or the same without a zone. */
    internal fun parseReset(text: String, now: Instant): Instant? {
        val match = RESET.find(text) ?: return null
        val zone = match.groupValues[6].takeIf { it.isNotBlank() }
            ?.let { runCatching { ZoneId.of(it.trim()) }.getOrNull() }
            ?: defaultZone
        val hour12 = match.groupValues[3].toIntOrNull() ?: return null
        if (hour12 !in 1..12) return null
        val minute = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val pm = match.groupValues[5].equals("pm", ignoreCase = true)
        val hour = (hour12 % 12) + if (pm) 12 else 0
        return try {
            val time = LocalTime.of(hour, minute)
            val nowLocal = ZonedDateTime.ofInstant(now, zone)
            val monthName = match.groupValues[1]
            if (monthName.isEmpty()) {
                var candidate = ZonedDateTime.of(nowLocal.toLocalDate(), time, zone)
                if (!candidate.toInstant().isAfter(now)) candidate = candidate.plusDays(1)
                candidate.toInstant()
            } else {
                val month = MONTHS[monthName.take(3).lowercase(Locale.ROOT)] ?: return null
                val day = match.groupValues[2].toInt()
                var candidate = ZonedDateTime.of(LocalDate.of(nowLocal.year, month, day), time, zone)
                // The CLI prints no year: a date far in the past means the next year.
                if (candidate.toInstant().isBefore(now.minusSeconds(SIX_MONTHS_SECONDS))) candidate = candidate.plusYears(1)
                candidate.toInstant()
            }
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun slug(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifEmpty { "other" }

    companion object {
        private const val AGENT_ID = "claude"
        private const val COMMAND = "claude -p \"/usage\""
        private const val SIX_MONTHS_SECONDS = 183L * 24 * 3600
        private val LINE = Regex("""(?im)^\s*(Current[^:\n]*):\s*(\d+(?:\.\d+)?)%\s*used\b(?:[^\n]*?\bresets\s+([^\n]*?))?\s*$""")
        private val MODEL_IN_PARENS = Regex("""\(([^)]+)\)""")
        private val RESET = Regex("""^(?:([A-Za-z]{3,9})\s+(\d{1,2}),?\s+)?(\d{1,2})(?::(\d{2}))?\s*([ap]m)\s*(?:\(([^)]*)\))?""", RegexOption.IGNORE_CASE)
        private val MONTHS: Map<String, Month> = Month.entries.associateBy { it.name.take(3).lowercase(Locale.ROOT) }
    }
}
