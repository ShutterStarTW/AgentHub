package com.shutterstar.agenthub.usage.ui

import com.shutterstar.agenthub.projects.ui.AgentHubUiFormat
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.LocalUsageBucket
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageResetCredit
import com.shutterstar.agenthub.usage.model.UsageResetCredits
import com.shutterstar.agenthub.usage.model.UsageWindow
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.Locale

internal enum class UsageSortOrder(val label: String) {
    HIGHEST_USAGE("Highest usage"),
    NAME("Name"),
    RECENT_ACTIVITY("Recent activity"),
    ;

    override fun toString(): String = label
}

internal enum class UsageSeverity {
    CRITICAL,
    WARNING,
    NORMAL,
    STALE,
    NEUTRAL,
}

internal object UsageUiModel {
    const val DEFAULT_WARNING_PERCENT = 70.0
    const val DEFAULT_CRITICAL_PERCENT = 90.0

    fun peakPercent(snapshot: AgentUsageSnapshot, now: Instant = Instant.now()): Double? {
        val active = snapshot.windows
            .filterNot { it.unlimited }
            .filterNot { it.isResetPassed(now) }
            .mapNotNull { it.clampedPercent() }
        if (active.isNotEmpty()) return active.max()
        return snapshot.windows
            .filterNot { it.unlimited }
            .mapNotNull { it.clampedPercent() }
            .maxOrNull()
    }

    fun windowSeverity(
        window: UsageWindow,
        snapshotStatus: UsageStatus,
        now: Instant = Instant.now(),
        warningPercent: Double = DEFAULT_WARNING_PERCENT,
        criticalPercent: Double = DEFAULT_CRITICAL_PERCENT,
    ): UsageSeverity {
        if (snapshotStatus == UsageStatus.STALE || window.isResetPassed(now)) return UsageSeverity.STALE
        if (window.unlimited) return UsageSeverity.NORMAL
        val percent = window.clampedPercent() ?: return UsageSeverity.NEUTRAL
        return when {
            percent >= criticalPercent -> UsageSeverity.CRITICAL
            percent >= warningPercent -> UsageSeverity.WARNING
            else -> UsageSeverity.NORMAL
        }
    }

    fun snapshotSeverity(
        snapshot: AgentUsageSnapshot,
        now: Instant = Instant.now(),
        warningPercent: Double = DEFAULT_WARNING_PERCENT,
        criticalPercent: Double = DEFAULT_CRITICAL_PERCENT,
    ): UsageSeverity = when (snapshot.status) {
        UsageStatus.STALE, UsageStatus.UNAVAILABLE -> UsageSeverity.STALE
        UsageStatus.LOCAL_ONLY -> UsageSeverity.NEUTRAL
        UsageStatus.AUTH_REQUIRED, UsageStatus.RATE_LIMITED -> UsageSeverity.WARNING
        UsageStatus.OK -> {
            val peak = peakPercent(snapshot, now) ?: return UsageSeverity.NORMAL
            when {
                peak >= criticalPercent -> UsageSeverity.CRITICAL
                peak >= warningPercent -> UsageSeverity.WARNING
                else -> UsageSeverity.NORMAL
            }
        }
    }

