package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport
import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Passive, zero-network quota read for Claude Code: the last `oauth/usage` response that Claude Code
 * itself caches in its global config (`cachedUsageUtilization` in `~/.claude.json`). Only usage
 * figures are read; no credential field is touched. Works without the `Live quotas` opt-in.
 *
 * - The cache counts only when its `accountUuid` equals the signed-in `oauthAccount.accountUuid`.
 * - A window whose reset time has passed is dropped (its percentage describes a finished window).
 * - Data older than [FRESH_AGE] is reported as `STALE`, data older than [MAX_AGE] is ignored.
 */
internal class ClaudeLocalUsageCacheProvider(
    private val configFile: Path = defaultConfigFile(),
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.LOCAL_TELEMETRY

    override fun isAvailable(): Boolean = Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)

    override fun discover(now: Instant): AgentUsageSnapshot? {
        val text = runCatching { LocalSessionSupport.readBoundedText(configFile, MAX_CONFIG_CHARS) }.getOrNull() ?: return null
        if (text.length >= MAX_CONFIG_CHARS) return null
        return parseConfig(text, now)
    }

    internal fun parseConfig(json: String, now: Instant): AgentUsageSnapshot? {
        val cacheJson = MetadataJsonParser.rawTopLevelField(json, "cachedUsageUtilization")?.trim()
            ?.takeIf { it.startsWith("{") } ?: return null
        val cachedAccount = MetadataJsonParser.topLevelStringFields(cacheJson, setOf("accountUuid"))["accountUuid"]
            ?.takeIf { it.isNotBlank() } ?: return null
        val currentAccount = MetadataJsonParser.rawTopLevelField(json, "oauthAccount")?.trim()
            ?.takeIf { it.startsWith("{") }
            ?.let { MetadataJsonParser.topLevelStringFields(it, setOf("accountUuid"))["accountUuid"] }
            ?.takeIf { it.isNotBlank() } ?: return null
        if (cachedAccount != currentAccount) return null

        val fetchedAt = MetadataJsonParser.topLevelLongFields(cacheJson, setOf("fetchedAtMs"))["fetchedAtMs"]
            ?.takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: return null
        val age = Duration.between(fetchedAt, now)
        if (age > MAX_AGE) return null

        val body = MetadataJsonParser.rawTopLevelField(cacheJson, "utilization")?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val windows = ClaudeUsageBody.windows(body).filter { window ->
            val reset = window.resetsAt
            reset != null && reset.isAfter(now)
        }
        if (windows.isEmpty()) return null

        val fresh = !age.isNegative && age <= FRESH_AGE
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = if (fresh) UsageStatus.OK else UsageStatus.STALE,
            sourceKind = sourceKind,
            observedAt = fetchedAt,
            windows = windows,
            statusNote = "Last usage read cached by Claude Code (${describeAge(age)} ago).",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    private fun describeAge(age: Duration): String = when {
        age.isNegative || age.toMinutes() < 1 -> "under a minute"
        age.toMinutes() < 60 -> "${age.toMinutes()} min"
        age.toHours() < 48 -> "${age.toHours()} h"
        else -> "${age.toDays()} days"
    }

    companion object {
        private const val AGENT_ID = "claude"
        private const val MAX_CONFIG_CHARS = 8 * 1024 * 1024
        private val FRESH_AGE: Duration = Duration.ofMinutes(15)
        private val MAX_AGE: Duration = Duration.ofDays(7)

        /** `~/.claude.json`, or `<CLAUDE_CONFIG_DIR>/.claude.json` when the config directory is relocated. */
        private fun defaultConfigFile(): Path {
            val home = AgentRuntime.userHome()
            val configDir = EnvHomeDirectorySupport.resolve("CLAUDE_CONFIG_DIR", ".claude")
            return if (configDir == home.resolve(".claude")) home.resolve(".claude.json") else configDir.resolve(".claude.json")
        }
    }
}
