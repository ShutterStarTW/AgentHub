package com.shutterstar.agenthub.environment.discovery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts the project-tree walks of the environment discovery, per family (`instruction`, `config`, `skill`),
 * so a test can pin how many times one discovery walks the same root.
 */
internal object ProjectWalkStats {
    const val INSTRUCTION = "instruction"
    const val CONFIG = "config"
    const val SKILL = "skill"

    data class Count(val walks: Long, val entries: Long)

    private class Counter {
        val walks = AtomicLong()
        val entries = AtomicLong()
    }

    private val counters = ConcurrentHashMap<String, Counter>()

    fun record(family: String, entries: Int) {
        val counter = counters.computeIfAbsent(family) { Counter() }
        counter.walks.incrementAndGet()
        counter.entries.addAndGet(entries.toLong())
    }

    fun snapshot(): Map<String, Count> = counters.mapValues { (_, counter) -> Count(counter.walks.get(), counter.entries.get()) }

    fun reset() = counters.clear()
}
