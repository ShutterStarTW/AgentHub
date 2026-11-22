package com.shutterstar.agenthub.whatsnew

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ChangeNotesSummaryTest {
    private val notes = """
        <p><b>1.3.9</b></p>
        <ul>
            <li>A new <b>Usage</b> tab &mdash; limits &amp; resets.</li>
            <li>Reads <code>CLAUDE.md</code>
                again&#8230;</li>
            <li>Third.</li>
            <li>Fourth.</li>
        </ul>
        <p><b>1.3.8</b></p>
        <ul>
            <li>Older item.</li>
        </ul>
    """.trimIndent()

    @Test
    fun `returns the plain text of the first items of the requested version`() {
        assertEquals(
            listOf("A new Usage tab — limits & resets.", "Reads CLAUDE.md again…", "Third."),
            ChangeNotesSummary.summarize(notes, "1.3.9"),
        )
    }

    @Test
    fun `picks an older block by version`() {
        assertEquals(listOf("Older item."), ChangeNotesSummary.summarize(notes, "1.3.8"))
    }

    @Test
    fun `unknown version missing or blank notes give nothing`() {
        assertTrue(ChangeNotesSummary.summarize(notes, "9.9.9").isEmpty())
        assertTrue(ChangeNotesSummary.summarize(notes, "1.3").isEmpty())
        assertTrue(ChangeNotesSummary.summarize(null, "1.3.9").isEmpty())
        assertTrue(ChangeNotesSummary.summarize(notes, "").isEmpty())
    }

    @Test
    fun `a long item is shortened`() {
        val long = "<p><b>2.0.0</b></p><ul><li>${"word ".repeat(80)}</li></ul>"
        val item = ChangeNotesSummary.summarize(long, "2.0.0").single()
        assertEquals(160, item.length)
        assertTrue(item.endsWith("…"))
    }

    @Test
    fun `reads the change-notes of the descriptor`() {
        val descriptor = """<idea-plugin><id>x</id><change-notes><![CDATA[
            <p><b>1.0</b></p><ul><li>One.</li></ul>
        ]]></change-notes><depends/></idea-plugin>"""

        val notes = ChangeNotesSummary.changeNotesFromDescriptor(descriptor)

        assertEquals(listOf("One."), ChangeNotesSummary.summarize(notes, "1.0"))
        assertNull(ChangeNotesSummary.changeNotesFromDescriptor("<idea-plugin/>"))
    }

    @Test
    fun `the shipped plugin descriptor yields the notes of its own version`() {
        val descriptorPath = Path.of("src/main/resources/META-INF/plugin.xml")
        val versionPath = Path.of("VERSION.md")
        assumeTrue(Files.exists(descriptorPath) && Files.exists(versionPath))
        val descriptor = Files.readString(descriptorPath)
        val version = Files.readString(versionPath).trim()

        assertTrue(ChangeNotesSummary.summarize(ChangeNotesSummary.changeNotesFromDescriptor(descriptor), version).isNotEmpty())
        assertTrue("<id>com.shutterstar.agenthub</id>" in descriptor)
    }
}
