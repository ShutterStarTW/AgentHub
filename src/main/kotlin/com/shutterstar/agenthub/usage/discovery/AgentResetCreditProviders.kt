package com.shutterstar.agenthub.usage.discovery

/** Registry of saved-reset providers (attached to the snapshot of the same agent). */
object AgentResetCreditProviders {
    val all: List<AgentResetCreditProvider>
        get() = listOf(
            ClaudeResetCreditsProvider(),
        )
}
