package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.SafeFileTree
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport
import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.io.path.extension

/**
 * Passively reads the latest Codex `rate_limits` snapshot recorded inside local `rollout-*.jsonl`
 * `token_count` events. Zero network access, zero credential reads, bounded file and tail budgets.
 */
class CodexLocalRateLimitProvider(
    private val codexDirectory: Path = defaultCodexDirectory(),
    private val maxScanEntries: Int = MAX_SCAN_ENTRIES,
    private val maxRecentFiles: Int = MAX_RECENT_FILES,
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.LOCAL_TELEMETRY

    private val sessionDirectories = listOf(
        codexDirectory.resolve(SESSIONS_DIRECTORY),
        codexDirectory.resolve(ARCHIVED_SESSIONS_DIRECTORY),
    )

    override fun isAvailable(): Boolean =
        sessionDirectories.any { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

    override fun discover(now: Instant): AgentUsageSnapshot? {
        if (!isAvailable()) return null
        val recentFiles = findRecentRolloutFiles()
        var best: ParsedRateLimitRecord? = null

        for (candidate in recentFiles) {
            val parsed = runCatching { parseLatestRateLimits(candidate.file, candidate.modifiedAt) }.getOrNull()
                ?: continue
            if (best == null || isNewer(parsed.observedAt, best.observedAt)) {
                best = parsed
            }
            // Files are ordered newest-modified first; once we have a record newer than any remaining file's mtime, stop.
            val observedAt = best.observedAt
            if (candidate.modifiedAt != null && observedAt != null && !candidate.modifiedAt.isAfter(observedAt)) {
                break
            }
        }

        val record = best ?: return null
        val timedWindows = record.windows.filter { it.resetsAt != null }
        val allReset = timedWindows.isNotEmpty() && timedWindows.all { it.isResetPassed(now) }
        val agedWithoutFutureReset = timedWindows.isEmpty() &&
            record.observedAt != null &&
            Duration.between(record.observedAt, now) > STALE_AGE

        val status = if (allReset || agedWithoutFutureReset) UsageStatus.STALE else UsageStatus.OK
        val note = when {
            allReset -> "Observed in the last Codex session; recorded limit window has since reset."
            agedWithoutFutureReset -> "Observed in an earlier Codex session; run a new session to refresh."
            else -> "Observed in the latest Codex session."
        }
        return AgentUsageSnapshot(
            agentId = agentId,
            status = status,
            sourceKind = sourceKind,
            planLabel = record.planLabel,
            observedAt = record.observedAt,
            windows = record.windows,
            statusNote = note,
            billingUrl = UsageSourceCatalog.billingUrl(agentId),
        )
    }

    private fun findRecentRolloutFiles(): List<RolloutCandidate> {
        val candidates = mutableListOf<RolloutCandidate>()
        var remaining = maxScanEntries.coerceAtLeast(0)
        for (directory in sessionDirectories) {
            if (remaining <= 0 || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue
            var scanned = 0
            runCatching {
                SafeFileTree.walk(directory, MAX_SCAN_DEPTH).use { paths ->
                    paths
                        .skip(1)
                        .limit(remaining.toLong())
                        .peek { scanned++ }
                        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        .filter { it.extension.equals(JSONL_EXTENSION, ignoreCase = true) }
                        .forEach { file ->
                            candidates += RolloutCandidate(file, LocalSessionSupport.modifiedAt(file))
                        }
                }
            }
            remaining -= scanned
        }
        return candidates
            .sortedByDescending { it.modifiedAt ?: Instant.EPOCH }
            .take(maxRecentFiles.coerceAtLeast(1))
    }

    private fun parseLatestRateLimits(file: Path, fileModifiedAt: Instant?): ParsedRateLimitRecord? {
        val tailLines = LocalSessionSupport.readTailLines(file, MAX_TAIL_BYTES, MAX_TAIL_LINES)
        for (line in tailLines) {
            if (!line.contains("\"rate_limits\"")) continue
            val top = MetadataJsonParser.topLevelStringFields(line, TOP_LEVEL_FIELDS)
            if (top[TYPE_FIELD] != EVENT_MSG_TYPE) continue
            if (MetadataJsonParser.stringAtPath(line, PAYLOAD_FIELD, TYPE_FIELD) != TOKEN_COUNT_TYPE) continue
            val rateLimitsJson = MetadataJsonParser.rawPath(line, PAYLOAD_FIELD, RATE_LIMITS_FIELD)?.trim() ?: continue
            if (!rateLimitsJson.startsWith("{")) continue
            val parsed = parseRateLimitsObject(
                rateLimitsJson = rateLimitsJson,
                observedAt = LocalSessionSupport.parseTimestamp(top[TIMESTAMP_FIELD]) ?: fileModifiedAt,
            )
            if (parsed != null) return parsed
        }
        return null
    }

    internal fun parseRateLimitsObject(rateLimitsJson: String, observedAt: Instant?): ParsedRateLimitRecord? {
        val strings = MetadataJsonParser.topLevelStringFields(rateLimitsJson, RATE_LIMIT_STRING_FIELDS)
        val planLabel = formatPlanLabel(strings[PLAN_TYPE_FIELD])
        val limitPrefix = sanitizeLabel(strings[LIMIT_NAME_FIELD])
            ?: sanitizeLabel(strings[LIMIT_ID_FIELD])?.takeUnless { it.equals("codex", ignoreCase = true) }

        val windows = mutableListOf<UsageWindow>()
        MetadataJsonParser.rawTopLevelField(rateLimitsJson, PRIMARY_FIELD)?.trim()
            ?.takeIf { it.startsWith("{") }
            ?.let { parseWindow("primary", it, limitPrefix, defaultMinutes = 300) }
            ?.let(windows::add)

        MetadataJsonParser.rawTopLevelField(rateLimitsJson, SECONDARY_FIELD)?.trim()
            ?.takeIf { it.startsWith("{") }
            ?.let { parseWindow("secondary", it, limitPrefix, defaultMinutes = 10_080) }
            ?.let(windows::add)

        MetadataJsonParser.rawTopLevelField(rateLimitsJson, CREDITS_FIELD)?.trim()
            ?.takeIf { it.startsWith("{") }
            ?.let(::parseCreditsWindow)
            ?.let(windows::add)

        if (windows.isEmpty()) return null
        return ParsedRateLimitRecord(
            planLabel = planLabel,
            observedAt = observedAt,
            windows = windows,
        )
    }

    private fun parseWindow(
        id: String,
        windowJson: String,
        prefix: String?,
        defaultMinutes: Int,
    ): UsageWindow? {
        val usedPercent = parseDoubleField(windowJson, USED_PERCENT_FIELD)
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.coerceAtMost(100.0)
            ?: return null
        val windowMinutes = MetadataJsonParser.topLevelLongFields(windowJson, setOf(WINDOW_MINUTES_FIELD))[WINDOW_MINUTES_FIELD]
            ?.takeIf { it in 1L..525_600L }
            ?.toInt()
        val resetsAt = parseResetTimestamp(windowJson)
        val effectiveMinutes = windowMinutes ?: defaultMinutes
        val baseLabel = formatWindowLabel(effectiveMinutes, id)
        val label = if (prefix != null) "$prefix · $baseLabel" else baseLabel
        return UsageWindow(
            id = id,
            label = label,
            kind = windowKindForMinutes(effectiveMinutes),
            usedPercent = usedPercent,
            windowMinutes = windowMinutes,
            resetsAt = resetsAt,
        )
    }

    private fun parseCreditsWindow(creditsJson: String): UsageWindow? {
        val booleans = MetadataJsonParser.topLevelBooleanFields(creditsJson, CREDIT_BOOLEAN_FIELDS)
        val unlimited = booleans[UNLIMITED_FIELD] == true
        val hasCredits = booleans[HAS_CREDITS_FIELD] == true
        val balance = parseDoubleField(creditsJson, BALANCE_FIELD)?.takeIf { it.isFinite() && it >= 0.0 }
        if (!unlimited && !hasCredits && balance == null) return null
        return UsageWindow(
            id = "credits",
            label = "Credits",
            kind = UsageWindowKind.CREDITS,
            usedPercent = null,
            limitAmount = balance,
            unit = if (balance != null) "credits" else null,
            unlimited = unlimited,
        )
    }

    private fun parseResetTimestamp(windowJson: String): Instant? {
        val raw = MetadataJsonParser.rawTopLevelField(windowJson, RESETS_AT_FIELD)?.trim() ?: return null
        if (raw == "null" || raw.isEmpty()) return null
        val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
        return LocalSessionSupport.parseTimestamp(unquoted)
            ?: unquoted.toLongOrNull()?.let(LocalSessionSupport::epochTimestamp)
    }

    private fun parseDoubleField(json: String, field: String): Double? {
        val raw = MetadataJsonParser.rawTopLevelField(json, field)?.trim() ?: return null
        if (raw == "null" || raw.isEmpty()) return null
        val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
        return unquoted.toDoubleOrNull()
    }

    private fun isNewer(candidate: Instant?, current: Instant?): Boolean = when {
        candidate == null -> false
        current == null -> true
        else -> candidate.isAfter(current)
    }

    private fun formatWindowLabel(minutes: Int, id: String): String = when {
        minutes == 300 -> "5-hour window"
        minutes == 1440 -> "24-hour window"
        minutes == 10_080 -> "Weekly limit"
        minutes % 1440 == 0 -> "${minutes / 1440}-day window"
        minutes % 60 == 0 -> "${minutes / 60}-hour window"
        minutes > 0 -> "${minutes}-minute window"
        else -> if (id == "primary") "Primary window" else "Secondary window"
    }

    private fun windowKindForMinutes(minutes: Int): UsageWindowKind = when {
        minutes <= 360 -> UsageWindowKind.ROLLING
        minutes <= 1440 -> UsageWindowKind.DAILY
        minutes <= 10_080 -> UsageWindowKind.WEEKLY
        else -> UsageWindowKind.CUSTOM
    }

    private fun formatPlanLabel(raw: String?): String? {
        val cleaned = sanitizeLabel(raw) ?: return null
        return when (cleaned.lowercase(Locale.ROOT)) {
            "free" -> "Free"
            "go" -> "Go"
            "plus" -> "Plus"
            "pro" -> "Pro"
            "team" -> "Team"
            "business" -> "Business"
            "enterprise" -> "Enterprise"
            "edu" -> "Edu"
            else -> cleaned.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    private fun sanitizeLabel(raw: String?): String? = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 48 && it.all { ch -> ch.isLetterOrDigit() || ch in "-._ " } }

    private data class RolloutCandidate(
        val file: Path,
        val modifiedAt: Instant?,
    )

    internal data class ParsedRateLimitRecord(
        val planLabel: String?,
        val observedAt: Instant?,
        val windows: List<UsageWindow>,
    )

    companion object {
        private const val AGENT_ID = "codex"
        private const val SESSIONS_DIRECTORY = "sessions"
        private const val ARCHIVED_SESSIONS_DIRECTORY = "archived_sessions"
        private const val JSONL_EXTENSION = "jsonl"
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_SCAN_ENTRIES = 20_000
        private const val MAX_RECENT_FILES = 16
        private const val MAX_TAIL_BYTES = 256 * 1024
        private const val MAX_TAIL_LINES = 200
        private val STALE_AGE = Duration.ofHours(6)

        private const val TYPE_FIELD = "type"
        private const val TIMESTAMP_FIELD = "timestamp"
        private const val EVENT_MSG_TYPE = "event_msg"
        private const val PAYLOAD_FIELD = "payload"
        private const val TOKEN_COUNT_TYPE = "token_count"
        private const val RATE_LIMITS_FIELD = "rate_limits"
        private const val LIMIT_ID_FIELD = "limit_id"
        private const val LIMIT_NAME_FIELD = "limit_name"
        private const val PLAN_TYPE_FIELD = "plan_type"
        private const val PRIMARY_FIELD = "primary"
        private const val SECONDARY_FIELD = "secondary"
        private const val CREDITS_FIELD = "credits"
        private const val USED_PERCENT_FIELD = "used_percent"
        private const val WINDOW_MINUTES_FIELD = "window_minutes"
        private const val RESETS_AT_FIELD = "resets_at"
        private const val HAS_CREDITS_FIELD = "has_credits"
        private const val UNLIMITED_FIELD = "unlimited"
        private const val BALANCE_FIELD = "balance"

        private val TOP_LEVEL_FIELDS = setOf(TYPE_FIELD, TIMESTAMP_FIELD)
        private val RATE_LIMIT_STRING_FIELDS = setOf(LIMIT_ID_FIELD, LIMIT_NAME_FIELD, PLAN_TYPE_FIELD)
        private val CREDIT_BOOLEAN_FIELDS = setOf(HAS_CREDITS_FIELD, UNLIMITED_FIELD)

        private fun defaultCodexDirectory(): Path = EnvHomeDirectorySupport.resolve("CODEX_HOME", ".codex")
    }
}
