package com.shutterstar.agenthub.usage.model

import java.time.Instant

/**
 * Aggregated local measurements over a time window, derived solely from already-indexed session
 * statistics. Recorded cost uses the same 10^-10 USD ticks convention as [SessionStatistics].
 */
data class LocalUsageBucket(
    val sessions: Int = 0,
    val prompts: Long = 0L,
    val inputTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val recordedCostTicks: Long = 0L,
    val premiumRequests: Long = 0L,
    val nanoAiu: Long = 0L,
) {
    val isEmpty: Boolean
        get() = sessions == 0 &&
            prompts == 0L &&
            inputTokens == 0L &&
            cachedTokens == 0L &&
            outputTokens == 0L &&
            totalTokens == 0L &&
            recordedCostTicks == 0L &&
            premiumRequests == 0L &&
            nanoAiu == 0L
}

data class LocalUsageSummary(
    val last5Hours: LocalUsageBucket = LocalUsageBucket(),
    val last24Hours: LocalUsageBucket = LocalUsageBucket(),
    val last7Days: LocalUsageBucket = LocalUsageBucket(),
    val last30Days: LocalUsageBucket = LocalUsageBucket(),
    val lastActivity: Instant? = null,
    val models: List<String> = emptyList(),
) {
    val hasAnyActivity: Boolean
        get() = !last30Days.isEmpty || !last7Days.isEmpty || !last24Hours.isEmpty || !last5Hours.isEmpty
}
