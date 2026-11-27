package com.shutterstar.agenthub

import java.io.InputStream

/** The shared ends of every short-lived child process: a bounded read of its output and a stop that reaches its children. */
internal object ProcessSupport {
    /** More output than this is read and thrown away, so a runaway command cannot fill the heap. */
    const val DEFAULT_MAX_OUTPUT_CHARS = 16 * 1024 * 1024

    /**
     * Reads [input] to its end as UTF-8, keeping at most [maxChars] characters. The rest is still drained: a child
     * blocked on a full pipe would otherwise never finish.
     */
    fun readCapped(input: InputStream, maxChars: Int = DEFAULT_MAX_OUTPUT_CHARS): String {
        val result = StringBuilder()
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                val room = maxChars - result.length
                if (room > 0) result.append(buffer, 0, minOf(read, room))
            }
        }
        return result.toString()
    }

    /**
     * Stops [process] and the processes it started: `bash -lc` and `wsl.exe` leave grandchildren running when only
     * the direct process is destroyed.
     */
    fun destroyTree(process: Process) {
        runCatching { process.descendants().toList().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
}
