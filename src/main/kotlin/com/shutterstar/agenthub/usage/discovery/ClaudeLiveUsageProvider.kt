package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.AgentDetector
import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.OsDetector
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
import java.time.Instant
import java.util.Locale

/**
 * Live quota provider for Claude Code.
 * Reads the existing local Claude CLI OAuth access token in memory only (never stored or logged)
 * and queries `https://api.anthropic.com/api/oauth/usage`.
 */
internal class ClaudeLiveUsageProvider(
    private val claudeDirectory: Path = EnvHomeDirectorySupport.resolve("CLAUDE_CONFIG_DIR", ".claude"),
    private val httpGet: (String, Map<String, String>) -> UsageHttpResponse = UsageHttpClient::get,
    private val keychainCredentialsReader: () -> String? = ::defaultMacKeychainCredentials,
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.LIVE_API
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot {
        val credentials = readCredentials()
            ?: return authRequired("Sign in with Claude Code (`claude`) to view live usage limits.")
        val token = credentials.accessToken
            ?: return authRequired("No Claude OAuth session found. Run `claude` to sign in.")

        val response = httpGet(
            USAGE_ENDPOINT,
            mapOf(
                "Authorization" to "Bearer $token",
                "anthropic-beta" to OAUTH_BETA_HEADER,
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json",
            ),
        )
        return when (response.statusCode) {
            200 -> parseUsageResponse(response.body, credentials.planLabel, now)
            401, 403 -> authRequired("Claude OAuth session expired or unauthorized. Run `claude` to refresh sign-in.")
            429 -> AgentUsageSnapshot(
                agentId = AGENT_ID,
                status = UsageStatus.RATE_LIMITED,
                sourceKind = sourceKind,
                planLabel = credentials.planLabel,
                observedAt = now,
                statusNote = "Anthropic usage endpoint rate-limited the request. Wait a moment and refresh.",
                billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
            )
            else -> throw IllegalStateException("Anthropic usage API returned HTTP ${response.statusCode}")
        }
    }

    internal fun parseUsageResponse(json: String, planLabel: String?, now: Instant): AgentUsageSnapshot {
        val windows = ClaudeUsageBody.windows(json)
        if (windows.isEmpty()) {
            throw IllegalStateException("Anthropic usage response did not contain recognizable quota windows")
        }
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            planLabel = planLabel,
            observedAt = now,
            windows = windows,
            statusNote = "Live account usage from Anthropic.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    private fun readCredentials(): ClaudeCredentials? {
        val file = claudeDirectory.resolve(CREDENTIALS_FILE)
        val rawJson = if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { LocalSessionSupport.readBoundedText(file, MAX_CREDENTIAL_CHARS) }.getOrNull()
        } else {
            keychainCredentialsReader()
        } ?: return null

        val oauthJson = MetadataJsonParser.rawTopLevelField(rawJson, "claudeAiOauth")?.trim()
            ?.takeIf { it.startsWith("{") }
            ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(oauthJson, setOf("accessToken", "subscriptionType"))
        val token = fields["accessToken"]?.trim()?.takeIf { it.isNotEmpty() }
        val plan = formatSubscriptionType(fields["subscriptionType"])
        return ClaudeCredentials(accessToken = token, planLabel = plan)
    }

    private fun formatSubscriptionType(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it.length <= 32 } ?: return null
        return when (cleaned) {
            "pro" -> "Pro"
            "max" -> "Max"
            "team" -> "Team"
            "enterprise" -> "Enterprise"
            "free" -> "Free"
            else -> cleaned.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    private fun authRequired(note: String): AgentUsageSnapshot = AgentUsageSnapshot(
        agentId = AGENT_ID,
        status = UsageStatus.AUTH_REQUIRED,
        sourceKind = sourceKind,
        statusNote = note,
        billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
    )

    private data class ClaudeCredentials(
        val accessToken: String?,
        val planLabel: String?,
    )

    companion object {
        private const val AGENT_ID = "claude"
        private const val CREDENTIALS_FILE = ".credentials.json"
        private const val MAX_CREDENTIAL_CHARS = 256 * 1024
        private const val USAGE_ENDPOINT = "https://api.anthropic.com/api/oauth/usage"
        private const val OAUTH_BETA_HEADER = "oauth-2025-04-20"
        private const val USER_AGENT = "claude-code/2.1"

        private fun defaultMacKeychainCredentials(): String? {
            if (!OsDetector.isMac() || AgentRuntime.isWsl()) return null
            val out = AgentDetector.shellOutput("security find-generic-password -s 'Claude Code-credentials' -w", timeoutSeconds = 5)
            return out.trim().takeIf { it.startsWith("{") }
        }
    }
}
