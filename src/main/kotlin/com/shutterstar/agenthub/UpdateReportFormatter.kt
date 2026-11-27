package com.shutterstar.agenthub

/** The ANSI colors of the terminal reports (the same ones the helper scripts used). */
internal object Ansi {
    const val RESET = "\u001B[0m"
    const val YELLOW = "\u001B[0;33m"
    const val BRIGHT_GREEN = "\u001B[1;32m"
    const val BRIGHT_WHITE = "\u001B[1;37m"
    const val DIM = "\u001B[2m"

    fun paint(text: String, color: String, enabled: Boolean): String = if (enabled) "$color$text$RESET" else text
}

/**
 * The text report of an update check, printed in the terminal. ASCII only (the escape character included):
 * the file is read back by `Get-Content` under Windows PowerShell 5.x, which mangles other characters.
 */
object UpdateReportFormatter {
    data class Entry(val name: String, val info: AgentUpdateInfo)

    private const val RULE = "--------------------------------------------------"
    private const val MAX_NAME_WIDTH = 24

    fun format(entries: List<Entry>, color: Boolean = false): String {
        val lines = mutableListOf(Ansi.paint("Agents & companion tools - versions & updates", Ansi.BRIGHT_WHITE, color), Ansi.paint(RULE, Ansi.DIM, color))
        if (entries.isEmpty()) {
            lines += "  No installed agents to check."
            return lines.joinToString("\n", postfix = "\n")
        }
        val width = entries.maxOf { it.name.length }.coerceAtMost(MAX_NAME_WIDTH)
        entries.forEach { (name, info) ->
            val installed = info.installedVersion ?: "version unknown"
            val padded = name.padEnd(width)
            lines += when (info.status) {
                UpdateStatus.CURRENT ->
                    "  ${Ansi.paint("[+]", Ansi.BRIGHT_GREEN, color)} $padded  ${Ansi.paint(installed, Ansi.BRIGHT_GREEN, color)}"
                UpdateStatus.OUTDATED -> {
                    val available = if (info.latestVersion != null) "-> ${info.latestVersion} available" else "(update available)"
                    "  ${Ansi.paint("[^]", Ansi.YELLOW, color)} $padded  ${Ansi.paint(installed, Ansi.DIM, color)} ${Ansi.paint(available, Ansi.BRIGHT_GREEN, color)}"
                }
                UpdateStatus.UNKNOWN ->
                    "  ${Ansi.paint("[?]", Ansi.YELLOW, color)} $padded  ${Ansi.paint("$installed (could not verify)", Ansi.YELLOW, color)}"
            }
        }
        lines += Ansi.paint(RULE, Ansi.DIM, color)
        lines += "  " + summary(entries, color)
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun summary(entries: List<Entry>, color: Boolean): String {
        val current = entries.count { it.info.status == UpdateStatus.CURRENT }
        val outdated = entries.count { it.info.status == UpdateStatus.OUTDATED }
        val unknown = entries.count { it.info.status == UpdateStatus.UNKNOWN }
        return listOfNotNull(
            Ansi.paint("$current up to date", Ansi.BRIGHT_GREEN, color),
            Ansi.paint("$outdated ${if (outdated == 1) "update" else "updates"} available", Ansi.YELLOW, color).takeIf { outdated > 0 },
            Ansi.paint("$unknown could not be verified", Ansi.YELLOW, color).takeIf { unknown > 0 },
        ).joinToString(", ")
    }
}
