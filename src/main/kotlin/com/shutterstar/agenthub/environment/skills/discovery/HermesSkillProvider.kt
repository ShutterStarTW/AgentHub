package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.HermesHomeSupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * Hermes Agent skills (docs `user-guide/features/skills`): `<HERMES_HOME>/skills` is "the primary directory and source of
 * truth" - bundled, hub-installed and agent-created skills all live there, grouped in category folders (the `.hub` and
 * other dot folders are not skills). Per project `.hermes/skills` (Hermes loads it only after `hermes skills trust`).
 * `.agents/skills` is the `SharedSkillProvider`'s; `skills.external_dirs` and `skills.create_dir` of `config.yaml`, skill
 * bundles and plugin skills are not modelled.
 */
class HermesSkillProvider(
    userHome: Path = AgentRuntime.userHome(),
    windowsRuntime: Boolean = AgentRuntime.isWindowsRuntime(),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()
    private val skillsDirectory = HermesHomeSupport.homeDirectory(userHome, windowsRuntime).resolve(SKILLS_DIRECTORY)

    override fun discoverGlobal(): List<SkillSourceRecord> = scanner.discover(
        root = skillsDirectory,
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
        const val AGENT_ID = "hermes"
        const val NATIVE_DIRECTORY = ".hermes"
        const val SKILLS_DIRECTORY = "skills"
    }
}
