package com.shutterstar.agenthub.environment.mcp.discovery

/**
 * Normalisation for agents that name the transport `transport` (Hermes Agent, OpenClaw) and switch servers off with
 * `enabled: false`: the shared parser reads `type` and has no enabled flag. A disabled server is not started by the agent,
 * so it is left out; an explicit `type` wins over `transport`.
 */
internal object McpServerFields {
    fun normalized(servers: JsonValue?): JsonValue? {
        val container = servers as? JsonObject ?: return servers
        return JsonObject(
            container.fields.mapNotNull { (name, value) ->
                val server = value as? JsonObject ?: return@mapNotNull name to value
                if ((server.fields[ENABLED_FIELD] as? JsonBoolean)?.value == false) return@mapNotNull null
                val transport = (server.fields[TRANSPORT_FIELD] as? JsonString)?.value
                name to if (transport != null && TYPE_FIELD !in server.fields) JsonObject(server.fields + (TYPE_FIELD to JsonString(transport))) else server
            }.toMap(),
        )
    }

    private const val ENABLED_FIELD = "enabled"
    private const val TRANSPORT_FIELD = "transport"
    private const val TYPE_FIELD = "type"
}
