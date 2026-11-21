package com.shutterstar.agenthub.usage.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport
import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import com.shutterstar.agenthub.usage.model.UsageResetCredit
import com.shutterstar.agenthub.usage.model.UsageResetCredits
import java.time.Instant

/**
 * Saved limit resets of the Claude account, from the `resetCredits` block of the `anthropic` report of
 * `omp usage --json --redact --provider anthropic`. Oh My Pi lists them with its own Claude sign-in.
 *
 * The Claude API itself only evaluates the saved resets (`cedar_ember`) for the Claude Code CLI's own
 * client identity and answers any other client with `ineligible_reason: "surface"` and no grants, so
 * a direct call cannot tell "none" from "not shown to this client". AgentHub does not pose as the
 * CLI. Read-only: `omp usage reset`, which spends a credit, is never run.
 */
internal class ClaudeResetCreditsProvider(
    private val runCommand: (String) -> String = { HeadlessCommandSupport.runCommand(it, timeoutSeconds = 25) },
) : AgentResetCreditProvider {
    override val agentId: String = "claude"
    override val requiresNetwork: Boolean = true

    override fun discover(now: Instant): UsageResetCredits? {
        val output = HeadlessCommandSupport.cleanOutput(runCommand(COMMAND))
        val start = JSON_START.find(output)?.range?.first ?: return null
        return parse(output.substring(start), now)
    }

    internal fun parse(json: String, now: Instant): UsageResetCredits? {
        val reports = MetadataJsonParser.rawTopLevelField(json, "reports")?.trim()?.takeIf { it.startsWith("[") } ?: return null
        val report = HeadlessCommandSupport.splitJsonObjects(reports).firstOrNull {
            MetadataJsonParser.topLevelStringFields(it, setOf("provider"))["provider"] == "anthropic"
        } ?: return null
        val resets = MetadataJsonParser.rawTopLevelField(report, "resetCredits")?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val count = MetadataJsonParser.rawTopLevelField(resets, "availableCount")?.trim()?.toIntOrNull() ?: return null
        val rows = MetadataJsonParser.rawTopLevelField(resets, "credits")?.trim()?.takeIf { it.startsWith("[") }
        val credits = rows?.let(HeadlessCommandSupport::splitJsonObjects).orEmpty().mapNotNull { row ->
            val strings = MetadataJsonParser.topLevelStringFields(row, setOf("status", "title", "expiresAt", "grantedAt"))
            if (strings["status"] != "available") return@mapNotNull null
            val expiresAt = strings["expiresAt"]?.let(LocalSessionSupport::parseTimestamp)
            if (expiresAt != null && !expiresAt.isAfter(now)) return@mapNotNull null
            val flags = MetadataJsonParser.topLevelBooleanFields(row, setOf("requiresLimit"))
            UsageResetCredit(
                title = ResetCreditText.title(strings["title"]) ?: "Claude limit reset",
                expiresAt = expiresAt,
                grantedAt = strings["grantedAt"]?.let(LocalSessionSupport::parseTimestamp),
                requiresLimit = flags["requiresLimit"],
            )
        }.sortedBy { it.expiresAt ?: Instant.MAX }.take(MAX_CREDITS)
        return UsageResetCredits(availableCount = count.coerceIn(0, 999), credits = credits)
    }

    companion object {
        private const val COMMAND = "omp usage --json --redact --provider anthropic"
        private const val MAX_CREDITS = 10

        // Log lines may precede the JSON document; it starts at the first line beginning with `{`.
        private val JSON_START = Regex("(?m)^\\{")
    }
}
