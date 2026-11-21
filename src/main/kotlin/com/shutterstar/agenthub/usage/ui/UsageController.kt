package com.shutterstar.agenthub.usage.ui

import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.usage.discovery.LocalSessionUsageAggregator
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageDiscoveryResult
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class UsageControllerState(
    val snapshots: List<AgentUsageSnapshot> = emptyList(),
    val warnings: List<String> = emptyList(),
    val loading: Boolean = false,
    val refreshedAt: Instant? = null,
)

/**
 * Coordinates background usage discovery, generation-based cancellation, live-query cooldown,
 * cache hydration, and EDT delivery without Kotlin coroutines.
 */
internal class UsageController(
    private val executor: (Runnable) -> Unit,
    private val deliver: (() -> Unit) -> Unit,
    private val discover: (List<DiscoveredProject>) -> UsageDiscoveryResult,
    private val cached: () -> Pair<List<AgentUsageSnapshot>, Instant?>,
    private val recordCache: (UsageDiscoveryResult) -> Unit,
    private val isLiveEnabled: () -> Boolean = { false },
    private val liveCooldownMillis: Long = DEFAULT_LIVE_COOLDOWN_MILLIS,
    private val now: () -> Instant = Instant::now,
    private val formatWarning: (String, String) -> String = { agentId, message -> "$agentId: $message" },
    private val onStateChanged: (UsageControllerState) -> Unit,
    private val hasSlowProviders: () -> Boolean = { false },
    private val discoverSlow: (UsageDiscoveryResult) -> UsageDiscoveryResult = { it },
) {
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    @Volatile
    private var state = UsageControllerState()
    @Volatile
    private var lastLiveRefreshAt: Instant? = null

    fun loadCached() {
        if (closed.get()) return
        val (snapshots, refreshedAt) = runCatching { cached() }.getOrDefault(emptyList<AgentUsageSnapshot>() to null)
        state = state.copy(
            snapshots = snapshots,
            refreshedAt = refreshedAt,
        )
        onStateChanged(state)
    }

    /**
     * Refreshes usage snapshots. When [force] is false and live queries are enabled, automatic
     * refreshes within [liveCooldownMillis] reuse the recent live quota windows and only update
     * local session summaries so provider APIs are not queried on every background index tick.
     */
    fun refresh(projects: List<DiscoveredProject>, force: Boolean = false) {
        if (closed.get()) return
        val liveEnabled = isLiveEnabled()
        val lastLive = lastLiveRefreshAt
        val currentTime = now()
        if (!force && liveEnabled && lastLive != null && state.snapshots.isNotEmpty()) {
            val elapsed = currentTime.toEpochMilli() - lastLive.toEpochMilli()
            if (elapsed in 0 until liveCooldownMillis) {
                val localByAgent = LocalSessionUsageAggregator.aggregate(projects, currentTime)
                state = state.copy(
                    snapshots = state.snapshots.map { snapshot ->
                        snapshot.copy(localSummary = localByAgent[snapshot.agentId] ?: snapshot.localSummary)
                    },
                )
                onStateChanged(state)
                return
            }
        }

        val ticket = generation.incrementAndGet()
        val projectSnapshot = projects.toList()
        state = state.copy(loading = true)
        onStateChanged(state)

        executor(Runnable {
            if (closed.get() || ticket != generation.get()) return@Runnable
            val result = runCatching { discover(projectSnapshot) }
            result.onSuccess { discovered ->
                if (discovered.liveEnabled) {
                    lastLiveRefreshAt = discovered.refreshedAt
                } else {
                    lastLiveRefreshAt = null
                }
                runCatching { recordCache(discovered) }
            }
            deliver {
                if (closed.get() || ticket != generation.get()) return@deliver
                state = result.fold(
                    onSuccess = { discovered ->
                        UsageControllerState(
                            snapshots = discovered.snapshots,
                            warnings = discovered.warnings.map { formatWarning(it.agentId, it.message) },
                            loading = false,
                            refreshedAt = discovered.refreshedAt,
                        )
                    },
                    onFailure = { error ->
                        val note = error.message?.takeIf { it.isNotBlank() } ?: "Usage discovery failed"
                        state.copy(
                            warnings = listOf(note),
                            loading = false,
                        )
                    },
                )
                onStateChanged(state)
            }
            result.onSuccess { discovered -> refreshSlow(ticket, discovered) }
        })
    }

    /**
     * Slow providers (about a minute) run after the fast result is already delivered, so they never
     * delay the other agents; only the agents they cover are replaced in the current state.
     */
    private fun refreshSlow(ticket: Long, discovered: UsageDiscoveryResult) {
        if (closed.get() || ticket != generation.get()) return
        if (!runCatching { hasSlowProviders() }.getOrDefault(false)) return
        val merged = runCatching { discoverSlow(discovered) }.getOrNull() ?: return
        if (merged === discovered) return
        runCatching { recordCache(merged) }
        val replaced = merged.snapshots
            .filter { snapshot -> discovered.snapshots.none { it === snapshot } }
            .associateBy { it.agentId }
        deliver {
            if (closed.get() || ticket != generation.get()) return@deliver
            state = state.copy(
                snapshots = state.snapshots.map { current ->
                    replaced[current.agentId]?.copy(localSummary = current.localSummary) ?: current
                },
                warnings = merged.warnings.map { formatWarning(it.agentId, it.message) },
            )
            onStateChanged(state)
        }
    }

    fun resetCooldown() {
        lastLiveRefreshAt = null
    }

    fun close() {
        closed.set(true)
        generation.incrementAndGet()
    }

    companion object {
        private const val DEFAULT_LIVE_COOLDOWN_MILLIS = 60_000L
    }
}
