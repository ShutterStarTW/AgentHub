package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport
import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import com.shutterstar.agenthub.usage.model.AgentUsageSnapshot
import com.shutterstar.agenthub.usage.model.UsageSourceCatalog
import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindow
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.Locale

/**
 * Live quota provider for Cursor CLI. Reads the `accessToken` of the CLI's own sign-in
 * (`Cursor/auth.json`) in memory only (never stored, logged or refreshed) and queries
 * `https://cursor.com/api/usage-summary`, the endpoint behind the Cursor dashboard.
 *
 * The session cookie is `WorkosCursorSessionToken=<userId>::<accessToken>` (URL-encoded), where the
 * user id is the part of the token's `sub` claim after `|`. An expired token is reported as
 * [UsageStatus.AUTH_REQUIRED] so the UI offers `cursor-agent login`.
 */
internal class CursorLiveUsageProvider(
    private val homeDirectory: () -> Path = AgentRuntime::userHome,
    private val authFileOverride: Path? = null,
    private val httpGet: (String, Map<String, String>) -> UsageHttpResponse = UsageHttpClient::get,
    private val httpPostProto: (String, Map<String, String>) -> UsageHttpBytes = UsageHttpClient::postEmpty,
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.LIVE_API
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot {
        val token = readAccessToken()
            ?: return authRequired("Cursor CLI is not signed in. Sign in to see the live usage limits.")
        val claims = decodeClaims(token)
            ?: return authRequired("The Cursor CLI sign-in could not be read. Sign in again to see the live usage limits.")
        if (claims.expiresAt != null && claims.expiresAt <= now) {
            return authRequired("The Cursor CLI sign-in has expired. Sign in again to see the live usage limits.")
        }
        val cookie = "WorkosCursorSessionToken=" + URLEncoder.encode("${claims.userId}::$token", StandardCharsets.UTF_8)
        val response = httpGet(
            USAGE_ENDPOINT,
            mapOf("Accept" to "application/json", "Cookie" to cookie, "User-Agent" to USER_AGENT),
        )
        return when (response.statusCode) {
            200 -> parseUsageSummary(response.body, now).withGrokBot(token)
            401, 403 -> authRequired("Cursor rejected the CLI sign-in. Sign in again to see the live usage limits.")
            429 -> AgentUsageSnapshot(
                agentId = AGENT_ID,
                status = UsageStatus.RATE_LIMITED,
                sourceKind = sourceKind,
                observedAt = now,
                statusNote = "Cursor usage endpoint rate-limited the request. Wait a moment and refresh.",
                billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
            )
            else -> throw IllegalStateException("Cursor usage API returned HTTP ${response.statusCode}")
        }
    }

    internal fun parseUsageSummary(json: String, now: Instant): AgentUsageSnapshot {
        val top = MetadataJsonParser.topLevelStringFields(json, setOf("membershipType", "billingCycleEnd"))
        val membership = top["membershipType"]?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.length in 1..32 }
        val resetsAt = top["billingCycleEnd"]?.let(LocalSessionSupport::parseTimestamp)
        val individual = MetadataJsonParser.rawTopLevelField(json, "individualUsage")?.trim()?.takeIf { it.startsWith("{") }
        val plan = individual?.let { MetadataJsonParser.rawTopLevelField(it, "plan") }?.trim()?.takeIf { it.startsWith("{") }
        if (plan == null || MetadataJsonParser.rawTopLevelField(plan, "enabled")?.trim() == "false") {
            throw IllegalStateException("Cursor usage response did not contain a plan usage block")
        }
        val cycleStart = MetadataJsonParser.topLevelStringFields(json, setOf("billingCycleStart"))["billingCycleStart"]
            ?.let(LocalSessionSupport::parseTimestamp)
        val cycleMinutes = if (cycleStart != null && resetsAt != null && resetsAt.isAfter(cycleStart)) {
            java.time.Duration.between(cycleStart, resetsAt).toMinutes().toInt()
        } else {
            null
        }
        val windows = if (membership?.startsWith("unified_") == true) {
            // X Subscription: one weekly pool shared by Cursor models; its reset is not in the summary.
            listOfNotNull(percent(plan, "totalPercentUsed")?.let {
                UsageWindow("weekly_pool", "Cursor Models (weekly)", UsageWindowKind.WEEKLY, it, windowMinutes = 10_080)
            })
        } else {
            fun monthly(id: String, label: String, used: Double?) =
                used?.let { UsageWindow(id, label, UsageWindowKind.MONTHLY, it, windowMinutes = cycleMinutes, resetsAt = resetsAt) }
            listOfNotNull(
                monthly("plan", "Plan", percent(plan, "totalPercentUsed")),
                monthly("auto", "Auto (Cursor models)", percent(plan, "autoPercentUsed")),
                monthly("api", "API (other models)", percent(plan, "apiPercentUsed")),
            )
        }
        if (windows.isEmpty()) {
            throw IllegalStateException("Cursor usage response did not contain recognizable quota windows")
        }
        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            planLabel = membership?.removePrefix("unified_")?.replaceFirstChar { it.titlecase(Locale.ROOT) },
            observedAt = now,
            windows = windows,
            statusNote = spendNote(plan) ?: "Live account usage from Cursor.",
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    /** "Spend $167.33 (included $20.00 + bonus $147.33)" from the plan's `breakdown` (USD cents). */
    private fun spendNote(plan: String): String? {
        val breakdown = MetadataJsonParser.rawTopLevelField(plan, "breakdown")?.trim()?.takeIf { it.startsWith("{") } ?: return null
        fun cents(field: String) = MetadataJsonParser.rawTopLevelField(breakdown, field)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        val total = cents("total") ?: return null
        val included = cents("included") ?: return null
        val bonus = cents("bonus") ?: 0L
        fun usd(value: Long) = String.format(Locale.ROOT, "$%.2f", value / 100.0)
        return "Spend ${usd(total)} (included ${usd(included)}" + (if (bonus > 0) " + bonus ${usd(bonus)})" else ")")
    }

    /**
     * Adds the weekly Grok Bot window ("Sand" RPCs of the dashboard service). Both RPCs are
     * independent of the usage summary, so any failure only drops this window.
     */
    private fun AgentUsageSnapshot.withGrokBot(token: String): AgentUsageSnapshot {
        val window = runCatching { grokBotWindow(token) }.getOrNull() ?: return this
        return copy(windows = windows + window)
    }

    private fun grokBotWindow(token: String): UsageWindow? {
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/proto",
            "Connect-Protocol-Version" to "1",
        )
        val access = httpPostProto("$RPC_ORIGIN/GetSandAccessStatus", headers).takeIf(::isProto) ?: return null
        val granted = ProtoWire.readFields(access.body)?.firstOrNull { it.number == 1 && it.wireType == 0 }?.varint == 1L
        if (!granted) return null
        val usage = httpPostProto("$RPC_ORIGIN/GetSandUsageStatus", headers).takeIf(::isProto) ?: return null
        val fields = ProtoWire.readFields(usage.body) ?: return null
        fun field(number: Int) = fields.firstOrNull { it.number == number }
        if (field(4)?.varint?.let { it != 0L } == true) return null
        val used = field(3)?.let(ProtoWire::doubleValue)?.coerceIn(0.0, 100.0) ?: return null
        val resetsAt = field(2)?.let(ProtoWire::timestamp) ?: return null
        val start = field(1)?.let(ProtoWire::timestamp)
        val minutes = start?.takeIf { resetsAt.isAfter(it) }?.let { java.time.Duration.between(it, resetsAt).toMinutes().toInt() } ?: 10_080
        return UsageWindow("grok_bot", "Grok Bot (weekly)", UsageWindowKind.WEEKLY, used, windowMinutes = minutes, resetsAt = resetsAt)
    }

    private fun isProto(response: UsageHttpBytes) =
        response.statusCode == 200 && response.contentType.lowercase(Locale.ROOT).startsWith("application/proto")

    private fun percent(plan: String, field: String): Double? =
        MetadataJsonParser.rawTopLevelField(plan, field)?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(100.0)

    private fun authRequired(note: String) = AgentUsageSnapshot(
        agentId = AGENT_ID,
        status = UsageStatus.AUTH_REQUIRED,
        sourceKind = sourceKind,
        observedAt = Instant.now(),
        statusNote = note,
        billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
    )

    private fun readAccessToken(): String? {
        val home = homeDirectory()
        val candidates = authFileOverride?.let(::listOf) ?: AUTH_FILE_CANDIDATES.map { home.resolve(it) }
        val file = candidates.firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) } ?: return null
        val raw = runCatching { LocalSessionSupport.readBoundedText(file, MAX_AUTH_CHARS) }.getOrNull() ?: return null
        return MetadataJsonParser.topLevelStringFields(raw, setOf("accessToken"))["accessToken"]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private data class Claims(val userId: String, val expiresAt: Instant?)

    private fun decodeClaims(token: String): Claims? {
        val payload = token.split('.').getOrNull(1) ?: return null
        val json = runCatching { String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val sub = MetadataJsonParser.topLevelStringFields(json, setOf("sub"))["sub"]?.trim() ?: return null
        // The id goes into a Cookie header: only a plain identifier is accepted.
        val userId = sub.substringAfterLast('|').takeIf { it.isNotEmpty() && it.length <= 128 && it.all { ch -> ch.isLetterOrDigit() || ch in "-_" } }
            ?: return null
        val exp = MetadataJsonParser.rawTopLevelField(json, "exp")?.trim()?.toLongOrNull()?.let(Instant::ofEpochSecond)
        return Claims(userId, exp)
    }

    companion object {
        private const val AGENT_ID = "cursor"
        private const val USAGE_ENDPOINT = "https://cursor.com/api/usage-summary"
        private const val RPC_ORIGIN = "https://api2.cursor.sh/aiserver.v1.DashboardService"
        private const val USER_AGENT = "AgentHub-Usage/1.0"
        private const val MAX_AUTH_CHARS = 16 * 1024

        // Windows (verified), macOS and Linux locations of the Cursor CLI sign-in, relative to the home directory.
        private val AUTH_FILE_CANDIDATES = listOf(
            "AppData/Roaming/Cursor/auth.json",
            "Library/Application Support/Cursor/auth.json",
            ".config/Cursor/auth.json",
        )
    }
}
