package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.OpenClawLayout
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path

/**
 * OpenClaw instructions (docs `concepts/agent-workspace`, `concepts/system-prompt`): the bootstrap files at the root of an
 * agent workspace - `AGENTS.md` (operating instructions), `SOUL.md`, `USER.md`, `IDENTITY.md` and `MEMORY.md` are injected
 * into the system prompt, `BOOT.md` and `BOOTSTRAP.md` only under the conditions noted on them. They are listed for the
 * project that is an agent workspace; there are no global instruction files. `HEARTBEAT.md` is not read by the runtime and
 * the daily `memory/` notes are agent memory, so neither is listed.
 */
class OpenClawInstructionProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val layout = OpenClawLayout(homeDirectory)

    override fun discoverGlobal(): List<InstructionSource> = emptyList()

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val root = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        if (layout.agentsWorkingIn(root).isEmpty()) return emptyList()
        return FILES.mapNotNull { (name, note) ->
            val type = if (name == "AGENTS.md") InstructionType.AGENTS_MD else InstructionType.AGENT_SPECIFIC
            InstructionFileSupport.source(root.resolve(name), InstructionScope.PROJECT, agentId, type, project.name, note)
        }
    }

    private companion object {
        const val AGENT_ID = "openclaw"
        val FILES = listOf(
            "AGENTS.md" to null,
            "SOUL.md" to null,
            "USER.md" to null,
            "IDENTITY.md" to null,
            "MEMORY.md" to "Loaded only in the main private session.",
            "BOOT.md" to "Read at Gateway startup, and only when the boot-md hook is enabled.",
            "BOOTSTRAP.md" to "Used once, for the first run of a new workspace; OpenClaw deletes it afterwards.",
        )
    }
}
