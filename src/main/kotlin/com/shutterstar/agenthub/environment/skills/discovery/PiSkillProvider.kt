package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.PiHomeSupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * Pi skills (docs `skills.md`, 1.5.2): `<agent dir>/skills` (`~/.pi/agent/skills`, `PI_CODING_AGENT_DIR`-aware) and per
 * project `.pi/skills` (applied only after project trust). Pi also reads `~/.agents/skills` and `.agents/skills` - those
 * belong to the `SharedSkillProvider`. The `skills` array of `settings.json`, package skills and standalone Markdown skills
 * are not modelled.
 */
class PiSkillProvider(
    userHome: Path = AgentRuntime.userHome(),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()
    private val agentDirectory = PiHomeSupport.agentDirectory(userHome)

    override fun discoverGlobal(): List<SkillSourceRecord> = scanner.discover(
        root = agentDirectory.resolve(SKILLS_DIRECTORY),
        agentId = agentId,
        scope = SkillScope.GLOBAL,
        shared = false,
        projectName = null,
        requireValidMetadata = true,
    )

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return scanner.discoverNestedProjectSkills(
            projectRoot = projectRoot,
            ownerDirectoryName = NATIVE_DIRECTORY,
            agentId = agentId,
            shared = false,
            projectName = project.name,
            requireValidMetadata = true,
        )
    }

    private companion object {
        const val AGENT_ID = "pi"
        const val NATIVE_DIRECTORY = ".pi"
        const val SKILLS_DIRECTORY = "skills"
    }
}
