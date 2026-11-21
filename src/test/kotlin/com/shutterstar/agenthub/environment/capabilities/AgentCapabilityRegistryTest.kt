package com.shutterstar.agenthub.environment.capabilities

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentCapabilityRegistryTest {
    @Test
    fun `should expose only verified shared skill compatibility`() {
        assertEquals(AgentCapabilities.NONE, AgentCapabilityRegistry.capabilitiesFor("amp"))
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("antigravity").supportsMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("antigravity").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("claude").supportsSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("codex").supportsSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("codex").supportsSharedAgentSkills)
        assertFalse(AgentCapabilityRegistry.capabilitiesFor("claude").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cline").supportsInstructions)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cursor").supportsInstructions)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cursor").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("grok").supportsSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("grok").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("grok").supportsProjectMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("opencode").supportsMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("opencode").supportsInstructions)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("copilot").supportsProjectMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("copilot").supportsSharedAgentSkills)
        val kilo = AgentCapabilityRegistry.capabilitiesFor("kilo")
        assertTrue(kilo.supportsSkills && kilo.supportsSharedAgentSkills && kilo.supportsMcp && kilo.supportsProjectMcp)
        assertTrue(kilo.supportsInstructions && kilo.supportsConfig)
        val omp = AgentCapabilityRegistry.capabilitiesFor("omp")
        assertTrue(omp.supportsSkills && omp.supportsSharedAgentSkills && omp.supportsMcp && omp.supportsProjectMcp)
        assertTrue(omp.supportsInstructions && omp.supportsConfig)
        val kimi = AgentCapabilityRegistry.capabilitiesFor("kimi")
        assertTrue(kimi.supportsSkills && kimi.supportsSharedAgentSkills && kimi.supportsMcp && kimi.supportsProjectMcp)
        assertTrue(kimi.supportsInstructions && kimi.supportsConfig)
        listOf("freebuff", "junie", "mimo", "vibe").forEach { agent ->
            val capabilities = AgentCapabilityRegistry.capabilitiesFor(agent)
            assertTrue(capabilities.supportsSkills && capabilities.supportsSharedAgentSkills, agent)
            assertTrue(capabilities.supportsMcp && capabilities.supportsProjectMcp, agent)
            assertTrue(capabilities.supportsInstructions && capabilities.supportsConfig, agent)
        }
        // Cline and Qwen Code read ~/.agents/skills and <project>/.agents/skills themselves (checked in their sources);
        // Claude Code and Kiro CLI do not.
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cline").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("qwen").supportsSharedAgentSkills)
        assertFalse(AgentCapabilityRegistry.capabilitiesFor("kiro").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("kiro").supportsMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("qwen").supportsSkills)
        // Pi reads the shared roots and has project MCP files; OpenClaw has workspace skills and no project MCP; Hermes reads
        // `.agents/skills` only per project (the user-level one needs `skills.external_dirs`) and has no project MCP file.
        val pi = AgentCapabilityRegistry.capabilitiesFor("pi")
        assertTrue(pi.supportsSkills && pi.supportsSharedAgentSkills && pi.supportsMcp && pi.supportsProjectMcp)
        assertTrue(pi.supportsInstructions && pi.supportsConfig)
        val openclaw = AgentCapabilityRegistry.capabilitiesFor("openclaw")
        assertTrue(openclaw.supportsSkills && openclaw.supportsSharedAgentSkills && openclaw.supportsMcp)
        assertFalse(openclaw.supportsProjectMcp)
        assertTrue(openclaw.supportsInstructions && openclaw.supportsConfig)
        val hermes = AgentCapabilityRegistry.capabilitiesFor("hermes")
        assertTrue(hermes.supportsSkills && hermes.supportsMcp && hermes.supportsInstructions && hermes.supportsConfig)
        assertFalse(hermes.supportsSharedAgentSkills || hermes.supportsProjectMcp)
        assertFalse(AgentCapabilityRegistry.capabilitiesFor("unknown-agent").supportsSkills)
    }
}
