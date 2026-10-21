package com.shutterstar.agenthub.projects.model

import java.time.Instant

data class DiscoveredProject(
    val identity: ProjectIdentity,
    val name: String,
    val path: String?,
    val gitRoot: String?,
    val gitRemote: String?,
    val currentBranch: String?,
    val agents: List<AgentProject>,
    val lastActivity: Instant?,
    /** Every recorded location of the project is a directory that no longer exists (deleted or moved). */
    val missing: Boolean = false,
)
