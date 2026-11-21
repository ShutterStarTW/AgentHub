package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.HermesHomeSupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/** Hermes Agent: the native skill directories are `<HERMES_HOME>/skills` and `<project>/.hermes/skills` (loaded after `hermes skills trust`). */
class HermesSkillSyncTarget(
    private val userHome: Path = AgentRuntime.userHome(),
    private val windowsRuntime: Boolean = AgentRuntime.isWindowsRuntime(),
) : SkillSyncTarget {
    override val agentId: String = "hermes"

    override fun globalSkillDirectory(): Path = HermesHomeSupport.homeDirectory(userHome, windowsRuntime).resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        ProjectPathResolver.resolveExistingRoot(project)?.resolve(".hermes")?.resolve("skills")

    override fun supportsLinkedSkills(): Boolean = false
}
