package com.shutterstar.agenthub

/** The text report of an installed-agents detection, printed in the terminal; ASCII only, see [UpdateReportFormatter]. */
object DetectionReportFormatter {
    /** [installed] is null when the check itself could not run: that is neither "installed" nor "not found". */
    data class Entry(val name: String, val installed: Boolean?)

    private const val RULE = "--------------------------------------------------"
    private const val MAX_NAME_WIDTH = 24

    fun format(entries: List<Entry>, color: Boolean = false): String {
        val lines = mutableListOf(Ansi.paint("Detecting installed agents & companion tools", Ansi.BRIGHT_WHITE, color), Ansi.paint(RULE, Ansi.DIM, color))
        if (entries.isEmpty()) {
            lines += "  No agents to detect."
            return lines.joinToString("\n", postfix = "\n")
        }
        val width = entries.maxOf { it.name.length }.coerceAtMost(MAX_NAME_WIDTH)
        entries.forEach { (name, installed) ->
            val padded = name.padEnd(width)
            lines += when (installed) {
                true -> "  ${Ansi.paint("[+]", Ansi.BRIGHT_GREEN, color)} $padded  ${Ansi.paint("installed", Ansi.BRIGHT_GREEN, color)}"
                false -> "  ${Ansi.paint("[x]", Ansi.YELLOW, color)} $padded  ${Ansi.paint("not found", Ansi.DIM, color)}"
                null -> "  ${Ansi.paint("[?]", Ansi.YELLOW, color)} $padded  ${Ansi.paint("could not check", Ansi.YELLOW, color)}"
            }
        }
        lines += Ansi.paint(RULE, Ansi.DIM, color)
        lines += "  " + summary(entries, color)
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun summary(entries: List<Entry>, color: Boolean): String {
        val installed = entries.count { it.installed == true }
        val missing = entries.count { it.installed == false }
        val unknown = entries.count { it.installed == null }
        return listOfNotNull(
            Ansi.paint("$installed installed", Ansi.BRIGHT_GREEN, color),
            Ansi.paint("$missing not found", Ansi.YELLOW, color),
            Ansi.paint("$unknown could not be checked", Ansi.YELLOW, color).takeIf { unknown > 0 },
        ).joinToString(", ")
    }
}
