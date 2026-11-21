package com.shutterstar.agenthub.usage.persistence

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shutterstar.agenthub.storage.AgentHubStorage
import com.shutterstar.agenthub.storage.StateStore
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageDiscoveryResult
import java.time.Instant

/**
 * Runtime-scoped shared cache (`~/.agenthub/cache/<runtime>/usage.xml`) for usage snapshots.
 * Decoded entries are cached by storage stamp so repeated EDT reads do not re-parse XML.
 */
@Service(Service.Level.APP)
class UsageCacheService(
    private val store: StateStore<UsageCacheState> = AgentHubStorage.cache(
        "usage",
        UsageCacheState::class.java,
        ::UsageCacheState,
    ),
) {
    private var cachedStamp: String? = null
    private var cachedSnapshots: List<AgentUsageSnapshot> = emptyList()
    private var cachedRefreshedAt: Instant? = null

    @Synchronized
    fun cachedSnapshots(isAgentVisible: (String) -> Boolean = { true }): List<AgentUsageSnapshot> {
        ensureLoaded()
        return cachedSnapshots.filter { isAgentVisible(it.agentId) }
    }

    @Synchronized
    fun lastRefreshedAt(): Instant? {
        ensureLoaded()
        return cachedRefreshedAt
    }

    @Synchronized
    fun record(result: UsageDiscoveryResult) {
        val encoded = UsageCacheStateMapper.encode(result)
        ensureLoaded()
        val sameContent = cachedSnapshots == UsageCacheStateMapper.decode(encoded) &&
            cachedRefreshedAt != null &&
            kotlin.math.abs(result.refreshedAt.toEpochMilli() - cachedRefreshedAt!!.toEpochMilli()) < MIN_TIMESTAMP_WRITE_GAP_MILLIS
        if (sameContent) return

        store.update { encoded }
        cachedStamp = store.stamp()
        cachedSnapshots = UsageCacheStateMapper.decode(encoded)
        cachedRefreshedAt = result.refreshedAt
    }

    fun storageStamp(): String = store.externalStamp()

    private fun ensureLoaded() {
        val currentStamp = store.stamp()
        if (currentStamp == cachedStamp) return
        val snapshot = store.snapshot()
        cachedSnapshots = UsageCacheStateMapper.decode(snapshot)
        cachedRefreshedAt = snapshot.refreshedAtEpochMillis.takeIf { it > 0L }?.let(Instant::ofEpochMilli)
        cachedStamp = currentStamp
    }

    companion object {
        /** Avoid rewriting unchanged XML on back-to-back refreshes within the same minute. */
        private const val MIN_TIMESTAMP_WRITE_GAP_MILLIS = 60_000L

        fun getInstance(): UsageCacheService = service()
    }
}
