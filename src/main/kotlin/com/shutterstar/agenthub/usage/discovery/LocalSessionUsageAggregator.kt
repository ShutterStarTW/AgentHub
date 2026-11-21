package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.usage.model.LocalUsageBucket
import com.shutterstar.agenthub.usage.model.LocalUsageSummary
import java.time.Duration
import java.time.Instant

/**
 * Pure aggregation of already-indexed [DiscoveredProject] session statistics into rolling local
 * activity windows (5h, 24h, 7d, 30d). Uses session activity timestamps, so the result is a
 * local activity summary rather than an authoritative provider quota meter.
 */
object LocalSessionUsageAggregator {
    private val FIVE_HOURS = Duration.ofHours(5)
    private val TWENTY_FOUR_HOURS = Duration.ofHours(24)
    private val SEVEN_DAYS = Duration.ofDays(7)
    private val THIRTY_DAYS = Duration.ofDays(30)
    private const val MAX_MODELS = 8

    fun aggregate(
        projects: List<DiscoveredProject>,
        now: Instant = Instant.now(),
    ): Map<String, LocalUsageSummary> {
        val cutoff5h = now.minus(FIVE_HOURS)
        val cutoff24h = now.minus(TWENTY_FOUR_HOURS)
        val cutoff7d = now.minus(SEVEN_DAYS)
        val cutoff30d = now.minus(THIRTY_DAYS)

        val seenSessions = HashSet<Pair<String, String>>()
        val builders = linkedMapOf<String, AgentAccumulator>()

        projects.forEach { project ->
            project.agents.forEach { relation ->
                relation.sessions.forEach { session ->
                    if (!seenSessions.add(session.agentId to session.id)) return@forEach
                    val accumulator = builders.getOrPut(session.agentId) { AgentAccumulator() }
                    val timestamp = sessionTimestamp(session)
                    if (timestamp != null && (accumulator.lastActivity == null || timestamp.isAfter(accumulator.lastActivity))) {
                        accumulator.lastActivity = timestamp
                    }
                    if (timestamp != null && !timestamp.isBefore(cutoff30d)) {
                        accumulator.recordModels(session.statistics["models"])
                        accumulator.last30d.add(session)
                        if (!timestamp.isBefore(cutoff7d)) accumulator.last7d.add(session)
                        if (!timestamp.isBefore(cutoff24h)) accumulator.last24h.add(session)
                        if (!timestamp.isBefore(cutoff5h)) accumulator.last5h.add(session)
                    }
                }
            }
        }

        return builders.mapValues { (_, acc) -> acc.build() }
    }

    internal fun sessionTimestamp(session: AgentSession): Instant? =
        session.updatedAt
            ?: session.statistics["lastEventMillis"]?.toLongOrNull()?.takeIf { it > 0L }?.let(Instant::ofEpochMilli)
            ?: session.startedAt
            ?: session.statistics["firstEventMillis"]?.toLongOrNull()?.takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    private class AgentAccumulator {
        val last5h = BucketBuilder()
        val last24h = BucketBuilder()
        val last7d = BucketBuilder()
        val last30d = BucketBuilder()
        var lastActivity: Instant? = null
        private val models = linkedSetOf<String>()

        fun recordModels(rawModels: String?) {
            if (rawModels.isNullOrBlank() || models.size >= MAX_MODELS) return
            rawModels.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= 96 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._:/ " } }
                .forEach { model ->
                    if (models.size < MAX_MODELS) models.add(model)
                }
        }

        fun build(): LocalUsageSummary = LocalUsageSummary(
            last5Hours = last5h.build(),
            last24Hours = last24h.build(),
            last7Days = last7d.build(),
            last30Days = last30d.build(),
            lastActivity = lastActivity,
            models = models.toList(),
        )
    }

    private class BucketBuilder {
        var sessions = 0
        var prompts = 0L
        var inputTokens = 0L
        var cachedTokens = 0L
        var outputTokens = 0L
        var totalTokens = 0L
        var recordedCostTicks = 0L
        var premiumRequests = 0L
        var nanoAiu = 0L

        fun add(session: AgentSession) {
            sessions++
            session.messageCount?.takeIf { it > 0 }?.let { prompts += it.toLong() }
            val stats = session.statistics
            val input = positiveLong(stats["inputTokens"])
            val cached = positiveLong(stats["cachedTokens"])
            val output = positiveLong(stats["outputTokens"])
            val total = positiveLong(stats["totalTokens"]).let { explicit ->
                if (explicit > 0L) explicit else input + output
            }
            val cost = positiveLong(stats["reportedCostTicks"]).let { full ->
                if (full > 0L) full else positiveLong(stats["partialRecordedCostTicks"])
            }
            inputTokens += input
            cachedTokens += cached
            outputTokens += output
            totalTokens += total
            recordedCostTicks += cost
            premiumRequests += positiveLong(stats["premiumRequests"])
            nanoAiu += positiveLong(stats["nanoAiu"])
        }

        fun build(): LocalUsageBucket = LocalUsageBucket(
            sessions = sessions,
            prompts = prompts,
            inputTokens = inputTokens,
            cachedTokens = cachedTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            recordedCostTicks = recordedCostTicks,
            premiumRequests = premiumRequests,
            nanoAiu = nanoAiu,
        )

        private fun positiveLong(raw: String?): Long = raw?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
    }
}
