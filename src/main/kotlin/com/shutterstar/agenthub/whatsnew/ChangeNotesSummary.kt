package com.shutterstar.agenthub.whatsnew

/** Plain-text bullets of one version's block in the `plugin.xml` change-notes HTML. Pure JDK. */
internal object ChangeNotesSummary {
    private const val MAX_ITEMS = 3
    private const val MAX_ITEM_CHARACTERS = 160
    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private val NUMERIC_ENTITY = Regex("&#(\\d+);")
    private val NAMED_ENTITIES = mapOf("&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&nbsp;" to " ")

    private const val PLUGIN_ID_TAG = "<id>com.shutterstar.agenthub</id>"
    private val CHANGE_NOTES = Regex("""<change-notes>\s*<!\[CDATA\[(.*?)]]>\s*</change-notes>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The `<change-notes>` of this plugin's own `plugin.xml`, read from the class path: the platform's `PluginId` and
     * `PluginManagerCore` are Kotlin in newer SDKs and a call compiled against them does not link on 2024.1.
     */
    fun ownChangeNotes(classLoader: ClassLoader): String? = runCatching {
        classLoader.getResources("META-INF/plugin.xml").asSequence()
            .map { url -> url.openStream().use { it.readBytes().toString(Charsets.UTF_8) } }
            .firstOrNull { PLUGIN_ID_TAG in it }
            ?.let(::changeNotesFromDescriptor)
    }.getOrNull()

    fun changeNotesFromDescriptor(descriptorXml: String): String? = CHANGE_NOTES.find(descriptorXml)?.groupValues?.get(1)

    fun summarize(changeNotesHtml: String?, version: String): List<String> {
        if (changeNotesHtml.isNullOrBlank() || version.isBlank()) return emptyList()
        val block = Regex("<p>\\s*<b>\\s*${Regex.escape(version)}\\s*</b>\\s*</p>\\s*<ul>(.*?)</ul>", RegexOption.DOT_MATCHES_ALL)
            .find(changeNotesHtml)?.groupValues?.get(1) ?: return emptyList()
        return Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(block)
            .map { plainText(it.groupValues[1]) }
            .filter { it.isNotEmpty() }
            .take(MAX_ITEMS)
            .map { if (it.length > MAX_ITEM_CHARACTERS) it.take(MAX_ITEM_CHARACTERS - 1).trimEnd() + "…" else it }
            .toList()
    }

    private fun plainText(html: String): String {
        var text = TAGS.replace(html, "")
        NAMED_ENTITIES.forEach { (entity, replacement) -> text = text.replace(entity, replacement) }
        text = NUMERIC_ENTITY.replace(text) { match -> match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value }
        return SPACES.replace(text.replace("&amp;", "&"), " ").trim()
    }
}
