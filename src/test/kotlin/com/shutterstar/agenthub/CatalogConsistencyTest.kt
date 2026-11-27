package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The agent catalog lives in four hand-maintained places; this keeps them from drifting apart. */
class CatalogConsistencyTest {
    private val registered: Set<String> = (CodingAgents.all + CompanionTools.all).map { it.name }.toSet()

    private fun read(path: String): String = Files.readString(Path.of(path))

    private fun tableNames(markdown: String): Set<String> =
        Regex("""^\| \[([^\]]+)]\(""", RegexOption.MULTILINE).findAll(markdown).map { it.groupValues[1] }.toSet()

    @Test
    fun `README and the docs home page list exactly the registered agents and companion tools`() {
        assertEquals(registered, tableNames(read("README.md")), "README.md")
        assertEquals(registered, tableNames(read("docs/index.md")), "docs/index.md")
    }

    @Test
    fun `plugin xml lists exactly the registered agents and companion tools`() {
        val listed = Regex("""<li><a href="[^"]*">([^<]+)</a>""")
            .findAll(read("src/main/resources/META-INF/plugin.xml"))
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(registered, listed)
    }

    @Test
    fun `every coding agent has a docs page, and every agent page belongs to an agent`() {
        // Companion tools are described together in docs/companion-tools.md.
        val ids = CodingAgents.all.map { it.id }.toSet()
        val pages = Files.list(Path.of("docs/agents")).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".md") }.map { it.removeSuffix(".md") }.toList().toSet()
        }

        assertEquals(emptySet<String>(), ids - pages, "agents without a page")
        assertTrue(pages.all { it == "index" || it == "README" || it in ids }, "pages without an agent: ${pages - ids}")
    }
}
