package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.PiHomeSupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/** Pi: the native skill directories are `<agent dir>/skills` (default `~/.pi/agent/skills`) and `<project>/.pi/skills`. */
class PiSkillSyncTarget(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "pi"

    override fun globalSkillDirectory(): Path = PiHomeSupport.agentDirectory(userHome).resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        ProjectPathResolver.resolveExistingRoot(project)?.resolve(".pi")?.resolve("skills")

    override fun supportsLinkedSkills(): Boolean = false
}
