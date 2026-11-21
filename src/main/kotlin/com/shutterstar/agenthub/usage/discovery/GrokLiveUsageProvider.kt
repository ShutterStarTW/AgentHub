package com.shutterstar.agenthub.usage.discovery

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
import kotlin.math.roundToInt

/**
 * Live quota provider for Grok Build. Reads the `key` (access token) of the CLI's own
 * sign-in in `~/.grok/auth.json` in memory only (never stored, logged, refreshed or written back:
 * refreshing would rotate the CLI's refresh token) and queries the billing endpoint of
 * `cli-chat-proxy.grok.com`, the one the Grok Build TUI uses.
 *
 * The access token is short-lived and only the CLI refreshes it, so an expired token is reported as
 * [UsageStatus.AUTH_REQUIRED] ("start `grok` once, or sign in").
 */
internal class GrokLiveUsageProvider(
    private val grokDirectory: Path = EnvHomeDirectorySupport.resolve("GROK_HOME", ".grok"),
    private val httpGet: (String, Map<String, String>) -> UsageHttpResponse = UsageHttpClient::get,
) : AgentUsageProvider {
    override val agentId: String = AGENT_ID
    override val sourceKind: UsageSourceKind = UsageSourceKind.LIVE_API
    override val requiresNetwork: Boolean = true

    override fun isAvailable(): Boolean = true

    override fun discover(now: Instant): AgentUsageSnapshot {
        val credential = readCredential()
            ?: return authRequired(now, "Grok Build is not signed in. Sign in to see the live usage limits.")
        if (credential.expiresAt != null && credential.expiresAt <= now.plusSeconds(EXPIRY_SKEW_SECONDS)) {
            return authRequired(now, "The Grok sign-in has expired. Start `grok` once to refresh it, or sign in again.")
        }
        val headers = mapOf("Authorization" to "Bearer ${credential.token}", "Accept" to "application/json")
        val response = httpGet("$BILLING_ORIGIN/v1/billing?format=credits", headers)
        return when (response.statusCode) {
            200 -> parseBilling(response.body, now).copy(planLabel = fetchPlanLabel(headers))
            401, 403 -> authRequired(now, "Grok rejected the CLI sign-in. Sign in again to see the live usage limits.")
            429 -> AgentUsageSnapshot(
                agentId = AGENT_ID,
                status = UsageStatus.RATE_LIMITED,
                sourceKind = sourceKind,
                observedAt = now,
                statusNote = "Grok billing endpoint rate-limited the request. Wait a moment and refresh.",
                billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
            )
            else -> throw IllegalStateException("Grok billing API returned HTTP ${response.statusCode}")
        }
    }

    internal fun parseBilling(json: String, now: Instant): AgentUsageSnapshot {
        val config = MetadataJsonParser.rawTopLevelField(json, "config")?.trim()?.takeIf { it.startsWith("{") }
            ?: throw IllegalStateException("Grok billing response did not contain a config block")
        val period = MetadataJsonParser.rawTopLevelField(config, "currentPeriod")?.trim()?.takeIf { it.startsWith("{") }
        val periodFields = period?.let { MetadataJsonParser.topLevelStringFields(it, setOf("type", "start", "end")) }.orEmpty()
        val start = (periodFields["start"] ?: MetadataJsonParser.topLevelStringFields(config, setOf("billingPeriodStart"))["billingPeriodStart"])
            ?.let(LocalSessionSupport::parseTimestamp)
        val end = (periodFields["end"] ?: MetadataJsonParser.topLevelStringFields(config, setOf("billingPeriodEnd"))["billingPeriodEnd"])
            ?.let(LocalSessionSupport::parseTimestamp)
        val minutes = if (start != null && end != null && end.isAfter(start)) Duration.between(start, end).toMinutes().toInt() else null
        val (kind, label) = periodKind(periodFields["type"], minutes)

        val products = productShares(config)
        // Unified billing omits the percent (and productUsage) while nothing was used this period.
        val reported = number(MetadataJsonParser.rawTopLevelField(config, "creditUsagePercent"))
            ?: products.takeIf { it.isNotEmpty() }?.sumOf { it.second }
        val used = reported
            ?: 0.0.takeIf { period != null && (start != null || end != null) }
            ?: throw IllegalStateException("Grok billing response did not contain recognizable quota windows")

        return AgentUsageSnapshot(
            agentId = AGENT_ID,
            status = UsageStatus.OK,
            sourceKind = sourceKind,
            observedAt = now,
            windows = listOf(
                UsageWindow(label.lowercase(Locale.ROOT), label, kind, used.coerceIn(0.0, 100.0), windowMinutes = minutes, resetsAt = end),
            ),
            statusNote = "Live account usage from Grok Build." + productNote(products),
            billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
        )
    }

    /** Per-product shares of the same pool (a 12% Grok Bot row is 12 points of the cap), unused rows omitted. */
    private fun productShares(config: String): List<Pair<String, Double>> {
        val array = MetadataJsonParser.rawTopLevelField(config, "productUsage")?.trim()?.takeIf { it.startsWith("[") } ?: return emptyList()
        return HeadlessCommandSupport.splitJsonObjects(array).mapNotNull { entry ->
            val product = MetadataJsonParser.topLevelStringFields(entry, setOf("product"))["product"]?.trim()
                ?.takeIf { PRODUCT_ID.matches(it) } ?: return@mapNotNull null
            val share = number(MetadataJsonParser.rawTopLevelField(entry, "usagePercent"))?.takeIf { it > 0.0 } ?: return@mapNotNull null
            (PRODUCT_LABELS[product] ?: product) to share.coerceAtMost(100.0)
        }.distinctBy { it.first }.take(MAX_PRODUCTS)
    }

    private fun productNote(products: List<Pair<String, Double>>): String =
        if (products.isEmpty()) "" else " " + products.joinToString(", ") { "${it.first} ${if (it.second < 0.5) "<1" else it.second.roundToInt().toString()}%" } + " of the pool."

    private fun periodKind(type: String?, minutes: Int?): Pair<UsageWindowKind, String> {
        val upper = type?.uppercase(Locale.ROOT).orEmpty()
        return when {
            "WEEK" in upper -> UsageWindowKind.WEEKLY to "Weekly"
            "MONTH" in upper -> UsageWindowKind.MONTHLY to "Monthly"
            "DAILY" in upper || upper.endsWith("_DAY") -> UsageWindowKind.DAILY to "Daily"
            minutes != null && minutes in 1_500..11_520 -> UsageWindowKind.WEEKLY to "Weekly"
            minutes != null && minutes in 36_000..50_400 -> UsageWindowKind.MONTHLY to "Monthly"
            minutes != null && minutes in 720..2_160 -> UsageWindowKind.DAILY to "Daily"
            else -> UsageWindowKind.CUSTOM to "Usage"
        }
    }

    /** `{"val": 12.5}` wrappers and bare numbers. */
    private fun number(raw: String?): Double? {
        val text = raw?.trim() ?: return null
        val value = if (text.startsWith("{")) MetadataJsonParser.rawTopLevelField(text, "val")?.trim()?.trim('"') else text.trim('"')
        return value?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun fetchPlanLabel(headers: Map<String, String>): String? = runCatching {
        val response = httpGet("$BILLING_ORIGIN/v1/settings", headers)
        if (response.statusCode != 200) return@runCatching null
        val fields = MetadataJsonParser.topLevelStringFields(response.body, setOf("subscription_tier_display", "subscription_tier"))
        // Exact match only: an unseen tier is better left unnamed than named wrongly.
        PLAN_TIERS[(fields["subscription_tier_display"] ?: fields["subscription_tier"])?.lowercase(Locale.ROOT)?.filter { it.isLetterOrDigit() }]
    }.getOrNull()

    private fun authRequired(now: Instant, note: String) = AgentUsageSnapshot(
        agentId = AGENT_ID,
        status = UsageStatus.AUTH_REQUIRED,
        sourceKind = sourceKind,
        observedAt = now,
        statusNote = note,
        billingUrl = UsageSourceCatalog.billingUrl(AGENT_ID),
    )

    private class Credential(val token: String, val expiresAt: Instant?)

    private fun readCredential(): Credential? {
        val file = grokDirectory.resolve("auth.json")
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        val raw = runCatching { LocalSessionSupport.readBoundedText(file, MAX_AUTH_CHARS) }.getOrNull() ?: return null
        val inner = raw.trim().removePrefix("{").removeSuffix("}")
        return HeadlessCommandSupport.splitJsonObjects(inner).firstNotNullOfOrNull { entry ->
            val fields = MetadataJsonParser.topLevelStringFields(entry, setOf("key", "expires_at"))
            val token = fields["key"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@firstNotNullOfOrNull null
            Credential(token, fields["expires_at"]?.let(LocalSessionSupport::parseTimestamp))
        }
    }

    companion object {
        private const val AGENT_ID = "grok"
        private const val BILLING_ORIGIN = "https://cli-chat-proxy.grok.com"
        private const val MAX_AUTH_CHARS = 32 * 1024
        private const val EXPIRY_SKEW_SECONDS = 60L
        private const val MAX_PRODUCTS = 6
        private val PRODUCT_ID = Regex("[A-Za-z][A-Za-z0-9]{0,31}")
        private val PRODUCT_LABELS = mapOf(
            "GrokBuild" to "Grok Build", "GrokBot" to "Grok Bot", "GrokChat" to "Chat",
            "GrokImagine" to "Imagine", "GrokVoice" to "Voice", "Cursor" to "Cursor",
        )
        private val PLAN_TIERS = mapOf(
            "supergrokheavy" to "SuperGrok Heavy", "supergrokplus" to "SuperGrok Plus", "supergroklite" to "SuperGrok Lite",
            "supergrok" to "SuperGrok", "xpremiumplus" to "X Premium+", "premiumplus" to "X Premium+",
            "xpremium" to "X Premium+", "apikey" to "API Key", "free" to "Free",
        )
    }
}
