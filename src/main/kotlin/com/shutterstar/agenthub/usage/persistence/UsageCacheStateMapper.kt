package com.shutterstar.agenthub.usage.persistence

import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.LocalUsageBucket
import com.shutterstar.agenthub.usage.model.LocalUsageSummary
import com.shutterstar.agenthub.usage.model.UsageDiscoveryResult
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.time.Instant

/**
 * Sanitizing mapper between runtime usage models and persisted XML state.
 * Only allowlisted identifiers, bounded labels and numeric measurements are persisted;
 * secrets, tokens, emails and raw provider responses are never stored.
 */
internal object UsageCacheStateMapper {
    private const val MAX_ID_LENGTH = 64
    private const val MAX_LABEL_LENGTH = 80
    private const val MAX_NOTE_LENGTH = 240
    private const val MAX_URL_LENGTH = 256
    private const val MAX_MODELS = 8

    fun encode(result: UsageDiscoveryResult): UsageCacheState = UsageCacheState(
        schemaVersion = UsageCacheState.CURRENT_SCHEMA_VERSION,
        refreshedAtEpochMillis = result.refreshedAt.toEpochMilli(),
        snapshots = result.snapshots.mapTo(mutableListOf(), ::encodeSnapshot),
    )

    fun decode(state: UsageCacheState): List<AgentUsageSnapshot> {
        if (state.schemaVersion != UsageCacheState.CURRENT_SCHEMA_VERSION) return emptyList()
        return state.snapshots.mapNotNull(::decodeSnapshot)
    }

    private fun encodeSnapshot(snapshot: AgentUsageSnapshot): UsageSnapshotState = UsageSnapshotState(
        agentId = sanitizeId(snapshot.agentId) ?: "",
        status = snapshot.status.name,
        sourceKind = snapshot.sourceKind.name,
        planLabel = sanitizeShortLabel(snapshot.planLabel),
        observedAtEpochMillis = snapshot.observedAt?.toEpochMilli(),
        windows = snapshot.windows.mapTo(mutableListOf(), ::encodeWindow),
        localSummary = snapshot.localSummary?.let(::encodeLocalSummary),
        statusNote = sanitizeNote(snapshot.statusNote),
        billingUrl = sanitizeHttpsUrl(snapshot.billingUrl),
    )

    private fun decodeSnapshot(state: UsageSnapshotState): AgentUsageSnapshot? {
        val agentId = sanitizeId(state.agentId) ?: return null
        val status = runCatching { UsageStatus.valueOf(state.status) }.getOrDefault(UsageStatus.LOCAL_ONLY)
        val sourceKind = runCatching { UsageSourceKind.valueOf(state.sourceKind) }.getOrDefault(UsageSourceKind.LOCAL_SESSIONS)
        return AgentUsageSnapshot(
            agentId = agentId,
            status = status,
            sourceKind = sourceKind,
            planLabel = sanitizeShortLabel(state.planLabel),
            observedAt = state.observedAtEpochMillis?.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
            windows = state.windows.mapNotNull(::decodeWindow),
            localSummary = state.localSummary?.let(::decodeLocalSummary),
            statusNote = sanitizeNote(state.statusNote),
            billingUrl = sanitizeHttpsUrl(state.billingUrl)?.takeIf(UsageSourceCatalog::isKnownUsageUrl) ?: UsageSourceCatalog.billingUrl(agentId),
        )
    }

    private fun encodeWindow(window: UsageWindow): UsageWindowState = UsageWindowState(
        id = sanitizeId(window.id) ?: "window",
        label = sanitizeLabel(window.label) ?: "Usage window",
        kind = window.kind.name,
        usedPercent = window.clampedPercent(),
        usedAmount = window.usedAmount?.takeIf { it.isFinite() && it >= 0.0 },
        limitAmount = window.limitAmount?.takeIf { it.isFinite() && it >= 0.0 },
        unit = sanitizeShortLabel(window.unit),
        windowMinutes = window.windowMinutes?.takeIf { it > 0 },
        resetsAtEpochMillis = window.resetsAt?.toEpochMilli(),
        unlimited = window.unlimited,
    )

