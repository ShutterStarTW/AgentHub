package com.shutterstar.agenthub.usage.model

import com.shutterstar.agenthub.CodingAgents

/**
 * Static metadata for the Usage tab: priority agents, live-capable agents, official usage/billing
 * dashboard links, and concise explanations when an agent does not persist quota windows locally.
 */
object UsageSourceCatalog {
    /** Priority agents emphasized by the Usage tab roadmap. */
    val priorityAgentIds: Set<String> = linkedSetOf(
        "claude",
        "codex",
        "copilot",
        "antigravity",
        "cursor",
        "kiro",
    )

    /** Agents that have a verified opt-in live quota provider (real quota percentages). */
    val liveCapableAgentIds: Set<String> = linkedSetOf(
        "antigravity",
        "claude",
        "codex",
        "copilot",
        "cursor",
        "grok",
        "hermes",
        "kiro",
        "omp",
        "openclaw",
    )

    /** Pages where the account's usage is actually shown. */
    private val dashboardUrls: Map<String, String> = mapOf(
        "claude" to "https://claude.ai/settings/usage",
        "codex" to "https://chatgpt.com/settings/usage?tab=overview",
        "copilot" to "https://github.com/settings/copilot/features",
        "cursor" to "https://cursor.com/dashboard?tab=usage",
    )

    /** The OpenAI usage page, shown instead of the Codex dashboard when Codex runs with an API key. */
    const val API_USAGE_URL = "https://platform.openai.com/usage"

    /** Official pages that document the agent's own usage/limits command, for agents without an account dashboard. */
    private val guideUrls: Map<String, String> = mapOf(
        "antigravity" to "https://antigravity.google/docs/cli/commands/usage",
        "hermes" to "https://hermes-agent.nousresearch.com/docs/reference/cli-commands",
        "kiro" to "https://kiro.dev/docs/reference/slash-commands/",
        "openclaw" to "https://docs.openclaw.ai/concepts/usage-tracking",
    )

    private val localOnlyNotes: Map<String, String> = mapOf(
        "antigravity" to "Enable Live quotas above to read the account limits from `agy /usage`.",
        "claude" to "No recent usage read cached by Claude Code was found. Enable Live quotas above to read the limits from `claude /usage`.",
        "codex" to "No recent Codex rollout with rate-limit telemetry was found. Enable Live quotas above for real-time ChatGPT/Codex limits.",
        "copilot" to "Copilot CLI does not store account-wide monthly quota percentages locally. Enable Live quotas above to query GitHub Copilot limits.",
        "cursor" to "Cursor CLI does not report request quotas itself. Enable Live quotas above to read them from Cursor's usage API with your CLI sign-in, or open the Cursor usage dashboard below.",
        "grok" to "Grok Build does not store its usage locally. Enable Live quotas above to read the weekly limit with your CLI sign-in.",
        "hermes" to "Hermes reports account limits only for a configured Codex, Anthropic or OpenRouter provider. Enable Live quotas above to read them from `hermes usage`.",
        "kiro" to "Kiro CLI does not write credit balances to local files. Enable Live quotas above to read them from `kiro-cli /usage`.",
        "omp" to "Oh My Pi reports account limits only for signed-in providers. Enable Live quotas above to read them from `omp usage`.",
        "openclaw" to "OpenClaw reports account limits only for a signed-in model provider. Enable Live quotas above to read them from `openclaw status --usage` (takes about a minute).",
    )

    /** Verified CLI sign-in subcommand per agent (from the CLI's own `--help`). */
    private val loginSubcommands: Map<String, String> = mapOf(
        "cursor" to "login",
        "grok" to "login",
    )

    /** The terminal command that signs the agent's CLI in, or null when none is verified. */
    fun loginCommand(agentId: String): String? {
        val subcommand = loginSubcommands[agentId] ?: return null
        val command = CodingAgents.byId(agentId)?.command ?: return null
        return "$command $subcommand"
    }

    /** The usage dashboard, else the usage guide of the agent, else null: an agent's home page is neither. */
    fun billingUrl(agentId: String): String? = dashboardUrls[agentId] ?: guideUrls[agentId]

    /** True for a link AgentHub itself offers; a cached link from an older version (an agent's home page) is not. */
    fun isKnownUsageUrl(url: String): Boolean =
        url in dashboardUrls.values || url in guideUrls.values || url == API_USAGE_URL

    /** The label of the button that opens [url]: a guide page says so, anything else is a dashboard. */
    fun linkLabel(url: String?): String =
        if (url != null && url in guideUrls.values) "Open Usage Guide" else "Open Usage Dashboard"

    fun localOnlyNote(agentId: String, liveEnabled: Boolean = false): String {
        if (liveEnabled && agentId in liveCapableAgentIds) {
            return "Live quota is currently unavailable; showing local session activity below."
        }
        return localOnlyNotes[agentId]
            ?: "This agent does not write provider quota limits to local files. Locally measured session activity is shown below."
    }
}
