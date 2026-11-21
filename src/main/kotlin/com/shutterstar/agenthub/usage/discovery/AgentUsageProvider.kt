package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageResetCredits
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import java.time.Instant

/**
 * Adds the saved limit resets of an account to the snapshot another provider already produced for
 * the same agent. Read-only: it only lists the credits and never redeems one.
 */
interface AgentResetCreditProvider {
    val agentId: String
    val requiresNetwork: Boolean get() = false

    fun discover(now: Instant): UsageResetCredits?
}

/**
 * Discovers usage/quota snapshots for a single agent. Phase 1 providers are strictly local,
 * read-only, and never read credentials or perform network requests.
 */
interface AgentUsageProvider {
    val agentId: String
    val sourceKind: UsageSourceKind
    val requiresNetwork: Boolean get() = false

    /**
     * Takes far longer than the shared refresh deadline. Slow providers are skipped by
     * [UsageDiscoveryService.discover] and run afterwards by [UsageDiscoveryService.discoverSlow],
     * so they never delay the other agents.
     */
    val slow: Boolean get() = false

    fun isAvailable(): Boolean

    fun discover(now: Instant = Instant.now()): AgentUsageSnapshot?
}