    fun filterAndSort(
        snapshots: List<AgentUsageSnapshot>,
        query: String,
        sortOrder: UsageSortOrder,
        displayName: (String) -> String,
        now: Instant = Instant.now(),
        onlyWithLimits: Boolean = false,
    ): List<AgentUsageSnapshot> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val limited = if (onlyWithLimits) snapshots.filter(::hasRealLimit) else snapshots
        val filtered = if (normalizedQuery.isEmpty()) {
            limited
        } else {
            limited.filter { snapshot -> matches(snapshot, normalizedQuery, displayName) }
        }
        return when (sortOrder) {
            UsageSortOrder.NAME -> filtered.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { displayName(it.agentId) },
            )
            UsageSortOrder.RECENT_ACTIVITY -> filtered.sortedWith(
                compareByDescending<AgentUsageSnapshot> { latestTimestamp(it) ?: Instant.EPOCH }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { displayName(it.agentId) },
            )
            UsageSortOrder.HIGHEST_USAGE -> filtered.sortedWith(
                compareBy<AgentUsageSnapshot> { usageTierRank(it, now) }
                    .thenByDescending { peakPercent(it, now) ?: -1.0 }
                    .thenByDescending { it.localSummary?.last7Days?.totalTokens ?: 0L }
                    .thenByDescending { it.localSummary?.last7Days?.prompts ?: 0L }
                    .thenBy { if (it.agentId in UsageSourceCatalog.priorityAgentIds) 0 else 1 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { displayName(it.agentId) },
            )
        }
    }

    private fun usageTierRank(snapshot: AgentUsageSnapshot, now: Instant): Int = when {
        snapshot.status == UsageStatus.OK && snapshot.sourceKind == UsageSourceKind.LIVE_API && peakPercent(snapshot, now) != null -> 0
        snapshot.status == UsageStatus.OK && peakPercent(snapshot, now) != null -> 1
        snapshot.status == UsageStatus.RATE_LIMITED || snapshot.status == UsageStatus.AUTH_REQUIRED -> 2
        snapshot.status == UsageStatus.STALE && snapshot.windows.isNotEmpty() -> 3
        snapshot.localSummary?.hasAnyActivity == true -> 4
        else -> 5
    }

    private fun latestTimestamp(snapshot: AgentUsageSnapshot): Instant? =
        listOfNotNull(snapshot.observedAt, snapshot.localSummary?.lastActivity).maxOrNull()

    /**
     * A real limit is a window with a cap. An agent that needs a sign-in or is rate limited does have one that cannot
     * be read right now, so it stays: hiding it would also hide its Sign in button.
     */
    fun hasRealLimit(snapshot: AgentUsageSnapshot): Boolean =
        snapshot.windows.any { !it.unlimited } ||
            snapshot.status == UsageStatus.AUTH_REQUIRED ||
            snapshot.status == UsageStatus.RATE_LIMITED

    private fun matches(
        snapshot: AgentUsageSnapshot,
        query: String,
        displayName: (String) -> String,
    ): Boolean {
        if (displayName(snapshot.agentId).lowercase(Locale.ROOT).contains(query)) return true
        if (snapshot.agentId.lowercase(Locale.ROOT).contains(query)) return true
        if (snapshot.planLabel?.lowercase(Locale.ROOT)?.contains(query) == true) return true
        if (badgeText(snapshot).lowercase(Locale.ROOT).contains(query)) return true
        if (snapshot.windows.any { it.label.lowercase(Locale.ROOT).contains(query) }) return true
        if (snapshot.localSummary?.models?.any { it.lowercase(Locale.ROOT).contains(query) } == true) return true
        return false
    }

    fun badgeText(snapshot: AgentUsageSnapshot, now: Instant = Instant.now()): String = when (snapshot.status) {
        UsageStatus.OK -> {
            val peak = peakPercent(snapshot, now)
            when {
                peak != null -> formatPercent(peak)
                snapshot.windows.any { it.unlimited } -> "Unlimited"
                else -> "OK"
            }
        }
        UsageStatus.STALE -> {
            val peak = peakPercent(snapshot, now)
            if (peak != null) "${formatPercent(peak)} · Stale" else "Stale"
        }
        UsageStatus.AUTH_REQUIRED -> "Sign in"
        UsageStatus.RATE_LIMITED -> "Rate limited"
        UsageStatus.LOCAL_ONLY -> when {
            snapshot.planLabel != null -> snapshot.planLabel
            snapshot.localSummary?.hasAnyActivity == true -> "Local"
            else -> "Idle"
        }
        UsageStatus.UNAVAILABLE -> "Unavailable"
    }

    fun sourceDescription(snapshot: AgentUsageSnapshot): String = when (snapshot.sourceKind) {
        UsageSourceKind.HEADLESS_COMMAND -> "Live quota via agent CLI"
        UsageSourceKind.LIVE_API -> "Live provider quota"
        UsageSourceKind.CLI_QUERY -> "Live CLI status"
        UsageSourceKind.LOCAL_TELEMETRY -> "From last local session (may be outdated)"
        UsageSourceKind.LOCAL_SESSIONS -> "Local session activity"
    }

    fun shortWindowLabel(window: UsageWindow): String = when (window.windowMinutes) {
        300 -> "5h"
        1440 -> "24h"
        10_080 -> "7d"
        else -> when {
            window.label.length <= 12 -> window.label
            window.id == "primary" -> "Primary"
            window.id == "secondary" -> "Secondary"
            else -> window.label.take(12)
        }
    }

    fun windowCompactMeta(window: UsageWindow, now: Instant = Instant.now()): String {
        val parts = mutableListOf<String>()
        if (window.unlimited) {
            parts += "Unlimited"
        } else {
            window.clampedPercent()?.let { parts += formatPercent(it) }
                ?: window.limitAmount?.let { amount ->
                    val unit = window.unit?.let { " $it" }.orEmpty()
                    parts += "${formatAmount(amount)}$unit"
                }
        }
        window.resetsAt?.let { resetsAt ->
            parts += formatResetCountdown(resetsAt, now)
        }
        return parts.joinToString(" · ")
    }

    fun windowDetailValue(window: UsageWindow): String = when {
        window.unlimited -> "Unlimited"
        window.clampedPercent() != null && window.usedAmount != null && window.limitAmount != null -> {
            val unit = window.unit?.let { " $it" }.orEmpty()
            "${formatPercent(window.clampedPercent()!!)} (${formatAmount(window.usedAmount)} / ${formatAmount(window.limitAmount)}$unit)"
        }
        window.clampedPercent() != null -> "${formatPercent(window.clampedPercent()!!)} used"
        window.limitAmount != null -> {
            val unit = window.unit?.let { " $it" }.orEmpty()
            "${formatAmount(window.limitAmount)}$unit available"
        }
        else -> "Recorded"
    }

    fun windowResetLine(window: UsageWindow, now: Instant = Instant.now()): String? {
        val resetsAt = window.resetsAt ?: return null
        val formatted = AgentHubUiFormat.dateTime.format(resetsAt)
        return if (window.isResetPassed(now)) {
            "Window reset at $formatted — start a new session to capture current limits"
        } else {
            "Resets $formatted (${formatResetCountdown(resetsAt, now)})"
        }
    }

    /** "3 saved resets available" (empty when there is none). */
    fun resetCreditsHeadline(credits: UsageResetCredits): String? = when {
        credits.availableCount <= 0 -> null
        credits.availableCount == 1 -> "1 saved reset available"
        else -> "${credits.availableCount} saved resets available"
    }

    /** "One free rate limit reset — expires 11/30/26, 7:35 PM (in 10d 7h) · usable once a limit is reached". */
    fun resetCreditLine(credit: UsageResetCredit, now: Instant = Instant.now()): String {
        val title = credit.title ?: "Saved reset"
        val expiry = credit.expiresAt?.let { " — expires ${AgentHubUiFormat.dateTime.format(it)} (${formatResetCountdown(it, now)})" }.orEmpty()
        val usable = when (credit.requiresLimit) {
            true -> " · usable once a limit is reached"
            false -> " · usable now"
            null -> ""
        }
        return title + expiry + usable
    }

    /** How much of a saved reset's validity (grant to expiry) has passed (0..100), or null when either end is unknown. */
    fun resetCreditElapsedPercent(credit: UsageResetCredit, now: Instant = Instant.now()): Double? {
        val granted = credit.grantedAt ?: return null
        val expires = credit.expiresAt ?: return null
        val total = Duration.between(granted, expires).toMillis().toDouble()
        if (total <= 0.0) return null
        return (Duration.between(granted, now).toMillis() / total * 100.0).coerceIn(0.0, 100.0)
    }

    /** A saved reset about to expire turns amber (under 3 days) and red (under 1 day). */
    fun resetCreditSeverity(credit: UsageResetCredit, now: Instant = Instant.now()): UsageSeverity {
        val remaining = credit.expiresAt?.let { Duration.between(now, it) } ?: return UsageSeverity.NEUTRAL
        return when {
            remaining < Duration.ofDays(1) -> UsageSeverity.CRITICAL
            remaining < Duration.ofDays(3) -> UsageSeverity.WARNING
            else -> UsageSeverity.NEUTRAL
        }
    }

    /** Where a window stands against an even burn rate: [elapsedPercent] of it has passed. */
    data class UsagePace(
        val elapsedPercent: Double,
        val projectedPercent: Double,
        val ahead: Boolean,
        val minutesToLimit: Long?,
    )

    /**
     * Even-pace projection for a window with a known length and reset. Null when the window length
     * or reset is unknown, it already reset, or too little of it has passed to extrapolate.
     */
    fun pace(window: UsageWindow, now: Instant = Instant.now()): UsagePace? {
        val used = window.clampedPercent() ?: return null
        val resetsAt = window.resetsAt ?: return null
        val totalMinutes = window.windowMinutes?.takeIf { it > 0 }?.toDouble() ?: return null
        val remainingMinutes = Duration.between(now, resetsAt).toMillis() / 60_000.0
        if (remainingMinutes <= 0.0 || remainingMinutes > totalMinutes) return null
        val elapsedMinutes = totalMinutes - remainingMinutes
        if (elapsedMinutes / totalMinutes < MIN_PACE_ELAPSED_FRACTION) return null
        val projected = used / (elapsedMinutes / totalMinutes)
        val ahead = projected >= 100.0
        val minutesToLimit = when {
            used >= 100.0 -> 0L
            ahead && used > 0.0 -> ((100.0 - used) * elapsedMinutes / used).toLong()
            else -> null
        }
        return UsagePace(elapsedMinutes / totalMinutes * 100.0, projected, ahead, minutesToLimit)
    }

    /** "on track, ~64% by reset" or "ahead of pace, ~3h 10m to limit". */
    fun paceLine(window: UsageWindow, now: Instant = Instant.now()): String? {
        val pace = pace(window, now) ?: return null
        return if (pace.ahead) {
            "ahead of pace, ~${formatMinutes(pace.minutesToLimit ?: 0L)} to limit"
        } else {
            "on track, ~${formatPercent(pace.projectedPercent)} by reset"
        }
    }

    private fun formatMinutes(totalMinutes: Long): String {
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
            hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            else -> "${minutes}m"
        }
    }

    private const val MIN_PACE_ELAPSED_FRACTION = 0.02

    fun compactLocalActivityLines(snapshot: AgentUsageSnapshot): List<String> {
        val summary = snapshot.localSummary
        if (summary == null || !summary.hasAnyActivity) {
            return listOf("No local sessions recorded yet")
        }
        val lines = mutableListOf<String>()
        if (!summary.last24Hours.isEmpty) {
            lines += "24h: ${formatBucketCompact(summary.last24Hours)}"
        } else if (!summary.last7Days.isEmpty) {
            lines += "7d: ${formatBucketCompact(summary.last7Days)}"
        }
        if (!summary.last30Days.isEmpty && (lines.isEmpty() || summary.last30Days != summary.last24Hours)) {
            lines += "30d: ${formatBucketCompact(summary.last30Days)}"
        }
        return lines.ifEmpty { listOf("No recent sessions in the last 30 days") }
    }

    fun formatBucketCompact(bucket: LocalUsageBucket): String {
        if (bucket.isEmpty) return "No activity"
        val parts = mutableListOf<String>()
        if (bucket.totalTokens > 0L) parts += "${formatTokens(bucket.totalTokens)} tok"
        if (bucket.premiumRequests > 0L) parts += "${bucket.premiumRequests} premium req"
        if (bucket.recordedCostTicks > 0L) parts += formatCost(bucket.recordedCostTicks)
        if (parts.isEmpty() && bucket.prompts > 0L) parts += "${bucket.prompts} ${plural(bucket.prompts, "prompt", "prompts")}"
        if (parts.isEmpty() && bucket.sessions > 0) parts += "${bucket.sessions} ${plural(bucket.sessions.toLong(), "session", "sessions")}"
        return parts.joinToString(" · ")
    }

    fun formatBucketDetailed(bucket: LocalUsageBucket): String {
        if (bucket.isEmpty) return "No activity"
        val parts = mutableListOf<String>()
        if (bucket.sessions > 0) parts += "${bucket.sessions} ${plural(bucket.sessions.toLong(), "session", "sessions")}"
        if (bucket.prompts > 0L) parts += "${bucket.prompts} ${plural(bucket.prompts, "prompt", "prompts")}"
        if (bucket.totalTokens > 0L) parts += "${formatTokens(bucket.totalTokens)} tokens"
        if (bucket.premiumRequests > 0L) parts += "${bucket.premiumRequests} premium ${plural(bucket.premiumRequests, "request", "requests")}"
        if (bucket.recordedCostTicks > 0L) parts += formatCost(bucket.recordedCostTicks)
        return parts.joinToString(" · ")
    }

    fun formatPercent(percent: Double): String {
        val clamped = percent.coerceIn(0.0, 100.0)
        return when {
            clamped > 0.0 && clamped < 0.5 -> "<1%"
            else -> "${kotlin.math.round(clamped).toInt()}%"
        }
    }

    fun formatTokens(tokens: Long): String {
        if (tokens < 1_000L) return tokens.toString()
        if (tokens < 1_000_000L) return String.format(Locale.US, "%.1fK", tokens / 1_000.0).removeSuffix(".0K").let {
            if (it.endsWith("K")) it else "${it}K"
        }
        if (tokens < 1_000_000_000L) return String.format(Locale.US, "%.1fM", tokens / 1_000_000.0).removeSuffix(".0M").let {
            if (it.endsWith("M")) it else "${it}M"
        }
        return String.format(Locale.US, "%.2fB", tokens / 1_000_000_000.0)
    }

    fun formatCost(costTicks: Long): String {
        if (costTicks <= 0L) return "$0.00"
        val dollars = BigDecimal.valueOf(costTicks, 10)
        val cents = dollars.setScale(2, RoundingMode.HALF_UP)
        if (cents.compareTo(BigDecimal.ZERO) == 0) return "<$0.01"
        return "$$cents"
    }

    fun formatAmount(amount: Double): String {
        val rounded = kotlin.math.round(amount)
        return if (kotlin.math.abs(amount - rounded) < 0.005) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
    }

    fun formatResetCountdown(resetsAt: Instant, now: Instant = Instant.now()): String {
        val remaining = Duration.between(now, resetsAt)
        if (remaining.isNegative || remaining.isZero) return "reset"
        val totalMinutes = remaining.toMinutes()
        if (totalMinutes < 1L) return "in <1m"
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> if (hours > 0) "in ${days}d ${hours}h" else "in ${days}d"
            hours > 0 -> if (minutes > 0) "in ${hours}h ${minutes}m" else "in ${hours}h"
            else -> "in ${minutes}m"
        }
    }

    fun formatRelativeAge(instant: Instant, now: Instant = Instant.now()): String {
        val age = Duration.between(instant, now)
        if (age.isNegative || age.toMinutes() < 1L) return "just now"
        val minutes = age.toMinutes()
        if (minutes < 60L) return "${minutes}m ago"
        val hours = age.toHours()
        if (hours < 24L) return "${hours}h ago"
        val days = age.toDays()
        return "${days}d ago"
    }

    fun statusSummary(
        allSnapshots: List<AgentUsageSnapshot>,
        visibleCount: Int,
        filtered: Boolean,
        refreshing: Boolean,
        refreshedAt: Instant?,
        liveEnabled: Boolean = false,
        now: Instant = Instant.now(),
    ): String {
        if (refreshing) return if (liveEnabled) "Refreshing live usage limits…" else "Refreshing local usage limits…"
        val countLabel = if (filtered) {
            "$visibleCount of ${allSnapshots.size} ${plural(allSnapshots.size.toLong(), "agent", "agents")}"
        } else {
            "${allSnapshots.size} ${plural(allSnapshots.size.toLong(), "agent", "agents")}"
        }
        val nearLimit = allSnapshots.count { snapshot ->
            snapshot.status == UsageStatus.OK && snapshotSeverity(snapshot, now).let {
                it == UsageSeverity.WARNING || it == UsageSeverity.CRITICAL
            }
        }
        val parts = mutableListOf(countLabel)
        if (nearLimit > 0) parts += "$nearLimit near limit"
        parts += if (liveEnabled) "Live mode" else "Local mode"
        refreshedAt?.let { parts += "Updated ${AgentHubUiFormat.dateTime.format(it)}" }
        return parts.joinToString(" · ")
    }

    fun copySummaryText(
        snapshot: AgentUsageSnapshot,
        displayName: (String) -> String,
        now: Instant = Instant.now(),
    ): String = buildString {
        append(displayName(snapshot.agentId))
        snapshot.planLabel?.let { append(" ($it)") }
        append(" — ").append(badgeText(snapshot, now))
        snapshot.windows.forEach { window ->
            append("\n  ").append(window.label).append(": ").append(windowDetailValue(window))
            window.resetsAt?.let { append(" (").append(formatResetCountdown(it, now)).append(")") }
        }
        snapshot.localSummary?.takeIf { it.hasAnyActivity }?.let { local ->
            if (!local.last24Hours.isEmpty) append("\n  Last 24h: ").append(formatBucketDetailed(local.last24Hours))
            if (!local.last7Days.isEmpty) append("\n  Last 7d: ").append(formatBucketDetailed(local.last7Days))
            if (!local.last30Days.isEmpty) append("\n  Last 30d: ").append(formatBucketDetailed(local.last30Days))
        }
    }

    private fun plural(count: Long, singular: String, plural: String): String =
        if (count == 1L) singular else plural
}
