package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.usage.model.UsageResetCredits
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
import java.time.ZoneId

class HeadlessUsageProvidersTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-11-20T09:00:00Z")

    private val claudeOutput = """
        You are currently using your subscription to power your Claude Code usage

        Current session: 4% used · resets Nov 20, 1:10pm (Europe/Budapest)
        Current week (all models): 8% used · resets Nov 25, 5am (Europe/Budapest)

        What's contributing to your usage?
        Last 24h · 130 requests · 3 sessions
          75%  Start fresh when you switch tasks · run /clear
    """.trimIndent()

    private fun claudeRunner(output: String): (String) -> String =
        { if (it == ClaudeCliUsageProvider.HELP_COMMAND) "  --no-session-persistence  Do not save sessions" else output }

    @Test
    fun shouldParseClaudeUsageCommandOutput() {
        val provider = ClaudeCliUsageProvider(runCommand = claudeRunner(claudeOutput), defaultZone = ZoneId.of("UTC"))

        val snapshot = provider.discover(now)

        assertNotNull(snapshot)
        assertEquals(UsageStatus.OK, snapshot!!.status)
        assertEquals(UsageSourceKind.HEADLESS_COMMAND, snapshot.sourceKind)
        assertEquals(listOf("five_hour", "seven_day"), snapshot.windows.map { it.id })
        val session = snapshot.windows[0]
        assertEquals(4.0, session.usedPercent)
        assertEquals(UsageWindowKind.ROLLING, session.kind)
        assertEquals(Instant.parse("2026-11-20T12:10:00Z"), session.resetsAt)
        val week = snapshot.windows[1]
        assertEquals(8.0, week.usedPercent)
        assertEquals(Instant.parse("2026-11-25T04:00:00Z"), week.resetsAt)
    }

    @Test
    fun shouldRunClaudeUsageWithoutPersistingASession() {
        val commands = mutableListOf<String>()
        ClaudeCliUsageProvider(runCommand = { commands += it; claudeRunner(claudeOutput)(it) }, helpCache = ClaudeCliUsageProvider.HelpCache()).discover(now)

        assertEquals(listOf(ClaudeCliUsageProvider.HELP_COMMAND, ClaudeCliUsageProvider.COMMAND), commands)
        assertTrue("--no-session-persistence" in ClaudeCliUsageProvider.COMMAND)
    }

    @Test
    fun shouldSkipClaudeUsageWhenTheCliHasNoNoSessionPersistenceFlag() {
        val commands = mutableListOf<String>()
        val provider = ClaudeCliUsageProvider(
            runCommand = { commands += it; if (it == ClaudeCliUsageProvider.HELP_COMMAND) "  -p, --print  Print response" else claudeOutput },
            helpCache = ClaudeCliUsageProvider.HelpCache(),
        )

        assertNull(provider.discover(now))
        assertNull(provider.discover(now.plusSeconds(60)))

        assertEquals(listOf(ClaudeCliUsageProvider.HELP_COMMAND), commands)
    }

    @Test
    fun shouldNotCacheAnUnreadableClaudeHelp() {
        var helpAnswer = ""
        val provider = ClaudeCliUsageProvider(
            runCommand = { if (it == ClaudeCliUsageProvider.HELP_COMMAND) helpAnswer else claudeOutput },
            helpCache = ClaudeCliUsageProvider.HelpCache(),
        )

        assertNull(provider.discover(now))
        helpAnswer = "  --no-session-persistence  Do not save"
        assertNotNull(provider.discover(now.plusSeconds(1)))
    }

    @Test
    fun shouldMapClaudeModelSpecificWeeklyLinesAndTimeOnlyReset() {
        val provider = ClaudeCliUsageProvider(
            runCommand = claudeRunner(
                """
                Current session: 50% used · resets 1:10pm (UTC)
                Current week (Opus): 12.5% used · resets Nov 25, 5am (UTC)
                Current week (Sonnet): 3% used
                """.trimIndent(),
            ),
        )

        val snapshot = provider.discover(now)!!

        assertEquals(listOf("five_hour", "seven_day_opus", "seven_day_sonnet"), snapshot.windows.map { it.id })
        assertEquals(Instant.parse("2026-11-20T13:10:00Z"), snapshot.windows[0].resetsAt)
        assertEquals(12.5, snapshot.windows[1].usedPercent)
        assertNull(snapshot.windows[2].resetsAt)
    }

    @Test
    fun shouldRollClaudeResetIntoNextYearAndReturnNullForUnrecognizedOutput() {
        val provider = ClaudeCliUsageProvider(
            runCommand = claudeRunner("Current week (all models): 9% used · resets Jan 3, 5am (UTC)"),
        )
        val december = Instant.parse("2026-12-30T09:00:00Z")
        assertEquals(Instant.parse("2027-01-03T05:00:00Z"), provider.discover(december)!!.windows.single().resetsAt)

        assertNull(ClaudeCliUsageProvider(runCommand = claudeRunner("")).discover(now))
        assertNull(ClaudeCliUsageProvider(runCommand = claudeRunner("Unknown command: /usage")).discover(now))
        assertEquals(
            UsageStatus.AUTH_REQUIRED,
            ClaudeCliUsageProvider(runCommand = claudeRunner("Not logged in · Please run /login")).discover(now)!!.status,
        )
    }

    @Test
    fun shouldParseAntigravityRowsAsRemainingPercent() {
        val output = listOf(
            "Gemini Models\tWeekly Limit Remaining\t85%\t2026-11-26T11:34:26Z",
            "Gemini Models\tFive Hour Limit Remaining\t100%\t2026-11-20T14:29:06Z",
            "Claude and GPT models\tWeekly Limit Remaining\t100%\t2026-11-27T09:29:06Z",
            "Gemini 4 Argon\tGemini 4 Argon Weekly Limit\t100%\t2026-11-27T09:29:06Z",
            "not a row",
        ).joinToString("\n")

        val snapshot = AntigravityCliUsageProvider(runCommand = { output }).discover(now)!!

        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals(4, snapshot.windows.size)
        val gemini = snapshot.windows[0]
        assertEquals(15.0, gemini.usedPercent)
        assertEquals("Gemini Models · Weekly limit", gemini.label)
        assertEquals(UsageWindowKind.WEEKLY, gemini.kind)
        assertEquals(Instant.parse("2026-11-26T11:34:26Z"), gemini.resetsAt)
        assertEquals(UsageWindowKind.ROLLING, snapshot.windows[1].kind)
        assertEquals(0.0, snapshot.windows[1].usedPercent)
        assertEquals(snapshot.windows.size, snapshot.windows.map { it.id }.toSet().size)

        assertNull(AntigravityCliUsageProvider(runCommand = { "" }).discover(now))
        assertNull(AntigravityCliUsageProvider(runCommand = { "some chat answer" }).discover(now))
    }

    @Test
    fun shouldParseKiroCreditsAndStripAnsiEscapes() {
        val output = "\u001B[1G\nEstimated Usage | resets on 2026-12-01 | KIRO FREE\n" +
            "Credits (0.18 of 50 covered in plan)\n████████\n\nTo manage your plan navigate to https://app.kiro.dev/account/usage\n"

        val snapshot = KiroCliUsageProvider(runCommand = { output }).discover(now)!!

        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals("Kiro Free", snapshot.planLabel)
        val credits = snapshot.windows.single()
        assertEquals(0.18, credits.usedAmount)
        assertEquals(50.0, credits.limitAmount)
        assertEquals(0.36, credits.usedPercent!!, 0.0001)
        assertEquals(UsageWindowKind.MONTHLY, credits.kind)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), credits.resetsAt)
    }

    @Test
    fun shouldDiscardKiroOutputThatIsNotTheUsageBlock() {
        assertNull(KiroCliUsageProvider(runCommand = { "Hello! How can I help you today?" }).discover(now))
        assertNull(KiroCliUsageProvider(runCommand = { "" }).discover(now))
        assertEquals(
            UsageStatus.AUTH_REQUIRED,
            KiroCliUsageProvider(runCommand = { "Not logged in. Run kiro-cli login" }).discover(now)!!.status,
        )
    }

    @Test
    fun shouldParseCodexAppServerRateLimitsResponse() {
        val response = """{"id":2,"result":{"rateLimits":{"limitId":"codex","limitName":null,""" +
            """"primary":{"usedPercent":9,"windowDurationMins":300,"resetsAt":1795179507},""" +
            """"secondary":{"usedPercent":1.5,"windowDurationMins":10080,"resetsAt":1795766307},""" +
            """"credits":{"hasCredits":false,"unlimited":false},"planType":"plus","rateLimitReachedType":null},""" +
            """"rateLimitsByLimitId":{"codex":{"limitId":"codex"}}}}"""

        val snapshot = CodexAppServerUsageProvider(fetchRateLimitsResponse = { response }).discover(now)!!

        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals(UsageSourceKind.HEADLESS_COMMAND, snapshot.sourceKind)
        assertEquals("Plus", snapshot.planLabel)
        assertEquals(listOf("primary", "secondary"), snapshot.windows.map { it.id })
        val primary = snapshot.windows[0]
        assertEquals(9.0, primary.usedPercent)
        assertEquals(300, primary.windowMinutes)
        assertEquals("5-hour window", primary.label)
        assertEquals(Instant.ofEpochSecond(1795179507), primary.resetsAt)
        val secondary = snapshot.windows[1]
        assertEquals(1.5, secondary.usedPercent)
        assertEquals("Weekly limit", secondary.label)
        assertEquals(UsageWindowKind.WEEKLY, secondary.kind)
    }

    @Test
    fun shouldReadCodexSavedResetsFromTheAppServer() {
        val expires = now.plusSeconds(10 * 86_400).epochSecond
        val response = """{"id":2,"result":{"rateLimits":{"primary":{"usedPercent":9,"windowDurationMins":300,"resetsAt":1795179507},"planType":"plus"},""" +
            """"rateLimitResetCredits":{"availableCount":3,"credits":[""" +
            """{"id":"RateLimitResetCredit_b","resetType":"codexRateLimits","status":"available","grantedAt":1795000000,"expiresAt":${expires + 86_400},"title":"Second reset","description":null},""" +
            """{"id":"RateLimitResetCredit_a","resetType":"codexRateLimits","status":"available","grantedAt":1795000000,"expiresAt":$expires,"title":"One free rate limit reset"},""" +
            """{"id":"RateLimitResetCredit_c","resetType":"codexRateLimits","status":"redeemed","grantedAt":1795000000,"expiresAt":$expires,"title":"Spent"},""" +
            """{"id":"RateLimitResetCredit_d","resetType":"codexRateLimits","status":"available","grantedAt":1,"expiresAt":${now.epochSecond - 5},"title":"Expired"}]}}}"""

        val credits = CodexAppServerUsageProvider(fetchRateLimitsResponse = { response }).discover(now)!!.resetCredits!!

        assertEquals(3, credits.availableCount)
        assertEquals(listOf("One free rate limit reset", "Second reset"), credits.credits.map { it.title })
        assertEquals(Instant.ofEpochSecond(expires), credits.credits[0].expiresAt)
        assertEquals(Instant.ofEpochSecond(1795000000), credits.credits[0].grantedAt)
        // Summary without detail rows (backend only knows the count) and no summary at all.
        val countOnly = """{"id":2,"result":{"rateLimits":{"primary":{"usedPercent":1}},"rateLimitResetCredits":{"availableCount":2,"credits":null}}}"""
        assertEquals(UsageResetCredits(2), CodexAppServerUsageProvider(fetchRateLimitsResponse = { countOnly }).discover(now)!!.resetCredits)
        assertNull(CodexAppServerUsageProvider(fetchRateLimitsResponse = { noResetCredits }).discover(now)!!.resetCredits)
    }

    private val noResetCredits = """{"id":2,"result":{"rateLimits":{"primary":{"usedPercent":1}}}}"""

    private val ompAnthropicUsage = """
        warming up
        {
          "reports": [
            {"provider": "openai-codex", "limits": [], "resetCredits": {"availableCount": 3}},
            {
              "provider": "anthropic",
              "limits": [],
              "metadata": {"email": "te*", "orgName": "te*"},
              "resetCredits": {
                "availableCount": 2,
                "redeemableCount": 1,
                "nextCreditId": "full-reset-2611",
                "eligible": true,
                "credits": [
                  {"id": "later", "title": "Later reset", "remainingCount": 1, "usable": false, "requiresLimit": true,
                   "grantedAt": "2026-11-10T21:30:00Z", "expiresAt": "2026-12-30T22:00:00Z", "status": "available"},
                  {"id": "full-reset-2611", "title": "One full limit reset (November 2026)", "remainingCount": 1, "usable": true, "requiresLimit": false,
                   "clears": ["anthropic:5h", "anthropic:7d"], "grantedAt": "2026-11-10T21:30:00Z", "expiresAt": "2026-12-10T22:00:00Z", "status": "available"},
                  {"id": "old", "title": "Old", "remainingCount": 1, "expiresAt": "2026-11-01T00:00:00Z", "status": "available"},
                  {"id": "spent", "title": "Spent", "remainingCount": 0, "expiresAt": "2026-12-31T00:00:00Z", "status": "redeemed"}
                ]
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun shouldListClaudeSavedResetsFromOmpWithoutSpendingAnything() {
        var command = ""
        val credits = ClaudeResetCreditsProvider(runCommand = { command = it; ompAnthropicUsage }).discover(now)!!

        assertEquals("omp usage --json --redact --provider anthropic", command)
        assertEquals(2, credits.availableCount)
        assertEquals(listOf("One full limit reset (November 2026)", "Later reset"), credits.credits.map { it.title })
        assertEquals(Instant.parse("2026-12-10T22:00:00Z"), credits.credits[0].expiresAt)
        assertEquals(Instant.parse("2026-11-10T21:30:00Z"), credits.credits[0].grantedAt)
        assertEquals(false, credits.credits[0].requiresLimit)
        assertEquals(true, credits.credits[1].requiresLimit)
        assertFalse(command.contains("reset "))
        assertFalse(credits.toString().contains("te*"))
    }

    @Test
    fun shouldTreatMissingOmpOrAnthropicReportAsUnknown() {
        assertNull(ClaudeResetCreditsProvider(runCommand = { "" }).discover(now))
        assertNull(ClaudeResetCreditsProvider(runCommand = { "error: Bun runtime must be >= 1.4.4" }).discover(now))
        assertNull(ClaudeResetCreditsProvider(runCommand = { """{"reports":[{"provider":"cursor","limits":[]}]}""" }).discover(now))
        assertNull(ClaudeResetCreditsProvider(runCommand = { """{"reports":[{"provider":"anthropic","limits":[]}]}""" }).discover(now))
        val none = """{"reports":[{"provider":"anthropic","resetCredits":{"availableCount":0,"credits":[]}}]}"""
        assertEquals(0, ClaudeResetCreditsProvider(runCommand = { none }).discover(now)!!.availableCount)
    }

    @Test
    fun shouldHandleCodexAppServerErrorsAndEmptyResponses() {
        val authError = """{"id":2,"error":{"code":-32600,"message":"codex account authentication required to read rate limits"}}"""
        assertEquals(
            UsageStatus.AUTH_REQUIRED,
            CodexAppServerUsageProvider(fetchRateLimitsResponse = { authError }).discover(now)!!.status,
        )
        val otherError = """{"id":2,"error":{"code":-1,"message":"boom"}}"""
        val failure = runCatching { CodexAppServerUsageProvider(fetchRateLimitsResponse = { otherError }).discover(now) }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)

        assertNull(CodexAppServerUsageProvider(fetchRateLimitsResponse = { null }).discover(now))
        assertNull(CodexAppServerUsageProvider(fetchRateLimitsResponse = { """{"id":2,"result":{"rateLimits":{"planType":"plus"}}}""" }).discover(now))
        assertNull(CodexAppServerUsageProvider(fetchRateLimitsResponse = { "not json" }).discover(now))
    }

    @Test
    fun shouldParseHermesUsageJsonDocument() {
        val json = """
            {
              "provider": "openai-codex",
              "source": "codex-usage",
              "title": "Account limits",
              "plan": "plus",
              "fetched_at": "2026-11-20T09:00:00+00:00",
              "windows": [
                {"label": "Session (5h)", "used_percent": 9.0, "resets_at": "2026-11-20T14:00:00+00:00", "detail": null},
                {"label": "Weekly", "used_percent": 1, "resets_at": "2026-11-27T09:00:00+00:00", "detail": "x {y}"},
                {"label": "Weekly Opus", "used_percent": null, "resets_at": null, "detail": null}
              ],
              "details": ["Banked resets: 1"],
              "unavailable_reason": null
            }
        """.trimIndent()

        val snapshot = HermesCliUsageProvider(runCommand = { json }).discover(now)!!

        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals("Plus", snapshot.planLabel)
        assertEquals(listOf("session_5h", "weekly"), snapshot.windows.map { it.id })
        assertEquals(UsageWindowKind.ROLLING, snapshot.windows[0].kind)
        assertEquals(9.0, snapshot.windows[0].usedPercent)
        assertEquals(Instant.parse("2026-11-20T14:00:00Z"), snapshot.windows[0].resetsAt)
        assertEquals(UsageWindowKind.WEEKLY, snapshot.windows[1].kind)
        assertEquals(10_080, snapshot.windows[1].windowMinutes)
    }

    @Test
    fun shouldReturnNullForHermesWithoutCredentialOrWindows() {
        val message = "No account usage available for provider 'nous': no credential is configured for it."
        assertNull(HermesCliUsageProvider(runCommand = { message }).discover(now))
        assertNull(HermesCliUsageProvider(runCommand = { "" }).discover(now))
        assertNull(
            HermesCliUsageProvider(
                runCommand = { """{"provider":"x","windows":[],"details":[],"unavailable_reason":null}""" },
            ).discover(now),
        )
        assertNull(
            HermesCliUsageProvider(
                runCommand = { """{"windows":[{"label":"Session","used_percent":5}],"unavailable_reason":"expired"}""" },
            ).discover(now),
        )
    }

    private val ompUsage = """
        {
          "generatedAt": 1795173400522,
          "reports": [
            {
              "provider": "openai-codex",
              "limits": [
                {"id": "openai-codex:primary", "label": "5 hours", "window": {"id": "5h", "label": "5 hours", "durationMs": 18000000, "resetsAt": 1795179507000},
                 "amount": {"used": 9, "limit": 100, "remaining": 91, "usedFraction": 0.09, "unit": "percent"}, "status": "ok"},
                {"id": "openai-codex:secondary", "label": "7 days", "window": {"id": "7d", "label": "7 days", "durationMs": 604800000, "resetsAt": 1795766307000},
                 "amount": {"used": 1, "limit": 100, "unit": "percent"}, "status": "ok"}
              ],
              "resetCredits": {"availableCount": 3, "credits": [{"expiresAt": "2026-11-30T18:35:37Z", "status": "available"}]},
              "metadata": {"planType": "plus", "email": "private@example.com"}
            },
            {
              "provider": "cursor",
              "limits": [
                {"id": "cursor:usd:individual-auto", "label": "Cursor Models", "window": {"id": "monthly", "label": "Monthly", "resetsAt": 1796323547000},
                 "amount": {"used": 31.89777777777778, "unit": "percent"}, "status": "ok"},
                {"id": "cursor:usd:individual-api", "label": "Other Models", "window": {"id": "monthly", "label": "Monthly", "resetsAt": 1796323547000},
                 "amount": {"used": 100, "unit": "percent"}, "status": "exhausted"},
                {"id": "cursor:spend", "label": "Spend", "window": {"id": "monthly"}, "amount": {"used": 12.5, "unit": "usd"}, "status": "ok"}
              ]
            },
            {
              "provider": "anthropic",
              "limits": [
                {"id": "anthropic:5h", "label": "Claude 5 Hour", "window": {"id": "5h", "label": "5 Hour", "durationMs": 18000000, "resetsAt": 1795176600614},
                 "amount": {"used": 23, "limit": 100, "unit": "percent"}, "status": "ok"}
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun shouldParseOmpUsageAcrossProvidersWithoutMetadataOrCredits() {
        val snapshot = OmpCliUsageProvider(runCommand = { "warming up\n$ompUsage" }).discover(now)!!

        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals(
            listOf("openai_codex_primary", "openai_codex_secondary", "cursor_usd_individual_auto", "cursor_usd_individual_api", "anthropic_5h"),
            snapshot.windows.map { it.id },
        )
        assertEquals("Codex · 5 hours", snapshot.windows[0].label)
        assertEquals(UsageWindowKind.ROLLING, snapshot.windows[0].kind)
        assertEquals(300, snapshot.windows[0].windowMinutes)
        assertEquals(Instant.ofEpochMilli(1795179507000), snapshot.windows[0].resetsAt)
        assertEquals(UsageWindowKind.WEEKLY, snapshot.windows[1].kind)
        assertEquals(UsageWindowKind.MONTHLY, snapshot.windows[2].kind)
        assertEquals(31.89777777777778, snapshot.windows[2].usedPercent)
        assertEquals(100.0, snapshot.windows[3].usedPercent)
        assertEquals("Claude 5 Hour", snapshot.windows[4].label)
        assertFalse(snapshot.toString().contains("private@example.com"))
        assertNull(snapshot.planLabel)
    }

    @Test
    fun shouldReturnNullForOmpWithoutUsableLimits() {
        assertNull(OmpCliUsageProvider(runCommand = { "" }).discover(now))
        assertNull(OmpCliUsageProvider(runCommand = { "error: Bun runtime must be >= 1.4.4" }).discover(now))
        assertNull(OmpCliUsageProvider(runCommand = { """{"reports":[]}""" }).discover(now))
        assertNull(
            OmpCliUsageProvider(
                runCommand = { """{"reports":[{"provider":"x","limits":[{"label":"Spend","amount":{"used":3,"unit":"usd"}}]}]}""" },
            ).discover(now),
        )
    }

    private val openClawStatus = """
        [plugins] loaded 3 plugins
        {
          "runtimeVersion": "2026.11.6",
          "sessions": {"count": 2, "usage": {"decoy": true}},
          "gateway": {"reachable": false, "error": "Gateway readiness budget exhausted."},
          "usage": {
            "updatedAt": 1795170920842,
            "providers": [
              {"provider": "anthropic", "displayName": "Claude", "windows": [], "error": "Timeout"},
              {
                "provider": "openai",
                "displayName": "OpenAI",
                "windows": [
                  {"label": "5h", "usedPercent": 9, "resetAt": 1795179507000},
                  {"label": "Week", "usedPercent": 1, "resetAt": 1795766307000}
                ],
                "plan": "plus",
                "billing": [{"type": "balance", "amount": 0, "unit": "credits"}],
                "accountEmail": "private@example.com"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun shouldParseOpenClawUsageAndNeverKeepTheAccountEmail() {
        val snapshot = OpenClawCliUsageProvider(runCommand = { openClawStatus }).discover(now)!!

        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals("Plus", snapshot.planLabel)
        assertEquals(listOf("5h", "week"), snapshot.windows.map { it.id })
        assertEquals(UsageWindowKind.ROLLING, snapshot.windows[0].kind)
        assertEquals(9.0, snapshot.windows[0].usedPercent)
        assertEquals(Instant.ofEpochMilli(1795179507000), snapshot.windows[0].resetsAt)
        assertEquals(UsageWindowKind.WEEKLY, snapshot.windows[1].kind)
        assertFalse(snapshot.toString().contains("private@example.com"))
        assertTrue(OpenClawCliUsageProvider().slow)
    }

    @Test
    fun shouldPrefixOpenClawWindowsWhenSeveralProvidersReportLimits() {
        val json = """
            {"usage":{"providers":[
              {"provider":"openai","displayName":"OpenAI","windows":[{"label":"5h","usedPercent":9,"resetAt":1795179507000}],"plan":"plus"},
              {"provider":"anthropic","displayName":"Claude","windows":[{"label":"5h","usedPercent":20,"resetAt":1795179507000}],"plan":"pro"}
            ]}}
        """.trimIndent()

        val snapshot = OpenClawCliUsageProvider(runCommand = { json }).discover(now)!!

        assertEquals(listOf("openai_5h", "anthropic_5h"), snapshot.windows.map { it.id })
        assertEquals("OpenAI · 5h", snapshot.windows[0].label)
        assertNull(snapshot.planLabel)
    }

    @Test
    fun shouldReturnNullForOpenClawWithoutWindows() {
        val timedOut = """{"usage":{"providers":[{"provider":"openai","displayName":"OpenAI","windows":[],"error":"Timeout"}]}}"""
        assertNull(OpenClawCliUsageProvider(runCommand = { timedOut }).discover(now))
        assertNull(OpenClawCliUsageProvider(runCommand = { "" }).discover(now))
        assertNull(OpenClawCliUsageProvider(runCommand = { "error: gateway unreachable" }).discover(now))
        assertNull(OpenClawCliUsageProvider(runCommand = { """{"usage":{"providers":[]}}""" }).discover(now))
    }

    private fun claudeConfig(
        fetchedAt: Instant,
        cachedAccount: String? = "acc-1",
        currentAccount: String? = "acc-1",
        fiveHourReset: String = "2026-11-20T12:10:00.5+00:00",
    ): String {
        val cacheAccount = cachedAccount?.let { """"accountUuid":"$it",""" }.orEmpty()
        val oauth = currentAccount?.let { """"oauthAccount":{"emailAddress":"private@example.com","accountUuid":"$it"},""" }.orEmpty()
        return """{"numStartups":3,$oauth"cachedUsageUtilization":{"fetchedAtMs":${fetchedAt.toEpochMilli()},$cacheAccount""" +
            """"utilization":{"five_hour":{"utilization":11,"resets_at":"$fiveHourReset"},""" +
            """"seven_day":{"utilization":9,"resets_at":"2026-11-25T04:00:00.5+00:00"},"seven_day_opus":null,""" +
            """"extra_usage":{"is_enabled":false},"limits":[]}}}"""
    }

    @Test
    fun shouldReadClaudeCodeCachedUsageWithoutCredentialsOrNetwork() {
        val snapshot = ClaudeLocalUsageCacheProvider(configFile = java.nio.file.Path.of("unused"))
            .parseConfig(claudeConfig(now.minusSeconds(120)), now)!!

        assertEquals(UsageStatus.OK, snapshot.status)
        assertEquals(UsageSourceKind.LOCAL_TELEMETRY, snapshot.sourceKind)
        assertEquals(now.minusSeconds(120), snapshot.observedAt)
        assertEquals(listOf("five_hour", "seven_day"), snapshot.windows.map { it.id })
        assertEquals(11.0, snapshot.windows[0].usedPercent)
        assertFalse(snapshot.toString().contains("private@example.com"))
        assertFalse(ClaudeLocalUsageCacheProvider().requiresNetwork)
    }

    @Test
    fun shouldMarkOldClaudeCacheStaleAndDropRolledOverWindows() {
        val provider = ClaudeLocalUsageCacheProvider(configFile = java.nio.file.Path.of("unused"))

        val stale = provider.parseConfig(claudeConfig(now.minusSeconds(3 * 3600)), now)!!
        assertEquals(UsageStatus.STALE, stale.status)

        val rolledOver = provider.parseConfig(claudeConfig(now.minusSeconds(3 * 3600), fiveHourReset = "2026-11-20T08:00:00+00:00"), now)!!
        assertEquals(listOf("seven_day"), rolledOver.windows.map { it.id })

        assertNull(provider.parseConfig(claudeConfig(now.minusSeconds(8L * 24 * 3600)), now))
    }

    @Test
    fun shouldIgnoreClaudeCacheOfAnotherOrUnknownAccount() {
        val provider = ClaudeLocalUsageCacheProvider(configFile = java.nio.file.Path.of("unused"))
        assertNull(provider.parseConfig(claudeConfig(now, cachedAccount = "acc-1", currentAccount = "acc-2"), now))
        assertNull(provider.parseConfig(claudeConfig(now, cachedAccount = null), now))
        assertNull(provider.parseConfig(claudeConfig(now, currentAccount = null), now))
        assertNull(provider.parseConfig("""{"numStartups":1}""", now))
        assertNull(provider.parseConfig("not json", now))
    }

    @Test
    fun shouldReadClaudeCacheFromConfigFileOnDisk() {
        val file = Files.writeString(tempDir.resolve(".claude.json"), claudeConfig(now.minusSeconds(60)))
        val provider = ClaudeLocalUsageCacheProvider(configFile = file)

        assertTrue(provider.isAvailable())
        assertEquals(2, provider.discover(now)!!.windows.size)
        assertFalse(ClaudeLocalUsageCacheProvider(configFile = tempDir.resolve("missing.json")).isAvailable())
    }

    @Test
    fun shouldOfferKiroUsageOnlyOnTheVerifiedPlatform() {
        assertTrue(KiroCliUsageProvider(verifiedPlatform = { true }).isAvailable())
        assertFalse(KiroCliUsageProvider(verifiedPlatform = { false }).isAvailable())
    }

    @Test
    fun shouldRunHeadlessProvidersOnlyWithLiveOptInAndPreferThemOverOauth() {
        val headless = listOf(ClaudeCliUsageProvider(), AntigravityCliUsageProvider(), KiroCliUsageProvider(), CodexAppServerUsageProvider(), HermesCliUsageProvider(), OpenClawCliUsageProvider(), OmpCliUsageProvider())
        assertTrue(headless.all { it.requiresNetwork })
        assertTrue(headless.all { it.sourceKind == UsageSourceKind.HEADLESS_COMMAND })
        assertFalse(AgentUsageProviders.all.any { it.agentId == "claude" && it.sourceKind == UsageSourceKind.CLI_QUERY })
        assertTrue(AgentUsageProviders.agentIds.containsAll(setOf("claude", "antigravity", "kiro")))
    }
}
