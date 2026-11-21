package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.AgentDetector
import com.shutterstar.agenthub.AgentRuntime
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * Live quota provider for GitHub Copilot CLI.
 * Resolves the user's GitHub OAuth token in memory only (env var → `gh auth token` → local Copilot
 * hosts/apps config) and queries `https://api.github.com/copilot_internal/user`.
 */
internal class CopilotLiveUsageProvider(
    private val homeDirectory: Path = AgentRuntime.userHome(),
    private val envLookup: (String) -> String? = { key -> if (AgentRuntime.isWsl()) null else System.getenv(key) },
    private val shellOutput: (String) -> String = { cmd -> AgentDetector.shellOutput(cmd, timeoutSeconds = 6) },
    private val httpGet: (String, Map<String, String>) -> UsageHttpResponse = UsageHttpClient::get,
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.LIVE_API
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot {
        val token = resolveToken()
            ?: return authRequired("Sign in with GitHub CLI (`gh auth login`) or Copilot CLI to view live quotas.")

        val response = httpGet(
            USAGE_ENDPOINT,
            mapOf(
                "Authorization" to "token $token",
                "Accept" to "application/json",
                "User-Agent" to USER_AGENT,
                "Editor-Version" to EDITOR_VERSION,
            ),
        )
        return when (response.statusCode) {
            200 -> parseUsageResponse(response.body, now)
            401, 403 -> authRequired("GitHub token is missing Copilot access or expired. Run `gh auth login` or `copilot login`.")
            429 -> AgentUsageSnapshot(
                agentId = AGENT_ID,
                status = UsageStatus.RATE_LIMITED,
                sourceKind = sourceKind,
                observedAt = now,
                statusNote = "GitHub Copilot quota endpoint rate-limited the request. Wait a moment and refresh.",
                billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
            )
            else -> throw IllegalStateException("GitHub Copilot quota API returned HTTP ${response.statusCode}")
        }
    }

    internal fun parseUsageResponse(json: String, now: Instant): AgentUsageSnapshot {
        val top = MetadataJsonParser.topLevelStringFields(
            json,
            setOf("copilot_plan", "access_type_sku", "quota_reset_date_utc", "quota_reset_date"),
        )
        val planLabel = formatPlanLabel(top["access_type_sku"], top["copilot_plan"])
        val resetsAt = top["quota_reset_date_utc"]?.let(LocalSessionSupport::parseTimestamp)
            ?: top["quota_reset_date"]?.let(::parseDateStartOfDayUtc)

        val snapshotsJson = MetadataJsonParser.rawTopLevelField(json, "quota_snapshots")?.trim()
            ?.takeIf { it.startsWith("{") }
            ?: throw IllegalStateException("GitHub Copilot response did not include quota_snapshots")

        val windows = mutableListOf<UsageWindow>()
        parseQuotaSnapshot(snapshotsJson, "premium_interactions", "Premium requests", "requests", resetsAt)?.let(windows::add)
        parseQuotaSnapshot(snapshotsJson, "chat", "Chat messages", "messages", resetsAt)?.let(windows::add)
        parseQuotaSnapshot(snapshotsJson, "completions", "Code completions", "completions", resetsAt)?.let(windows::add)

        if (windows.isEmpty()) {
            throw IllegalStateException("GitHub Copilot response had no active quota buckets")
        }

        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            planLabel = planLabel,
            observedAt = now,
            windows = windows,
            statusNote = "Live monthly quota from GitHub Copilot.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    private fun parseQuotaSnapshot(
        snapshotsJson: String,
        key: String,
        label: String,
        unit: String,
        resetsAt: Instant?,
    ): UsageWindow? {
        val raw = MetadataJsonParser.rawTopLevelField(snapshotsJson, key)?.trim() ?: return null
        if (!raw.startsWith("{")) return null
        val booleans = MetadataJsonParser.topLevelBooleanFields(raw, setOf("unlimited", "has_quota"))
        val unlimited = booleans["unlimited"] == true
        val hasQuota = booleans["has_quota"]
        val entitlement = parseDouble(raw, "entitlement")?.takeIf { it.isFinite() && it >= 0.0 }
        val remaining = parseDouble(raw, "remaining")?.takeIf { it.isFinite() && it >= 0.0 }
            ?: parseDouble(raw, "quota_remaining")?.takeIf { it.isFinite() && it >= 0.0 }
        val percentRemaining = parseDouble(raw, "percent_remaining")?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)

        // Free plans return `has_quota: false, entitlement: 0` for premium_interactions; skip unavailable buckets
        // so they never appear as a false "100% used" bar.
        if (!unlimited && (hasQuota == false || (entitlement != null && entitlement <= 0.0))) {
            return null
        }

        val usedAmount = if (!unlimited && entitlement != null && remaining != null) {
            (entitlement - remaining).coerceAtLeast(0.0)
        } else {
            null
        }
        val usedPercent = when {
            unlimited -> null
            percentRemaining != null -> (100.0 - percentRemaining).coerceIn(0.0, 100.0)
            usedAmount != null && entitlement != null && entitlement > 0.0 -> (usedAmount / entitlement * 100.0).coerceIn(0.0, 100.0)
            else -> null
        }
        if (!unlimited && usedPercent == null) return null

        return UsageWindow(
            id = key,
            label = label,
            kind = UsageWindowKind.MONTHLY,
            usedPercent = usedPercent,
            usedAmount = usedAmount,
            limitAmount = if (unlimited) null else entitlement,
            unit = if (unlimited) null else unit,
            resetsAt = resetsAt,
            unlimited = unlimited,
        )
    }

    private fun resolveToken(): String? {
        for (envVar in TOKEN_ENV_VARS) {
            val value = envLookup(envVar)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        val ghToken = runCatching { shellOutput("gh auth token") }.getOrDefault("").lineSequence().firstOrNull()?.trim()
        if (!ghToken.isNullOrEmpty() && !ghToken.contains(' ')) return ghToken

        val copilotConfigDir = homeDirectory.resolve(".config").resolve("github-copilot")
        for (fileName in listOf("hosts.json", "apps.json")) {
            val token = readCopilotConfigToken(copilotConfigDir.resolve(fileName))
            if (token != null) return token
        }
        return null
    }

    private fun readCopilotConfigToken(file: Path): String? {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        val text = runCatching { LocalSessionSupport.readBoundedText(file, MAX_CONFIG_CHARS) }.getOrNull() ?: return null
        val match = OAUTH_TOKEN_REGEX.find(text) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun parseDateStartOfDayUtc(raw: String): Instant? = runCatching {
        LocalDate.parse(raw.trim()).atStartOfDay(ZoneOffset.UTC).toInstant()
    }.getOrNull()

    private fun parseDouble(json: String, field: String): Double? {
        val raw = MetadataJsonParser.rawTopLevelField(json, field)?.trim() ?: return null
        if (raw == "null" || raw.isEmpty()) return null
        val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
        return unquoted.toDoubleOrNull()
    }

    private fun formatPlanLabel(sku: String?, plan: String?): String? {
        val skuLower = sku?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val planLower = plan?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "free" in skuLower -> "Free"
            "pro_plus" in skuLower || planLower == "individual_pro" -> "Pro+"
            "business" in skuLower || planLower == "business" -> "Business"
            "enterprise" in skuLower || planLower == "enterprise" -> "Enterprise"
            planLower == "individual" || "individual" in skuLower -> "Pro"
            planLower.isNotEmpty() -> planLower.replaceFirstChar { it.titlecase(Locale.ROOT) }
            else -> null
        }
    }

    private fun authRequired(note: String): AgentUsageSnapshot = AgentUsageSnapshot(
        agentId = AGENT_ID,
        status = UsageStatus.AUTH_REQUIRED,
        sourceKind = sourceKind,
        statusNote = note,
        billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
    )

    companion object {
        private const val AGENT_ID = "copilot"
        private const val USAGE_ENDPOINT = "https://api.github.com/copilot_internal/user"
        private const val USER_AGENT = "GitHubCopilotChat/0.26.7"
        private const val EDITOR_VERSION = "vscode/1.99.0"
        private const val MAX_CONFIG_CHARS = 128 * 1024
        private val TOKEN_ENV_VARS = listOf("COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN")
        private val OAUTH_TOKEN_REGEX = Regex(""""oauth_token"\s*:\s*"([^"]+)"""")
    }
}
