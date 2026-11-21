package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.OpenClawLayout
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * OpenClaw: the managed skills directory `<state>/skills` (default `~/.openclaw/skills`) is global; the project skill
 * directory `<workspace>/skills` exists only for a project that is an agent workspace. Symlinked skills are not offered:
 * OpenClaw only follows links listed in `skills.load.allowSymlinkTargets`.
 */
class OpenClawSkillSyncTarget(
    userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "openclaw"
    private val layout = OpenClawLayout(userHome)

    override fun globalSkillDirectory(): Path = layout.stateDirectory.resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return null
        return if (layout.agentsWorkingIn(root).isEmpty()) null else root.resolve("skills")
    }

    override fun supportsLinkedSkills(): Boolean = false
}
