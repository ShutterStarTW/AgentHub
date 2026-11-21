package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.usage.model.UsageSourceKind
import com.shutterstar.agenthub.usage.model.UsageStatus
import com.shutterstar.agenthub.usage.model.UsageWindowKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class LiveUsageProvidersTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-11-19T13:05:00Z")

    @Test
    fun shouldParseClaudeLiveOAuthResponseAndNeverExposeToken() {
        val claudeDir = Files.createDirectories(tempDir.resolve(".claude"))
        val secretToken = "sk-ant-oat01-super-secret-value"
        Files.writeString(
            claudeDir.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$secretToken","subscriptionType":"pro"}}""",
        )
        var capturedAuth: String? = null
        val body = """
            {
              "five_hour": {"utilization": 20.0, "resets_at": "2026-11-19T15:09:59+00:00"},
              "seven_day": {"utilization": 7.0, "resets_at": "2026-11-25T03:59:59+00:00"},
              "seven_day_opus": null,
              "extra_usage": {"is_enabled": false}
            }
        """.trimIndent()

        val provider = ClaudeLiveUsageProvider(
            claudeDirectory = claudeDir,
            httpGet = { _, headers ->
                capturedAuth = headers["Authorization"]
                UsageHttpResponse(200, body)
            },
            keychainCredentialsReader = { null },
        )

        val snapshot = provider.discover(now)

        assertEquals("Bearer $secretToken", capturedAuth)
        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals(UsageSourceKind.LIVE_API, snapshot.sourceKind)
        assertEquals("Pro", snapshot.planLabel)
        assertEquals(2, snapshot.windows.size)
        assertEquals(20.0, snapshot.windows[0].usedPercent)
        assertEquals("5-hour window", snapshot.windows[0].label)
        assertEquals(7.0, snapshot.windows[1].usedPercent)
        assertEquals("Weekly limit", snapshot.windows[1].label)
        assertFalse(snapshot.toString().contains(secretToken))
    }

    @Test
    fun shouldReportClaudeAuthRequiredOn401AndRateLimitedOn429() {
        val claudeDir = Files.createDirectories(tempDir.resolve(".claude-err"))
        Files.writeString(
            claudeDir.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"tok","subscriptionType":"max"}}""",
        )

        val unauthorized = ClaudeLiveUsageProvider(
            claudeDirectory = claudeDir,
            httpGet = { _, _ -> UsageHttpResponse(401, "{}") },
            keychainCredentialsReader = { null },
        ).discover(now)
        assertEquals(UsageStatus.AUTH_REQUIRED, unauthorized.status)

        val limited = ClaudeLiveUsageProvider(
            claudeDirectory = claudeDir,
            httpGet = { _, _ -> UsageHttpResponse(429, "{}") },
            keychainCredentialsReader = { null },
        ).discover(now)
        assertEquals(UsageStatus.RATE_LIMITED, limited.status)
    }

    @Test
    fun shouldParseCodexLiveResponseAndHandleApiKeyMode() {
        val codexDir = Files.createDirectories(tempDir.resolve(".codex"))
        val secretToken = "codex-jwt-secret"
        Files.writeString(
            codexDir.resolve("auth.json"),
            """{"auth_mode":"chatgpt","tokens":{"access_token":"$secretToken","account_id":"acct-123"}}""",
        )
        var capturedAccountId: String? = null
        val body = """
            {
              "email": "private@example.com",
              "plan_type": "plus",
              "rate_limit": {
                "primary_window": {"used_percent": 1, "limit_window_seconds": 18000, "reset_at": 1795098123},
                "secondary_window": {"used_percent": 14, "limit_window_seconds": 604800, "reset_at": 1795698204}
              },
              "credits": {"has_credits": false, "unlimited": false, "balance": "0"}
            }
        """.trimIndent()

        val snapshot = CodexLiveUsageProvider(
            codexDirectory = codexDir,
            httpGet = { _, headers ->
                capturedAccountId = headers["ChatGPT-Account-Id"]
                UsageHttpResponse(200, body)
            },
        ).discover(now)

        assertEquals("acct-123", capturedAccountId)
        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals(UsageSourceKind.LIVE_API, snapshot.sourceKind)
        assertEquals("Plus", snapshot.planLabel)
        assertEquals(2, snapshot.windows.size)
        assertEquals(1.0, snapshot.windows[0].usedPercent)
        assertEquals(300, snapshot.windows[0].windowMinutes)
        assertEquals(14.0, snapshot.windows[1].usedPercent)
        assertEquals(10_080, snapshot.windows[1].windowMinutes)
        assertFalse(snapshot.toString().contains("private@example.com"))
        assertFalse(snapshot.toString().contains(secretToken))

        // API-key mode should not call ChatGPT usage API or ask to sign in.
        val apiKeyDir = Files.createDirectories(tempDir.resolve(".codex-apikey"))
        Files.writeString(apiKeyDir.resolve("auth.json"), """{"auth_mode":"api_key","OPENAI_API_KEY":"sk-test"}""")
        val apiKeySnapshot = CodexLiveUsageProvider(
            codexDirectory = apiKeyDir,
            httpGet = { _, _ -> throw AssertionError("HTTP must not be called for API-key mode") },
        ).discover(now)
        assertEquals(UsageStatus.LOCAL_ONLY, apiKeySnapshot.status)
        assertEquals("API Key", apiKeySnapshot.planLabel)
    }

    @Test
    fun shouldParseCopilotLiveQuotaAndSkipZeroEntitlementBuckets() {
        val body = """
            {
              "copilot_plan": "individual",
              "access_type_sku": "free_limited_copilot",
              "quota_reset_date_utc": "2026-12-01T00:00:00.000Z",
              "quota_snapshots": {
                "premium_interactions": {
                  "has_quota": false,
                  "unlimited": false,
                  "entitlement": 0,
                  "remaining": 0,
                  "percent_remaining": 0.0
                },
                "chat": {
                  "has_quota": true,
                  "unlimited": false,
                  "entitlement": 200,
                  "remaining": 150,
                  "percent_remaining": 75.0
                },
                "completions": {
                  "has_quota": true,
                  "unlimited": false,
                  "entitlement": 2000,
                  "remaining": 1800,
                  "percent_remaining": 90.0
                }
              }
            }
        """.trimIndent()

        val snapshot = CopilotLiveUsageProvider(
            homeDirectory = tempDir,
            envLookup = { null },
            shellOutput = { "gho_secret_token" },
            httpGet = { _, _ -> UsageHttpResponse(200, body) },
        ).discover(now)

        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals("Free", snapshot.planLabel)
        // Zero-entitlement premium_interactions must be omitted, never shown as 100% used!
        assertEquals(listOf("chat", "completions"), snapshot.windows.map { it.id })
        assertEquals(25.0, snapshot.windows[0].usedPercent)
        assertEquals(50.0, snapshot.windows[0].usedAmount)
        assertEquals(200.0, snapshot.windows[0].limitAmount)
        assertEquals(UsageWindowKind.MONTHLY, snapshot.windows[0].kind)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), snapshot.windows[0].resetsAt)
        assertEquals(10.0, snapshot.windows[1].usedPercent)
        assertFalse(snapshot.toString().contains("gho_secret_token"))
    }

    private fun cursorJwt(expiresAt: Instant, sub: String = "google-oauth2|user_01ABC"): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"$sub","exp":${expiresAt.epochSecond}}""".toByteArray())
        return "$header.$payload.signature-part"
    }

    private val cursorSummary = """
        {"billingCycleStart":"2026-11-03T18:45:47.000Z","billingCycleEnd":"2026-12-03T18:45:47.000Z","membershipType":"pro",
         "limitType":"user","isUnlimited":false,"autoModelSelectedDisplayMessage":"You've used 35% of your included total usage",
         "individualUsage":{"plan":{"enabled":true,"used":2000,"limit":2000,"remaining":0,
           "breakdown":{"included":2000,"bonus":14733,"total":16733},
           "autoPercentUsed":31.8977777777778,"apiPercentUsed":100,"totalPercentUsed":35.4137566137566},
           "onDemand":{"enabled":false,"used":0,"limit":null,"remaining":null}},
         "teamUsage":{}}
    """.trimIndent()

    @Test
    fun shouldReadCursorLiveUsageWithTheCliTokenAndNeverKeepIt(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val token = cursorJwt(now.plusSeconds(3600))
        val authFile = dir.resolve("auth.json")
        java.nio.file.Files.writeString(authFile, """{"accessToken":"$token","refreshToken":"refresh-secret"}""")
        var sentHeaders: Map<String, String> = emptyMap()
        var sentUrl = ""
        val provider = CursorLiveUsageProvider(authFileOverride = authFile, httpGet = { url, headers ->
            sentUrl = url
            sentHeaders = headers
            UsageHttpResponse(200, cursorSummary)
        })

        val snapshot = provider.discover(now)

        assertEquals("https://cursor.com/api/usage-summary", sentUrl)
        assertTrue(sentHeaders.getValue("Cookie").startsWith("WorkosCursorSessionToken=user_01ABC%3A%3A"))
        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals("Pro", snapshot.planLabel)
        assertEquals(listOf("plan", "auto", "api"), snapshot.windows.map { it.id })
        assertEquals(35.4137566137566, snapshot.windows[0].usedPercent)
        assertEquals(31.8977777777778, snapshot.windows[1].usedPercent)
        assertEquals(100.0, snapshot.windows[2].usedPercent)
        assertEquals(Instant.parse("2026-12-03T18:45:47Z"), snapshot.windows[0].resetsAt)
        assertEquals(30 * 24 * 60, snapshot.windows[0].windowMinutes)
        assertEquals("Spend \$167.33 (included \$20.00 + bonus \$147.33)", snapshot.statusNote)
        assertFalse(snapshot.toString().contains(token))
        assertFalse(snapshot.toString().contains("refresh-secret"))
        assertTrue(provider.requiresNetwork)
    }

    private fun protoVarint(value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var v = value
        while (v and 0x7F.inv().toLong() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private fun protoTimestamp(number: Int, epochSeconds: Long): ByteArray {
        val inner = byteArrayOf(0x08) + protoVarint(epochSeconds)
        return byteArrayOf(((number shl 3) or 2).toByte(), inner.size.toByte()) + inner
    }

    private fun protoDouble(number: Int, value: Double): ByteArray =
        byteArrayOf(((number shl 3) or 1).toByte()) +
            java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(value).array()

    @Test
    fun shouldAddCursorGrokBotWeeklyWindowFromTheProtobufRpcs(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val authFile = dir.resolve("auth.json")
        java.nio.file.Files.writeString(authFile, """{"accessToken":"${cursorJwt(now.plusSeconds(3600))}"}""")
        val start = now.epochSecond - 3 * 86_400
        val reset = start + 7 * 86_400
        val usageBody = protoTimestamp(1, start) + protoTimestamp(2, reset) + protoDouble(3, 12.5)
        val requested = mutableListOf<String>()
        fun provider(access: Long, usage: ByteArray, contentType: String = "application/proto") =
            CursorLiveUsageProvider(
                authFileOverride = authFile,
                httpGet = { _, _ -> UsageHttpResponse(200, cursorSummary) },
                httpPostProto = { url, headers ->
                    requested += url.substringAfterLast('/')
                    assertEquals("application/proto", headers["Content-Type"])
                    if (url.endsWith("GetSandAccessStatus")) {
                        UsageHttpBytes(200, contentType, byteArrayOf(0x08, access.toByte()))
                    } else {
                        UsageHttpBytes(200, contentType, usage)
                    }
                },
            )

        val snapshot = provider(1, usageBody).discover(now)
        assertEquals(listOf("plan", "auto", "api", "grok_bot"), snapshot.windows.map { it.id })
        val grok = snapshot.windows.last()
        assertEquals(12.5, grok.usedPercent)
        assertEquals(UsageWindowKind.WEEKLY, grok.kind)
        assertEquals(7 * 24 * 60, grok.windowMinutes)
        assertEquals(Instant.ofEpochSecond(reset), grok.resetsAt)
        assertEquals(listOf("GetSandAccessStatus", "GetSandUsageStatus"), requested)

        // Not granted, wrong content type or a malformed body only drop this window.
        assertEquals(3, provider(0, usageBody).discover(now).windows.size)
        assertEquals(3, provider(1, usageBody, contentType = "text/html").discover(now).windows.size)
        assertEquals(3, provider(1, byteArrayOf(0x0A, 0x7F)).discover(now).windows.size)
    }

    @Test
    fun shouldDecodeProtobufFieldsAndRejectMalformedInput() {
        val fields = ProtoWire.readFields(protoTimestamp(1, 1_700_000_000) + protoDouble(3, 1.5) + byteArrayOf(0x20, 0x01))!!
        assertEquals(Instant.ofEpochSecond(1_700_000_000), ProtoWire.timestamp(fields[0]))
        assertEquals(1.5, ProtoWire.doubleValue(fields[1]))
        assertEquals(1L, fields[2].varint)
        assertNull(ProtoWire.readFields(byteArrayOf(0x0A, 0x05, 0x01)))
        assertNull(ProtoWire.readFields(byteArrayOf(0x0B)))
        assertNull(ProtoWire.readFields(byteArrayOf(0x00)))
    }

    @Test
    fun shouldAskForCursorSignInWhenTokenIsMissingExpiredOrRejected(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val never = { _: String, _: Map<String, String> -> throw AssertionError("no request without a valid token") }
        assertEquals(
            UsageStatus.AUTH_REQUIRED,
            CursorLiveUsageProvider(authFileOverride = dir.resolve("missing.json"), httpGet = never).discover(now).status,
        )

        val expired = dir.resolve("expired.json")
        java.nio.file.Files.writeString(expired, """{"accessToken":"${cursorJwt(now.minusSeconds(60))}"}""")
        assertEquals(UsageStatus.AUTH_REQUIRED, CursorLiveUsageProvider(authFileOverride = expired, httpGet = never).discover(now).status)

        val badSub = dir.resolve("badsub.json")
        java.nio.file.Files.writeString(badSub, """{"accessToken":"${cursorJwt(now.plusSeconds(60), sub = "x|a;b")}"}""")
        assertEquals(UsageStatus.AUTH_REQUIRED, CursorLiveUsageProvider(authFileOverride = badSub, httpGet = never).discover(now).status)

        val valid = dir.resolve("valid.json")
        java.nio.file.Files.writeString(valid, """{"accessToken":"${cursorJwt(now.plusSeconds(3600))}"}""")
        assertEquals(
            UsageStatus.AUTH_REQUIRED,
            CursorLiveUsageProvider(authFileOverride = valid, httpGet = { _, _ -> UsageHttpResponse(401, "") }).discover(now).status,
        )
        assertEquals(
            UsageStatus.RATE_LIMITED,
            CursorLiveUsageProvider(authFileOverride = valid, httpGet = { _, _ -> UsageHttpResponse(429, "") }).discover(now).status,
        )
    }

    @Test
    fun shouldParseCursorUnifiedMembershipAsWeeklyPool() {
        val json = """{"membershipType":"unified_pro","individualUsage":{"plan":{"enabled":true,"totalPercentUsed":12.5}}}"""
        val snapshot = CursorLiveUsageProvider().parseUsageSummary(json, now)
        assertEquals("Pro", snapshot.planLabel)
        assertEquals(listOf("weekly_pool"), snapshot.windows.map { it.id })
        assertEquals(12.5, snapshot.windows[0].usedPercent)
    }

    private fun grokAuth(dir: java.nio.file.Path, expiresAt: Instant, token: String = "grok-access-secret"): java.nio.file.Path {
        java.nio.file.Files.writeString(
            dir.resolve("auth.json"),
            """{"https://auth.x.ai::client-1":{"key":"$token","auth_mode":"oidc","email":"private@example.com",""" +
                """"refresh_token":"grok-refresh-secret","expires_at":"$expiresAt","oidc_issuer":"https://auth.x.ai"}}""",
        )
        return dir
    }

    private val grokBilling = """
        {"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-11-19T00:00:00Z","end":"2026-11-26T00:00:00Z"},
         "onDemandCap":{"val":0},"onDemandUsed":{"val":0},"isUnifiedBillingUser":true,"prepaidBalance":{"val":0}}}
    """.trimIndent()

    @Test
    fun shouldReadGrokWeeklyLimitWithTheCliTokenAndNeverKeepIt(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val requested = mutableListOf<Pair<String, Map<String, String>>>()
        val provider = GrokLiveUsageProvider(grokDirectory = grokAuth(dir, now.plusSeconds(3600)), httpGet = { url, headers ->
            requested += url to headers
            if (url.endsWith("/v1/settings")) UsageHttpResponse(200, """{"subscription_tier_display":"Free"}""") else UsageHttpResponse(200, grokBilling)
        })

        val snapshot = provider.discover(now)

        assertEquals("https://cli-chat-proxy.grok.com/v1/billing?format=credits", requested.first().first)
        assertEquals("Bearer grok-access-secret", requested.first().second["Authorization"])
        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals("Free", snapshot.planLabel)
        val weekly = snapshot.windows.single()
        assertEquals("Weekly", weekly.label)
        assertEquals(UsageWindowKind.WEEKLY, weekly.kind)
        assertEquals(0.0, weekly.usedPercent)
        assertEquals(7 * 24 * 60, weekly.windowMinutes)
        assertEquals(Instant.parse("2026-11-26T00:00:00Z"), weekly.resetsAt)
        assertFalse(snapshot.toString().contains("grok-access-secret"))
        assertFalse(snapshot.toString().contains("grok-refresh-secret"))
        assertFalse(snapshot.toString().contains("private@example.com"))
    }

    @Test
    fun shouldSumGrokProductSharesAndKeepThemAsANote() {
        val json = """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-11-19T00:00:00Z","end":"2026-11-26T00:00:00Z"},
            "productUsage":[{"product":"GrokBuild","usagePercent":12.5},{"product":"GrokBot","usagePercent":3},{"product":"GrokChat","usagePercent":0}]}}"""
        val snapshot = GrokLiveUsageProvider().parseBilling(json, now)
        assertEquals(15.5, snapshot.windows.single().usedPercent)
        assertTrue(snapshot.statusNote!!.contains("Grok Build 13%"))
        assertTrue(snapshot.statusNote!!.contains("Grok Bot 3%"))

        val explicit = GrokLiveUsageProvider().parseBilling(
            """{"config":{"creditUsagePercent":{"val":40},"currentPeriod":{"type":"USAGE_PERIOD_TYPE_MONTHLY"}}}""", now,
        )
        assertEquals(40.0, explicit.windows.single().usedPercent)
        assertEquals(UsageWindowKind.MONTHLY, explicit.windows.single().kind)
    }

    @Test
    fun shouldAskForGrokSignInWhenTokenIsMissingExpiredOrRejected(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val never = { _: String, _: Map<String, String> -> throw AssertionError("no request without a valid token") }
        assertEquals(UsageStatus.AUTH_REQUIRED, GrokLiveUsageProvider(grokDirectory = dir.resolve("none"), httpGet = never).discover(now).status)

        assertEquals(
            UsageStatus.AUTH_REQUIRED,
            GrokLiveUsageProvider(grokDirectory = grokAuth(dir, now.minusSeconds(10)), httpGet = never).discover(now).status,
        )

        val valid = grokAuth(dir, now.plusSeconds(3600))
        assertEquals(
            UsageStatus.AUTH_REQUIRED,
            GrokLiveUsageProvider(grokDirectory = valid, httpGet = { _, _ -> UsageHttpResponse(401, "") }).discover(now).status,
        )
        assertEquals(
            UsageStatus.RATE_LIMITED,
            GrokLiveUsageProvider(grokDirectory = valid, httpGet = { _, _ -> UsageHttpResponse(429, "") }).discover(now).status,
        )
        assertEquals("grok login", com.shutterstar.agenthub.usage.model.UsageSourceCatalog.loginCommand("grok"))
    }

    @Test
    fun shouldParseCursorAboutAndKiroWhoamiSafely() {
        val cursorOutput = """
            About Cursor CLI
            CLI Version         2026.11.13
            Subscription Tier   Pro
            User Email          private@example.com
        """.trimIndent()
        val cursor = CursorCliStatusProvider(shellOutput = { cursorOutput }).discover(now)
        assertNotNull(cursor)
        assertEquals("Pro", cursor!!.planLabel)
        assertEquals(UsageSourceKind.CLI_QUERY, cursor.sourceKind)
        assertFalse(cursor.toString().contains("private@example.com"))

        val cursorSignedOut = CursorCliStatusProvider(shellOutput = { "Not logged in. Run `cursor-agent login`." }).discover(now)!!
        assertEquals(UsageStatus.AUTH_REQUIRED, cursorSignedOut.status)
        assertEquals("cursor-agent login", com.shutterstar.agenthub.usage.model.UsageSourceCatalog.loginCommand("cursor"))
        assertNull(com.shutterstar.agenthub.usage.model.UsageSourceCatalog.loginCommand("kiro"))

        val kiroSignedIn = KiroCliStatusProvider(shellOutput = { "Logged in with Google\nEmail: user@example.com" }).discover(now)
        assertNotNull(kiroSignedIn)
        assertEquals(UsageStatus.LOCAL_ONLY, kiroSignedIn!!.status)
        assertFalse(kiroSignedIn.toString().contains("user@example.com"))

        val kiroSignedOut = KiroCliStatusProvider(shellOutput = { "Not logged in" }).discover(now)
        assertEquals(UsageStatus.AUTH_REQUIRED, kiroSignedOut!!.status)
        assertNull(KiroCliStatusProvider(shellOutput = { "" }).discover(now))

        // Local CLI status commands must not depend on the live-quota opt-in.
        assertFalse(CursorCliStatusProvider().requiresNetwork)
        assertFalse(KiroCliStatusProvider().requiresNetwork)
    }

    @Test
    fun shouldGateNetworkProvidersBehindOptInAndFallbackToLocalOnLiveFailure() {
        var liveCalled = false
        val liveCodex = object : AgentUsageProvider {
            override val agentId = "codex"
            override val sourceKind = UsageSourceKind.LIVE_API
            override val requiresNetwork = true
            override fun isAvailable() = true
            override fun discover(now: Instant): com.shutterstar.agenthub.usage.model.AgentUsageSnapshot {
                liveCalled = true
                throw IllegalStateException("network offline Bearer secret-xyz")
            }
        }
        val localCodex = object : AgentUsageProvider {
            override val agentId = "codex"
            override val sourceKind = UsageSourceKind.LOCAL_TELEMETRY
            override val requiresNetwork = false
            override fun isAvailable() = true
            override fun discover(now: Instant) = com.shutterstar.agenthub.usage.model.AgentUsageSnapshot(
                agentId = "codex",
                status = UsageStatus.OK,
                sourceKind = UsageSourceKind.LOCAL_TELEMETRY,
                planLabel = "Plus",
                observedAt = now,
            )
        }

        // Opt-in OFF: live provider must NOT run at all.
        val offlineResult = UsageDiscoveryService(
            providers = listOf(liveCodex, localCodex),
            liveQueriesEnabled = { false },
            candidateAgentIds = { setOf("codex") },
            now = { now },
        ).discover(emptyList())
        assertFalse(liveCalled)
        assertEquals(UsageSourceKind.LOCAL_TELEMETRY, offlineResult.snapshots.single().sourceKind)

        // Opt-in ON: live runs, fails, falls back to local telemetry, and redacts any Bearer token in warnings.
        val liveFallback = UsageDiscoveryService(
            providers = listOf(liveCodex, localCodex),
            liveQueriesEnabled = { true },
            candidateAgentIds = { setOf("codex") },
            now = { now },
        ).discover(emptyList())
        assertTrue(liveCalled)
        assertEquals(UsageSourceKind.LOCAL_TELEMETRY, liveFallback.snapshots.single().sourceKind)
        assertEquals(1, liveFallback.warnings.size)
        assertFalse(liveFallback.warnings.single().message.contains("secret-xyz"))
        assertTrue(liveFallback.warnings.single().message.contains("[redacted]"))
    }
}