    private fun decodeWindow(state: UsageWindowState): UsageWindow? {
        val id = sanitizeId(state.id) ?: return null
        val label = sanitizeLabel(state.label) ?: return null
        val kind = runCatching { UsageWindowKind.valueOf(state.kind) }.getOrDefault(UsageWindowKind.CUSTOM)
        return UsageWindow(
            id = id,
            label = label,
            kind = kind,
            usedPercent = state.usedPercent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0),
            usedAmount = state.usedAmount?.takeIf { it.isFinite() && it >= 0.0 },
            limitAmount = state.limitAmount?.takeIf { it.isFinite() && it >= 0.0 },
            unit = sanitizeShortLabel(state.unit),
            windowMinutes = state.windowMinutes?.takeIf { it > 0 },
            resetsAt = state.resetsAtEpochMillis?.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
            unlimited = state.unlimited,
        )
    }

    private fun encodeLocalSummary(summary: LocalUsageSummary): LocalUsageSummaryState = LocalUsageSummaryState(
        last5Hours = encodeBucket(summary.last5Hours),
        last24Hours = encodeBucket(summary.last24Hours),
        last7Days = encodeBucket(summary.last7Days),
        last30Days = encodeBucket(summary.last30Days),
        lastActivityEpochMillis = summary.lastActivity?.toEpochMilli(),
        models = summary.models.mapNotNull(::sanitizeModel).take(MAX_MODELS).toMutableList(),
    )

    private fun decodeLocalSummary(state: LocalUsageSummaryState): LocalUsageSummary = LocalUsageSummary(
        last5Hours = decodeBucket(state.last5Hours),
        last24Hours = decodeBucket(state.last24Hours),
        last7Days = decodeBucket(state.last7Days),
        last30Days = decodeBucket(state.last30Days),
        lastActivity = state.lastActivityEpochMillis?.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
        models = state.models.mapNotNull(::sanitizeModel).take(MAX_MODELS),
    )

    private fun encodeBucket(bucket: LocalUsageBucket): LocalUsageBucketState = LocalUsageBucketState(
        sessions = bucket.sessions.coerceAtLeast(0),
        prompts = bucket.prompts.coerceAtLeast(0L),
        inputTokens = bucket.inputTokens.coerceAtLeast(0L),
        cachedTokens = bucket.cachedTokens.coerceAtLeast(0L),
        outputTokens = bucket.outputTokens.coerceAtLeast(0L),
        totalTokens = bucket.totalTokens.coerceAtLeast(0L),
        recordedCostTicks = bucket.recordedCostTicks.coerceAtLeast(0L),
        premiumRequests = bucket.premiumRequests.coerceAtLeast(0L),
        nanoAiu = bucket.nanoAiu.coerceAtLeast(0L),
    )

    private fun decodeBucket(state: LocalUsageBucketState): LocalUsageBucket = LocalUsageBucket(
        sessions = state.sessions.coerceAtLeast(0),
        prompts = state.prompts.coerceAtLeast(0L),
        inputTokens = state.inputTokens.coerceAtLeast(0L),
        cachedTokens = state.cachedTokens.coerceAtLeast(0L),
        outputTokens = state.outputTokens.coerceAtLeast(0L),
        totalTokens = state.totalTokens.coerceAtLeast(0L),
        recordedCostTicks = state.recordedCostTicks.coerceAtLeast(0L),
        premiumRequests = state.premiumRequests.coerceAtLeast(0L),
        nanoAiu = state.nanoAiu.coerceAtLeast(0L),
    )

    internal fun sanitizeId(raw: String?): String? = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_LENGTH && it.all { ch -> ch.isLetterOrDigit() || ch in "-_." } }

    internal fun sanitizeShortLabel(raw: String?): String? = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 48 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._/ " } }

    internal fun sanitizeLabel(raw: String?): String? = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL_LENGTH && it.none { ch -> ch.isISOControl() } }

    internal fun sanitizeNote(raw: String?): String? = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.none { ch -> ch.isISOControl() } }
        ?.take(MAX_NOTE_LENGTH)

    internal fun sanitizeModel(raw: String?): String? = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 96 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._:/ " } }

    internal fun sanitizeHttpsUrl(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.length > MAX_URL_LENGTH || value.any { it.isISOControl() || it.isWhitespace() }) return null
        if (!value.startsWith("https://")) return null
        // Never persist URLs carrying embedded credentials.
        val authority = value.removePrefix("https://").substringBefore('/')
        if ('@' in authority) return null
        return value
    }
}
