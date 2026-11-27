package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ProcessSupportTest {
    @Test
    fun `reads the whole output when it is under the cap`() {
        assertEquals("héllo\nvilág", ProcessSupport.readCapped("héllo\nvilág".byteInputStream(Charsets.UTF_8), maxChars = 100))
    }

    @Test
    fun `keeps only the first characters of an oversized output and still drains the rest`() {
        val input = ("a".repeat(30_000) + "b".repeat(30_000)).byteInputStream()

        val result = ProcessSupport.readCapped(input, maxChars = 20_000)

        assertEquals("a".repeat(20_000), result)
        assertEquals(-1, input.read(), "the stream was read to its end")
    }

    @Test
    fun `an empty stream gives an empty string`() {
        assertEquals("", ProcessSupport.readCapped(ByteArray(0).inputStream()))
    }

    @Test
    fun `destroyTree stops a running process`() {
        val sleeper = if (OsDetector.isWindows()) {
            ProcessBuilder("ping", "-n", "30", "127.0.0.1")
        } else {
            ProcessBuilder("sleep", "30")
        }.redirectErrorStream(true).start()
        try {
            assertTrue(sleeper.isAlive)

            ProcessSupport.destroyTree(sleeper)

            assertTrue(sleeper.waitFor(10, TimeUnit.SECONDS))
            assertFalse(sleeper.isAlive)
        } finally {
            sleeper.destroyForcibly()
        }
    }
}
