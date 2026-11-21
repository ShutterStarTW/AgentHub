package com.shutterstar.agenthub.usage.persistence

data class UsageCacheState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var refreshedAtEpochMillis: Long = 0L,
    var snapshots: MutableList<UsageSnapshotState> = mutableListOf(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class UsageSnapshotState(
    var agentId: String = "",
    var status: String = "",
    var sourceKind: String = "",
    var planLabel: String? = null,
    var observedAtEpochMillis: Long? = null,
    var windows: MutableList<UsageWindowState> = mutableListOf(),
    var localSummary: LocalUsageSummaryState? = null,
    var statusNote: String? = null,
    var billingUrl: String? = null,
)

data class UsageWindowState(
    var id: String = "",
    var label: String = "",
    var kind: String = "",
    var usedPercent: Double? = null,
    var usedAmount: Double? = null,
    var limitAmount: Double? = null,
    var unit: String? = null,
    var windowMinutes: Int? = null,
    var resetsAtEpochMillis: Long? = null,
    var unlimited: Boolean = false,
)

data class LocalUsageBucketState(
    var sessions: Int = 0,
    var prompts: Long = 0L,
    var inputTokens: Long = 0L,
    var cachedTokens: Long = 0L,
    var outputTokens: Long = 0L,
    var totalTokens: Long = 0L,
    var recordedCostTicks: Long = 0L,
    var premiumRequests: Long = 0L,
    var nanoAiu: Long = 0L,
)

data class LocalUsageSummaryState(
    var last5Hours: LocalUsageBucketState = LocalUsageBucketState(),
    var last24Hours: LocalUsageBucketState = LocalUsageBucketState(),
    var last7Days: LocalUsageBucketState = LocalUsageBucketState(),
    var last30Days: LocalUsageBucketState = LocalUsageBucketState(),
    var lastActivityEpochMillis: Long? = null,
    var models: MutableList<String> = mutableListOf(),
)
