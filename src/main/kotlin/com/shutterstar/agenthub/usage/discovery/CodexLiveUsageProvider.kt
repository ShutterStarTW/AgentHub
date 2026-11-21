package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport
import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageResetCredits
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

/**
 * Live quota provider for Codex CLI.
 * Reads the existing `$CODEX_HOME/auth.json` OAuth token in memory only (never stored or logged)
 * and queries `https://chatgpt.com/backend-api/wham/usage`.
 */
internal class CodexLiveUsageProvider(
    private val codexDirectory: Path = EnvHomeDirectorySupport.resolve("CODEX_HOME", ".codex"),
    private val httpGet: (String, Map<String, String>) -> UsageHttpResponse = UsageHttpClient::get,
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.LIVE_API
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = Files.isRegularFile(codexDirectory.resolve(AUTH_FILE), LinkOption.NOFOLLOW_LINKS)

    override fun discover(now: Instant): AgentUsageSnapshot {
        val auth = readAuth()
            ?: return authRequired("Sign in with Codex CLI (`codex login`) to view live usage limits.")
        if (auth.accessToken == null) {
            if (auth.authMode.equals("api_key", ignoreCase = true) || auth.hasApiKey) {
                return AgentUsageSnapshot(
                    agentId = AGENT_ID,
                    status = UsageStatus.LOCAL_ONLY,
                    sourceKind = UsageSourceKind.LOCAL_SESSIONS,
                    planLabel = "API Key",
                    observedAt = now,
                    statusNote = "Codex is using an OpenAI API key (pay-per-token); subscription rate-limit windows do not apply.",
                    billingUrl = UsageSourceCatalog.API_USAGE_URL,
                )
            }
            return authRequired("Sign in with ChatGPT in Codex CLI (`codex login`) to view live usage limits.")
        }

        val headers = linkedMapOf(
            "Authorization" to "Bearer ${auth.accessToken}",
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
        )
        auth.accountId?.let { headers["ChatGPT-Account-Id"] = it }

        val response = httpGet(USAGE_ENDPOINT, headers)
        return when (response.statusCode) {
            200 -> parseUsageResponse(response.body, now)
            401, 403 -> authRequired("Codex session expired or unauthorized. Run `codex login` to refresh sign-in.")
            429 -> AgentUsageSnapshot(
                agentId = AGENT_ID,
                status = UsageStatus.RATE_LIMITED,
                sourceKind = sourceKind,
                observedAt = now,
                statusNote = "Codex usage endpoint rate-limited the request. Wait a moment and refresh.",
                billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
            )
            else -> throw IllegalStateException("Codex usage API returned HTTP ${response.statusCode}")
        }
    }

    internal fun parseUsageResponse(json: String, now: Instant): AgentUsageSnapshot {
        val planLabel = CodexUsageFormat.planLabel(MetadataJsonParser.topLevelStringFields(json, setOf("plan_type"))["plan_type"])
        val windows = mutableListOf<UsageWindow>()

        MetadataJsonParser.rawTopLevelField(json, "rate_limit")?.trim()
            ?.takeIf { it.startsWith("{") }
            ?.let { rateLimitJson ->
                parseWindow(rateLimitJson, "primary_window", "primary", defaultSeconds = 18_000)?.let(windows::add)
                parseWindow(rateLimitJson, "secondary_window", "secondary", defaultSeconds = 604_800)?.let(windows::add)
            }

        MetadataJsonParser.rawTopLevelField(json, "code_review_rate_limit")?.trim()
            ?.takeIf { it.startsWith("{") }
            ?.let { reviewJson ->
                parseWindow(reviewJson, "primary_window", "code_review", defaultSeconds = 604_800, labelPrefix = "Code review")
                    ?.let(windows::add)
            }

        MetadataJsonParser.rawTopLevelField(json, "credits")?.trim()
            ?.takeIf { it.startsWith("{") }
            ?.let(::parseCreditsWindow)
            ?.let(windows::add)

        if (windows.isEmpty()) {
            throw IllegalStateException("Codex usage response did not contain recognizable quota windows")
        }

        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            planLabel = planLabel,
            observedAt = now,
            windows = windows,
            statusNote = "Live account usage from OpenAI Codex.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
            resetCredits = MetadataJsonParser.rawTopLevelField(json, "rate_limit_reset_credits")?.trim()
                ?.takeIf { it.startsWith("{") }
                ?.let { parseDouble(it, "available_count") }
                ?.toInt()?.coerceIn(0, 999)
                ?.let { UsageResetCredits(availableCount = it) },
        )
    }

    private fun parseWindow(
        containerJson: String,
        field: String,
        id: String,
        defaultSeconds: Long,
        labelPrefix: String? = null,
    ): UsageWindow? {
        val raw = MetadataJsonParser.rawTopLevelField(containerJson, field)?.trim() ?: return null
        if (!raw.startsWith("{")) return null
        val usedPercent = parseDouble(raw, "used_percent")?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(100.0)
            ?: return null
        val longs = MetadataJsonParser.topLevelLongFields(raw, setOf("limit_window_seconds", "reset_at"))
        val windowSeconds = longs["limit_window_seconds"]?.takeIf { it > 0L } ?: defaultSeconds
        val windowMinutes = (windowSeconds / 60L).toInt().coerceAtLeast(1)
        val resetsAt = longs["reset_at"]?.let(LocalSessionSupport::epochTimestamp)
        val baseLabel = CodexUsageFormat.windowLabel(windowMinutes, id)
        val label = if (labelPrefix != null) "$labelPrefix · $baseLabel" else baseLabel
        return UsageWindow(
            id = id,
            label = label,
            kind = CodexUsageFormat.windowKind(windowMinutes),
            usedPercent = usedPercent,
            windowMinutes = windowMinutes,
            resetsAt = resetsAt,
        )
    }

    private fun parseCreditsWindow(creditsJson: String): UsageWindow? {
        val booleans = MetadataJsonParser.topLevelBooleanFields(creditsJson, setOf("has_credits", "unlimited"))
        val unlimited = booleans["unlimited"] == true
        val hasCredits = booleans["has_credits"] == true
        val balance = parseDouble(creditsJson, "balance")?.takeIf { it.isFinite() && it > 0.0 }
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

    private fun readAuth(): CodexAuth? {
        val file = codexDirectory.resolve(AUTH_FILE)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        val rawJson = runCatching { LocalSessionSupport.readBoundedText(file, MAX_AUTH_CHARS) }.getOrNull() ?: return null
        val top = MetadataJsonParser.topLevelStringFields(rawJson, setOf("auth_mode", "OPENAI_API_KEY"))
        val tokensJson = MetadataJsonParser.rawTopLevelField(rawJson, "tokens")?.trim()?.takeIf { it.startsWith("{") }
        val tokenFields = tokensJson?.let { MetadataJsonParser.topLevelStringFields(it, setOf("access_token", "account_id")) }.orEmpty()
        return CodexAuth(
            authMode = top["auth_mode"],
            hasApiKey = !top["OPENAI_API_KEY"].isNullOrBlank(),
            accessToken = tokenFields["access_token"]?.trim()?.takeIf { it.isNotEmpty() },
            accountId = tokenFields["account_id"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseDouble(json: String, field: String): Double? {
        val raw = MetadataJsonParser.rawTopLevelField(json, field)?.trim() ?: return null
        if (raw == "null" || raw.isEmpty()) return null
        val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
        return unquoted.toDoubleOrNull()
    }

    private fun authRequired(note: String): AgentUsageSnapshot = AgentUsageSnapshot(
        agentId = AGENT_ID,
        status = UsageStatus.AUTH_REQUIRED,
        sourceKind = sourceKind,
        statusNote = note,
        billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
    )

    private data class CodexAuth(
        val authMode: String?,
        val hasApiKey: Boolean,
        val accessToken: String?,
        val accountId: String?,
    )

    companion object {
        private const val AGENT_ID = "codex"
        private const val AUTH_FILE = "auth.json"
        private const val MAX_AUTH_CHARS = 256 * 1024
        private const val USAGE_ENDPOINT = "https://chatgpt.com/backend-api/wham/usage"
        private const val USER_AGENT = "codex-cli"
    }
}
