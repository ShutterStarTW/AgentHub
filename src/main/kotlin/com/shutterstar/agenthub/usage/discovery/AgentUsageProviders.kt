package com.shutterstar.agenthub.usage.discovery

/**
 * Registry of built-in usage providers, rebuilt per access so Windows <-> WSL runtime switches
 * pick up the active home directory. Live providers are listed before local fallbacks for the
 * same agent.
 */
object AgentUsageProviders {
    val all: List<AgentUsageProvider>
        get() = listOf(
            AntigravityCliUsageProvider(),
            ClaudeCliUsageProvider(),
            ClaudeLiveUsageProvider(),
            ClaudeLocalUsageCacheProvider(),
            CodexAppServerUsageProvider(),
            CodexLiveUsageProvider(),
            CodexLocalRateLimitProvider(),
            CopilotLiveUsageProvider(),
            CursorCliStatusProvider(),
            CursorLiveUsageProvider(),
            GrokLiveUsageProvider(),
            HermesCliUsageProvider(),
            KiroCliUsageProvider(),
            KiroCliStatusProvider(),
            OmpCliUsageProvider(),
            OpenClawCliUsageProvider(),
        )

    val agentIds: Set<String> = linkedSetOf(
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
}
