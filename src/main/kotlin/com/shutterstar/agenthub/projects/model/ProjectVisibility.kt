package com.shutterstar.agenthub.projects.model

/**
 * Read-time view of the project index restricted to visible (installed) agents: drops the
 * relations of hidden agents, drops projects left without any agent, and recomputes each
 * project's `lastActivity` from the remaining relations so a removed agent's recent sessions
 * do not keep a project at the top of the list.
 */
object ProjectVisibility {
    /**
     * Drops the empty sessions ([AgentSession.isEmpty]); an agent left without sessions and a project left without
     * agents drop out too, and counts and `lastActivity` are recomputed from what remains.
     */
    fun withoutEmptySessions(projects: List<DiscoveredProject>): List<DiscoveredProject> {
        if (projects.none { project -> project.agents.any { agent -> agent.sessions.any { it.isEmpty } } }) return projects
        return projects.mapNotNull { project ->
            val agents = project.agents.mapNotNull { agent ->
                val sessions = agent.sessions.filterNot { it.isEmpty }
                when {
                    sessions.size == agent.sessions.size -> agent
                    sessions.isEmpty() -> null
                    else -> agent.copy(
                        sessionCount = sessions.size,
                        sessions = sessions,
                        lastActivity = sessions.mapNotNull { it.updatedAt ?: it.startedAt }.maxOrNull(),
                    )
                }
            }
            if (agents.isEmpty()) {
                null
            } else if (agents == project.agents) {
                project
            } else {
                project.copy(agents = agents, lastActivity = agents.mapNotNull { it.lastActivity }.maxOrNull())
            }
        }.sortedWith(ProjectComparators.discoveredProjectByRecency)
    }

    fun filter(projects: List<DiscoveredProject>, isAgentVisible: (String) -> Boolean): List<DiscoveredProject> {
        var changed = false
        val visible = projects.mapNotNull { project ->
            val visibleAgents = project.agents.filter { isAgentVisible(it.agentId) }
            when {
                visibleAgents.size == project.agents.size -> project
                visibleAgents.isEmpty() -> {
                    changed = true
                    null
                }
                else -> {
                    changed = true
                    project.copy(
                        agents = visibleAgents,
                        lastActivity = visibleAgents.mapNotNull { it.lastActivity }.maxOrNull(),
                    )
                }
            }
        }
        return if (changed) visible.sortedWith(ProjectComparators.discoveredProjectByRecency) else visible
    }
}
