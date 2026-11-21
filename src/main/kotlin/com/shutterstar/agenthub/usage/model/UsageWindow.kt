package com.shutterstar.agenthub.usage.model

import java.time.Instant

enum class UsageWindowKind {
    ROLLING,
    DAILY,
    WEEKLY,
    MONTHLY,
    CREDITS,
    CUSTOM,
}

/**
 * One quota or rate-limit window reported for an agent (for example a 5-hour rolling window or
 * a weekly cap). Contains only sanitized identifiers and numeric measurements — never tokens,
 * credentials or raw response payloads.
 */
data class UsageWindow(
    val id: String,
    val label: String,
    val kind: UsageWindowKind,
    val usedPercent: Double?,
    val usedAmount: Double? = null,
    val limitAmount: Double? = null,
    val unit: String? = null,
    val windowMinutes: Int? = null,
    val resetsAt: Instant? = null,
    val unlimited: Boolean = false,
) {
    fun isResetPassed(now: Instant): Boolean = resetsAt != null && !resetsAt.isAfter(now)

    fun clampedPercent(): Double? = if (unlimited) null else usedPercent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
}
