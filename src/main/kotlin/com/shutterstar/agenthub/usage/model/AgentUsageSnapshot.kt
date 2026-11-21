package com.shutterstar.agenthub.usage.model

import java.time.Instant

enum class UsageStatus {
    /** Fresh, valid quota window data is available. */
    OK,
    /** Quota windows were read from local history, but are old or past their reset timestamp. */
    STALE,
    /** No quota telemetry is available; only locally measured session activity is shown. */
    LOCAL_ONLY,
    /** Live query requires signing in to the agent CLI (missing or expired local session). */
    AUTH_REQUIRED,
    /** Provider usage endpoint returned HTTP 429; retry after backoff. */
    RATE_LIMITED,
    /** Usage discovery failed or could not be read for this agent. */
    UNAVAILABLE,
}

enum class UsageSourceKind {
    /**
     * Quota reported by the agent CLI itself: its non-interactive `/usage` slash command or a local
     * RPC such as the Codex app-server (opt-in). AgentHub reads no token and makes no request
     * itself; the CLI uses its own sign-in.
     */
    HEADLESS_COMMAND,
    /** Live quota fetched directly from the provider's usage endpoint (opt-in). */
    LIVE_API,
    /** Live status/plan queried via a non-interactive CLI subcommand (opt-in). */
    CLI_QUERY,
    /** Passively parsed from an agent's own local telemetry / session files (zero network). */
    LOCAL_TELEMETRY,
    /** Aggregated from AgentHub's locally indexed session statistics (zero network). */
    LOCAL_SESSIONS,
}

/** One saved ("banked") limit reset the account can spend. Only display data; AgentHub never redeems one. */
data class UsageResetCredit(
    val title: String?,
    val expiresAt: Instant? = null,
    val grantedAt: Instant? = null,
    /** True when the provider only lets it be redeemed once a limit is reached; null when unknown. */
    val requiresLimit: Boolean? = null,
)

/** Saved limit resets of an account. [credits] can be shorter than [availableCount] (the backend may cap the rows). */
data class UsageResetCredits(
    val availableCount: Int,
    val credits: List<UsageResetCredit> = emptyList(),
)

data class AgentUsageSnapshot(
    val agentId: String,
    val status: UsageStatus,
    val sourceKind: UsageSourceKind,
    val planLabel: String? = null,
    val observedAt: Instant? = null,
    val windows: List<UsageWindow> = emptyList(),
    val localSummary: LocalUsageSummary? = null,
    val statusNote: String? = null,
    val billingUrl: String? = null,
    val resetCredits: UsageResetCredits? = null,
)

data class UsageDiscoveryWarning(
    val agentId: String,
    val message: String,
)

data class UsageDiscoveryResult(
    val snapshots: List<AgentUsageSnapshot>,
    val warnings: List<UsageDiscoveryWarning> = emptyList(),
    val refreshedAt: Instant = Instant.now(),
    val liveEnabled: Boolean = false,
)
