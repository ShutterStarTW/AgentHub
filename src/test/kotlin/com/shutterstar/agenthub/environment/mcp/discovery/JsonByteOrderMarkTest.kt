package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JsonByteOrderMarkTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should parse JSON and JSONC that start with a byte order mark`() {
        assertNotNull(SafeJsonParser.parse("﻿{\"a\":1}"))
        assertNotNull(SafeJsonParser.parseJsonc("﻿// note\n{\"a\":1}"))
    }

    @Test
    fun `should discover an Antigravity MCP config saved with a byte order mark`() {
        val globalDirectory = Files.createDirectories(temporaryDirectory.resolve(".gemini/config"))
        Files.writeString(
            globalDirectory.resolve("mcp_config.json"),
            "﻿" + """{"mcpServers":{"local":{"command":"npx"}}}""",
        )

        val server = AntigravityMcpProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals(McpScope.GLOBAL, server.scope)
        assertEquals("local", server.name)
    }
}
