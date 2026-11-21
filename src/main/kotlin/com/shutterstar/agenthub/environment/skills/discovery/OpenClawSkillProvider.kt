package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.discovery.OpenClawLayout
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * OpenClaw skills (docs `tools/skills`): the managed `<state>/skills` (`~/.openclaw/skills`) and the per-agent
 * `agents/<id>/agent/workshop-skills` are global; `<workspace>/skills` belongs to the agent workspace, which is the
 * OpenClaw "project". `~/.agents/skills` and `<workspace>/.agents/skills` are the `SharedSkillProvider`'s. Bundled and
 * plugin skills and `skills.load.extraDirs` are not modelled.
 */
class OpenClawSkillProvider(
    userHome: Path = AgentRuntime.userHome(),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()
    private val layout = OpenClawLayout(userHome)

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val budget = scanner.newBudget()
        val roots = listOf(layout.stateDirectory.resolve(SKILLS_DIRECTORY)) +
            layout.agentIds().map { layout.stateDirectory.resolve(OpenClawLayout.AGENTS_DIRECTORY).resolve(it).resolve("agent").resolve(WORKSHOP_DIRECTORY) }
        return roots.flatMap { root ->
            scanner.discover(
                root = root,
                agentId = agentId,
                scope = SkillScope.GLOBAL,
                shared = false,
                projectName = null,
                requireValidMetadata = true,
                budget = budget,
            )
        }.distinctBy { it.path }
    }

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        if (layout.agentsWorkingIn(projectRoot).isEmpty()) return emptyList()
        return scanner.discover(
            root = projectRoot.resolve(SKILLS_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.PROJECT,
            shared = false,
            projectName = project.name,
            requireValidMetadata = true,
        )
    }

    private companion object {
        const val AGENT_ID = "openclaw"
        const val SKILLS_DIRECTORY = "skills"
        const val WORKSHOP_DIRECTORY = "workshop-skills"
    }
}
