package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.util.Locale

/** Window and plan naming shared by the Codex usage providers (live API and app-server RPC). */
internal object CodexUsageFormat {
    fun windowLabel(minutes: Int, id: String): String = when {
        minutes == 300 -> "5-hour window"
        minutes == 1440 -> "24-hour window"
        minutes == 10_080 -> "Weekly limit"
        minutes % 1440 == 0 -> "${minutes / 1440}-day window"
        minutes % 60 == 0 -> "${minutes / 60}-hour window"
        else -> if (id == "primary") "Primary window" else "Secondary window"
    }

    fun windowKind(minutes: Int): UsageWindowKind = when {
        minutes <= 360 -> UsageWindowKind.ROLLING
        minutes <= 1440 -> UsageWindowKind.DAILY
        minutes <= 10_080 -> UsageWindowKind.WEEKLY
        else -> UsageWindowKind.CUSTOM
    }

    fun planLabel(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it.length <= 32 } ?: return null
        return when (cleaned) {
            "free" -> "Free"
            "go" -> "Go"
            "plus" -> "Plus"
            "pro" -> "Pro"
            "team" -> "Team"
            "business" -> "Business"
            "enterprise" -> "Enterprise"
            else -> cleaned.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}
