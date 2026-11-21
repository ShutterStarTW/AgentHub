package com.shutterstar.agenthub.storage

import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexService
import com.shutterstar.agenthub.environment.persistence.SkillBrowserIndexService
import com.shutterstar.agenthub.projects.persistence.ProjectIndexService
import com.shutterstar.agenthub.usage.persistence.UsageCacheService

/**
 * Decodes the persisted indexes so the tool window's first read finds them ready. Decoding the shared
 * environment index (many MB) is stamp-cached, but whoever asks first pays for it, and the tool window
 * asks on the EDT. Call this from a background thread only.
 */
internal object PersistedIndexWarmup {
    fun warm() {
        // Best effort: a broken cache file must not fail startup; the tool window reports it when it reads.
        runCatching { ProjectIndexService.getInstance().cachedProjects() }
        runCatching { EnvironmentIndexService.getInstance().cachedEnvironments() }
        runCatching { SkillBrowserIndexService.getInstance().cachedSkills("") }
        runCatching { UsageCacheService.getInstance().cachedSnapshots() }
    }
}
