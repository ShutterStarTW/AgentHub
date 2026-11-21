package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.discovery.AgentProjectProviders
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.LocalUsageSummary
import com.shutterstar.agenthub.usage.model.UsageDiscoveryResult
import com.shutterstar.agenthub.usage.model.UsageDiscoveryWarning
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Coordinates usage providers (parallel across agents, bounded to 4 threads with shared timeout)
 * and merges results with local session consumption aggregated from [DiscoveredProject]s.
 *
 * - Only agents accepted by [isAgentVisible] are queried or returned.
 * - Network providers (`requiresNetwork == true`) only run when [liveQueriesEnabled] returns true.
 * - When multiple providers exist for the same agent (for example live API + local rollout
 *   fallback for Codex), live providers run first and local fallback is used only if live fails.
 * - When Live quotas is on and no provider returned anything for an agent, the last live reading
 *   from [previousSnapshots] is kept as `STALE` while its windows have not reset (see [lastGood]).
 */
class UsageDiscoveryService(
    private val providersSupplier: () -> List<AgentUsageProvider> = { AgentUsageProviders.all },
    private val isAgentVisible: (String) -> Boolean = { true },
    private val liveQueriesEnabled: () -> Boolean = { false },
    private val candidateAgentIds: () -> Set<String> = { AgentProjectProviders.agentIds + AgentUsageProviders.agentIds },
    private val providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    private val now: () -> Instant = Instant::now,
    private val slowProviderTimeoutMillis: Long = DEFAULT_SLOW_PROVIDER_TIMEOUT_MILLIS,
    private val resetCreditProvidersSupplier: () -> List<AgentResetCreditProvider> = { AgentResetCreditProviders.all },
    private val previousSnapshots: () -> List<AgentUsageSnapshot> = { emptyList() },
) {
    constructor(
        providers: List<AgentUsageProvider>,
        isAgentVisible: (String) -> Boolean = { true },
        liveQueriesEnabled: () -> Boolean = { false },
        candidateAgentIds: () -> Set<String> = { AgentProjectProviders.agentIds + AgentUsageProviders.agentIds },
        providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
        now: () -> Instant = Instant::now,
        slowProviderTimeoutMillis: Long = DEFAULT_SLOW_PROVIDER_TIMEOUT_MILLIS,
        resetCreditProviders: List<AgentResetCreditProvider> = emptyList(),
        previousSnapshots: () -> List<AgentUsageSnapshot> = { emptyList() },
    ) : this(
        providersSupplier = { providers },
        isAgentVisible = isAgentVisible,
        liveQueriesEnabled = liveQueriesEnabled,
        candidateAgentIds = candidateAgentIds,
        providerTimeoutMillis = providerTimeoutMillis,
        now = now,
        slowProviderTimeoutMillis = slowProviderTimeoutMillis,
        resetCreditProvidersSupplier = { resetCreditProviders },
        previousSnapshots = previousSnapshots,
    )

    fun discover(projects: List<DiscoveredProject>): UsageDiscoveryResult {
        val currentTime = now()
        val liveEnabled = liveQueriesEnabled()
        val localByAgent = LocalSessionUsageAggregator.aggregate(projects, currentTime)
            .filterKeys(isAgentVisible)

        val (telemetryByAgent, warnings) = runProviders(
            eligibleProviders(liveEnabled, slow = false),
            currentTime,
            providerTimeoutMillis,
            liveEnabled,
        )

        val allAgentIds = linkedSetOf<String>().apply {
            addAll(candidateAgentIds().filter(isAgentVisible))
            addAll(localByAgent.keys)
            addAll(telemetryByAgent.keys)
        }

        val warningsByAgent = warnings.associateBy { it.agentId }
        val previousByAgent = if (liveEnabled) previousSnapshots().associateBy { it.agentId } else emptyMap()
        val snapshots = allAgentIds.sorted().map { agentId ->
            mergeSnapshot(
                agentId = agentId,
                telemetry = telemetryByAgent[agentId] ?: lastGood(previousByAgent[agentId], currentTime),
                local = localByAgent[agentId],
                warning = warningsByAgent[agentId],
                liveEnabled = liveEnabled,
            )
        }
        return UsageDiscoveryResult(
            snapshots = snapshots,
            warnings = warnings,
            refreshedAt = currentTime,
            liveEnabled = liveEnabled,
        )
    }

    /** True when Live quotas is on and a visible slow provider ([AgentUsageProvider.slow]) is waiting for [discoverSlow]. */
    fun hasSlowProviders(): Boolean = eligibleProviders(liveQueriesEnabled(), slow = true).isNotEmpty()

    /**
     * Runs the slow providers on top of the [fast] result and replaces only their agents' snapshots
     * (the local session summary of the fast pass is kept). Returns [fast] itself when nothing changed.
     */
    fun discoverSlow(fast: UsageDiscoveryResult): UsageDiscoveryResult {
        val eligible = eligibleProviders(liveQueriesEnabled(), slow = true)
        if (eligible.isEmpty()) return fast
        val (telemetryByAgent, warnings) = runProviders(eligible, now(), slowProviderTimeoutMillis, liveQueriesEnabled())
        if (telemetryByAgent.isEmpty() && warnings.isEmpty()) return fast
        val snapshots = fast.snapshots.map { snapshot ->
            val slowSnapshot = telemetryByAgent[snapshot.agentId] ?: return@map snapshot
            slowSnapshot.copy(
                localSummary = snapshot.localSummary,
                billingUrl = slowSnapshot.billingUrl ?: snapshot.billingUrl,
            )
        }
        return fast.copy(snapshots = snapshots, warnings = fast.warnings + warnings)
    }

    private fun eligibleProviders(liveEnabled: Boolean, slow: Boolean): Map<String, List<AgentUsageProvider>> =
        providersSupplier()
            .filter { it.slow == slow }
            .filter { isAgentVisible(it.agentId) }
            .filter { liveEnabled || !it.requiresNetwork }
            .groupBy { it.agentId }
            .mapValues { (_, list) ->
                // Prefer live API / CLI providers ahead of local telemetry fallbacks.
                list.sortedBy { providerPriority(it) }
            }

    private fun runProviders(
        eligibleByAgent: Map<String, List<AgentUsageProvider>>,
        currentTime: Instant,
        timeoutMillis: Long,
        liveEnabled: Boolean,
    ): Pair<Map<String, AgentUsageSnapshot>, List<UsageDiscoveryWarning>> {
        val telemetryByAgent = linkedMapOf<String, AgentUsageSnapshot>()
        val warnings = mutableListOf<UsageDiscoveryWarning>()
        if (eligibleByAgent.isEmpty()) return telemetryByAgent to warnings
        val threadCount = minOf(eligibleByAgent.size, MAX_PROVIDER_THREADS)
        val executor = Executors.newFixedThreadPool(threadCount) { runnable ->
            Thread(runnable, "agenthub-usage-discovery").apply { isDaemon = true }
        }
        try {
            val futures = eligibleByAgent.entries.map { (agentId, agentProviders) ->
                agentId to executor.submit(Callable {
                    runAgentProviders(agentId, agentProviders, currentTime, liveEnabled)
                })
            }
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            for ((agentId, future) in futures) {
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(1L)
                try {
                    val outcome = future.get(remainingMillis, TimeUnit.MILLISECONDS)
                    outcome.snapshot?.let { telemetryByAgent[agentId] = it }
                    warnings += outcome.warnings
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    warnings += UsageDiscoveryWarning(agentId, "Usage query timed out")
                } catch (_: InterruptedException) {
                    future.cancel(true)
                    Thread.currentThread().interrupt()
                    warnings += UsageDiscoveryWarning(agentId, "Usage discovery was interrupted")
                    break
                } catch (error: Exception) {
                    val message = sanitizeError(error.cause?.message ?: error.message)
                    warnings += UsageDiscoveryWarning(agentId, message)
                }
            }
        } finally {
            executor.shutdownNow()
        }
        return telemetryByAgent to warnings
    }

    private fun runAgentProviders(
        agentId: String,
        providers: List<AgentUsageProvider>,
        now: Instant,
        liveEnabled: Boolean,
    ): AgentOutcome {
        val localWarnings = mutableListOf<UsageDiscoveryWarning>()
        // A snapshot without quota windows (auth required, rate limited, API-key mode) must not stop the
        // chain: a later local provider may still have real windows. It is only the fallback result.
        var fallback: AgentUsageSnapshot? = null
        for (provider in providers) {
            val available = runCatching { provider.isAvailable() }.getOrElse { error ->
                localWarnings += UsageDiscoveryWarning(agentId, sanitizeError(error.message))
                false
            }
            if (!available) continue
            try {
                val snapshot = provider.discover(now) ?: continue
                if (snapshot.windows.isNotEmpty()) {
                    return AgentOutcome(withResetCredits(agentId, snapshot, now, liveEnabled), localWarnings)
                }
                if (fallback == null) fallback = snapshot
            } catch (error: Exception) {
                localWarnings += UsageDiscoveryWarning(agentId, sanitizeError(error.message))
            }
        }
        return AgentOutcome(fallback, localWarnings)
    }

    /** Attaches saved resets when the winning provider did not report them; a failure only drops them. */
    private fun withResetCredits(agentId: String, snapshot: AgentUsageSnapshot, now: Instant, liveEnabled: Boolean): AgentUsageSnapshot {
        if (snapshot.resetCredits != null) return snapshot
        val provider = resetCreditProvidersSupplier()
            .firstOrNull { it.agentId == agentId && (liveEnabled || !it.requiresNetwork) } ?: return snapshot
        val credits = runCatching { provider.discover(now) }.getOrNull() ?: return snapshot
        return snapshot.copy(resetCredits = credits)
    }

    /**
     * The previous live reading of an agent, kept as `STALE` when the current query returned nothing. Only windows
     * that have not reset yet survive (a reset that passed must not be served as current); a window without a reset
     * time lives [UNDATED_WINDOW_TTL]. The original [AgentUsageSnapshot.observedAt] is kept, so the age never resets.
     * Saved resets are dropped, and AUTH_REQUIRED/RATE_LIMITED snapshots are never carried: they carry no windows
     * and the sign-in prompt must stay visible.
     */
    private fun lastGood(previous: AgentUsageSnapshot?, now: Instant): AgentUsageSnapshot? {
        if (previous == null || previous.sourceKind !in LIVE_SOURCE_KINDS) return null
        if (previous.status != UsageStatus.OK && previous.status != UsageStatus.STALE) return null
        val observedAt = previous.observedAt ?: return null
        val windows = previous.windows.filter { window ->
            when {
                window.unlimited -> false
                window.resetsAt != null -> window.resetsAt.isAfter(now)
                else -> Duration.between(observedAt, now) <= UNDATED_WINDOW_TTL
            }
        }
        if (windows.isEmpty()) return null
        return previous.copy(
            status = UsageStatus.STALE,
            windows = windows,
            localSummary = null,
            resetCredits = null,
            statusNote = "Last live reading (${NOTE_TIME.format(observedAt)} UTC); the latest query returned no limits.",
        )
    }

    private fun providerPriority(provider: AgentUsageProvider): Int = when (provider.sourceKind) {
        UsageSourceKind.HEADLESS_COMMAND -> 0
        UsageSourceKind.LIVE_API -> 1
        UsageSourceKind.CLI_QUERY -> 2
        UsageSourceKind.LOCAL_TELEMETRY -> 3
        UsageSourceKind.LOCAL_SESSIONS -> 4
    }

    private fun mergeSnapshot(
        agentId: String,
        telemetry: AgentUsageSnapshot?,
        local: LocalUsageSummary?,
        warning: UsageDiscoveryWarning?,
        liveEnabled: Boolean,
    ): AgentUsageSnapshot {
        val billingUrl = telemetry?.billingUrl ?: UsageSourceCatalog.billingUrl(agentId)
        if (telemetry != null) {
            return telemetry.copy(
                localSummary = local,
                billingUrl = billingUrl,
            )
        }
        if (warning != null && (local == null || !local.hasAnyActivity)) {
            return AgentUsageSnapshot(
                agentId = agentId,
                status = UsageStatus.UNAVAILABLE,
                sourceKind = UsageSourceKind.LOCAL_SESSIONS,
                observedAt = local?.lastActivity,
                localSummary = local,
                statusNote = warning.message,
                billingUrl = billingUrl,
            )
        }
        return AgentUsageSnapshot(
            agentId = agentId,
            status = UsageStatus.LOCAL_ONLY,
            sourceKind = UsageSourceKind.LOCAL_SESSIONS,
            observedAt = local?.lastActivity,
            localSummary = local,
            statusNote = warning?.message ?: UsageSourceCatalog.localOnlyNote(agentId, liveEnabled),
            billingUrl = billingUrl,
        )
    }

    private fun sanitizeError(raw: String?): String = raw
        ?.replace(Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE), "Bearer [redacted]")
        ?.replace(Regex("""token\s+\S+""", RegexOption.IGNORE_CASE), "token [redacted]")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(MAX_WARNING_LENGTH)
        ?: "Could not read usage data"

    private data class AgentOutcome(
        val snapshot: AgentUsageSnapshot?,
        val warnings: List<UsageDiscoveryWarning>,
    )

    companion object {
        private const val MAX_PROVIDER_THREADS = 4
        // Copilot worst case: `gh auth token` (6 s) + connect (5 s) + request (8 s).
        private const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 25_000L
        // OpenClaw `status --usage` runs ~50 s; its command timeout is 80 s.
        private const val DEFAULT_SLOW_PROVIDER_TIMEOUT_MILLIS = 90_000L
        private const val MAX_WARNING_LENGTH = 180
        private val UNDATED_WINDOW_TTL: Duration = Duration.ofHours(6)
        private val LIVE_SOURCE_KINDS = setOf(UsageSourceKind.HEADLESS_COMMAND, UsageSourceKind.LIVE_API, UsageSourceKind.CLI_QUERY)
        private val NOTE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    }
}
