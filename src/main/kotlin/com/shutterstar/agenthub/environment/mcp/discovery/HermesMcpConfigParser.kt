package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import java.nio.file.Path

/**
 * Reads the `mcp_servers:` mapping of Hermes Agent's `config.yaml` and nothing else. The YAML subset it understands is the
 * one the docs use for servers: block mappings, block and flow (`[a, b]`, `{k: v}`) sequences and maps, quoted and plain
 * scalars, booleans and comments. Anchors, tags, multi-line scalars (`|`, `>`) and multi-document files are not
 * interpreted - such a value is kept as an opaque scalar. The values are handed to the shared server parser, which keeps only
 * names of environment variables and sanitises commands, arguments and URLs.
 */
internal object HermesMcpConfigParser {
    fun parse(content: String, configPath: Path, scope: McpScope, agentId: String = "hermes"): List<RawMcpServer>? = runCatching {
        val lines = content.lineSequence().mapNotNull(::toLine).toList()
        val start = lines.indexOfFirst { it.indent == 0 && it.text.substringBefore(':') == TABLE && it.text.contains(':') }
        if (start < 0) return@runCatching emptyList()
        val rest = lines[start].text.substringAfter(':').trim()
        if (rest.isNotEmpty()) {
            // `mcp_servers: {}` or an inline map; anything else is not a servers mapping.
            val inline = parseInline(rest, 0)
            return@runCatching JsonMcpServerSupport.parseServers(agentId, McpServerFields.normalized(inline), configPath, scope)
        }
        val block = lines.drop(start + 1).takeWhile { it.indent > 0 }
        if (block.isEmpty()) return@runCatching emptyList()
        val value = Cursor(block).parseBlock(block.first().indent, 0)
        JsonMcpServerSupport.parseServers(agentId, McpServerFields.normalized(value), configPath, scope)
    }.getOrNull()

    private data class Line(val indent: Int, val text: String)

    private fun toLine(raw: String): Line? {
        if (raw.isBlank() || raw.contains('\t') && raw.trimStart(' ').startsWith('\t')) return null
        val stripped = stripComment(raw).trimEnd()
        if (stripped.isBlank() || stripped.trim() == "---" || stripped.trim() == "...") return null
        val indent = stripped.length - stripped.trimStart(' ').length
        return Line(indent, stripped.trim())
    }

    private class Cursor(private val lines: List<Line>) {
        private var index = 0

        fun parseBlock(indent: Int, depth: Int): JsonValue {
            if (depth > MAX_DEPTH || index >= lines.size) return JsonScalar
            return if (lines[index].text == "-" || lines[index].text.startsWith("- ")) parseSequence(indent, depth) else parseMapping(indent, depth)
        }

        private fun parseSequence(indent: Int, depth: Int): JsonValue {
            val items = mutableListOf<JsonValue>()
            while (index < lines.size && lines[index].indent >= indent) {
                val line = lines[index]
                if (line.indent > indent) { index++; continue }
                if (!(line.text == "-" || line.text.startsWith("- "))) break
                index++
                val item = line.text.removePrefix("-").trim()
                items += if (item.isEmpty()) JsonScalar else parseInline(item, 0)
            }
            return JsonArray(items)
        }

        private fun parseMapping(indent: Int, depth: Int): JsonValue {
            val fields = linkedMapOf<String, JsonValue>()
            while (index < lines.size && lines[index].indent >= indent) {
                val line = lines[index]
                if (line.indent > indent) { index++; continue }
                val separator = keySeparator(line.text)
                if (separator < 0) { index++; continue }
                val key = unquote(line.text.substring(0, separator).trim())
                val rest = line.text.substring(separator + 1).trim()
                index++
                fields[key] = if (rest.isEmpty()) {
                    val next = lines.getOrNull(index)
                    when {
                        next == null -> JsonScalar
                        next.indent > indent -> parseBlock(next.indent, depth + 1)
                        // A sequence may sit at the same indent as its key.
                        next.indent == indent && (next.text == "-" || next.text.startsWith("- ")) -> parseSequence(indent, depth + 1)
                        else -> JsonScalar
                    }
                } else parseInline(rest, 0)
            }
            return JsonObject(fields)
        }
    }

    private fun parseInline(text: String, depth: Int): JsonValue {
        val value = text.trim()
        if (depth > MAX_DEPTH) return JsonScalar
        return when {
            value.isEmpty() || value == "~" || value == "null" -> JsonScalar
            value == "true" -> JsonBoolean(true)
            value == "false" -> JsonBoolean(false)
            value.startsWith("\"") || value.startsWith("'") -> JsonString(unquote(value))
            value.startsWith("[") && value.endsWith("]") -> JsonArray(splitTopLevel(value.substring(1, value.length - 1)).filter { it.isNotBlank() }.map { parseInline(it, depth + 1) })
            value.startsWith("{") && value.endsWith("}") -> JsonObject(
                splitTopLevel(value.substring(1, value.length - 1)).filter { it.isNotBlank() }.mapNotNull { pair ->
                    val separator = keySeparator(pair.trim())
                    if (separator < 0) null else unquote(pair.trim().substring(0, separator).trim()) to parseInline(pair.trim().substring(separator + 1), depth + 1)
                }.toMap(),
            )
            value.startsWith("|") || value.startsWith(">") || value.startsWith("&") || value.startsWith("*") || value.startsWith("!") -> JsonScalar
            else -> JsonString(value)
        }
    }

    /** The index of the `:` that ends a mapping key (outside quotes, followed by a space or the end), or -1. */
    private fun keySeparator(text: String): Int {
        var quote: Char? = null
        text.forEachIndexed { index, character ->
            when {
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == ':' && (index == text.length - 1 || text[index + 1] == ' ') -> return index
            }
        }
        return -1
    }

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        val current = StringBuilder()
        text.forEach { character ->
            when {
                quote != null -> { current.append(character); if (character == quote) quote = null }
                character == '"' || character == '\'' -> { quote = character; current.append(character) }
                character == '[' || character == '{' -> { depth++; current.append(character) }
                character == ']' || character == '}' -> { depth--; current.append(character) }
                character == ',' && depth == 0 -> { parts += current.toString(); current.clear() }
                else -> current.append(character)
            }
        }
        parts += current.toString()
        return parts
    }

    private fun unquote(value: String): String {
        val text = value.trim()
        if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
            return text.substring(1, text.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        if (text.length >= 2 && text.first() == '\'' && text.last() == '\'') return text.substring(1, text.length - 1).replace("''", "'")
        return text
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        line.forEachIndexed { index, character ->
            when {
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == '#' && (index == 0 || line[index - 1] == ' ') -> return line.substring(0, index)
            }
        }
        return line
    }

    private const val TABLE = "mcp_servers"
    private const val MAX_DEPTH = 16
}
