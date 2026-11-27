package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ReportFileCommandTest {
    @Test
    fun `unix prints the file and removes it with single quoted paths`() {
        val plain = Path.of("/tmp/r 1.txt").toString()
        assertEquals(
            "cat '$plain'; rm -f '$plain'",
            ReportFileCommand.build(Path.of("/tmp/r 1.txt"), wsl = false, windows = false),
        )
        val quoted = Path.of("/tmp/it's.txt").toString().replace("'", "'\\''")
        assertEquals(
            "cat '$quoted'; rm -f '$quoted'",
            ReportFileCommand.build(Path.of("/tmp/it's.txt"), wsl = false, windows = false),
        )
    }

    @Test
    fun `wsl reaches the windows temp file through its mnt path`() {
        assertEquals(
            "cat \"/mnt/c/Users/me/AppData/Local/Temp/r.txt\"; rm -f \"/mnt/c/Users/me/AppData/Local/Temp/r.txt\"",
            ReportFileCommand.build(Path.of("C:/Users/me/AppData/Local/Temp/r.txt"), wsl = true, windows = true),
        )
    }

    @Test
    fun `windows reads the file as utf8 and escapes characters PowerShell would expand`() {
        val path = Path.of("/tmp/a\$b.txt").toString().replace("$", "`$")

        assertEquals(
            "powershell -NoProfile -Command \"Get-Content -Raw -Encoding UTF8 -LiteralPath '$path'; " +
                "Remove-Item -LiteralPath '$path'\"",
            ReportFileCommand.build(Path.of("/tmp/a\$b.txt"), wsl = false, windows = true),
        )
    }
}
