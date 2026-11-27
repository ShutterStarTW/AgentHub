package com.shutterstar.agenthub

import java.nio.file.Path

/** The terminal command that prints a report file and deletes it, for the shell the IDE terminal runs in. */
internal object ReportFileCommand {
    fun build(
        file: Path,
        wsl: Boolean = WslSupport.isActive(),
        windows: Boolean = OsDetector.isWindows(),
    ): String {
        val path = file.toString()
        return when {
            wsl -> {
                // The terminal runs inside the distribution: the Windows temp file is reached through /mnt/<drive>.
                val quoted = "\"${escapeDoubleQuoted(WslSupport.toWslPath(path))}\""
                "cat $quoted; rm -f $quoted"
            }
            windows -> {
                // Single quotes inside a double-quoted -Command: only `$`, a backtick and `"` need escaping there.
                val quoted = "'" + path.replace("'", "''") + "'"
                val command = "Get-Content -Raw -Encoding UTF8 -LiteralPath $quoted; Remove-Item -LiteralPath $quoted"
                "powershell -NoProfile -Command \"${command.replace("`", "``").replace("$", "`$")}\""
            }
            else -> {
                val quoted = "'" + path.replace("'", "'\\''") + "'"
                "cat $quoted; rm -f $quoted"
            }
        }
    }

    private fun escapeDoubleQuoted(value: String): String = buildString {
        value.forEach { char ->
            if (char == '\\' || char == '"' || char == '$' || char == '`') append('\\')
            append(char)
        }
    }
}
