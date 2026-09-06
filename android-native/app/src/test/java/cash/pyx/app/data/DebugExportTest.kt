package cash.pyx.app.data

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugExportTest {
    private fun tempDir(): File = createTempDirectory("debug-export-test").toFile()

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { file ->
        file.entries().asSequence().associate { entry ->
            entry.name to file.getInputStream(entry).readBytes().decodeToString()
        }
    }

    @Test
    fun `zip contains metadata, log files, and the wallet database tree`() {
        val walletDir = tempDir()
        File(walletDir, "logs").mkdirs()
        File(walletDir, "logs/pyx.log.2026-09-06").writeText("log line")
        File(walletDir, "client.db").mkdirs()
        File(walletDir, "client.db/CURRENT").writeText("MANIFEST-000001")
        File(walletDir, "client.db/archive").mkdirs()
        File(walletDir, "client.db/archive/000001.log").writeText("wal")

        val zip = File(tempDir(), "export.zip")
        DebugExport.writeZip(zip, walletDir, "meta")

        assertEquals(
            mapOf(
                "export-info.txt" to "meta",
                "logs/pyx.log.2026-09-06" to "log line",
                "client.db/CURRENT" to "MANIFEST-000001",
                "client.db/archive/000001.log" to "wal",
            ),
            entries(zip),
        )
    }

    @Test
    fun `missing logs and database directories still produce a metadata-only zip`() {
        val zip = File(tempDir(), "export.zip")
        DebugExport.writeZip(zip, tempDir(), "meta")
        assertEquals(mapOf("export-info.txt" to "meta"), entries(zip))
    }

    @Test
    fun `files outside the logs and database trees are not exported`() {
        val walletDir = tempDir()
        File(walletDir, "client.db").mkdirs()
        File(walletDir, "client.db/CURRENT").writeText("db")
        // Sibling wallet files (preferences, crash markers, stray temp files)
        // must never ride along implicitly.
        File(walletDir, "unrelated.txt").writeText("private")

        val zip = File(tempDir(), "export.zip")
        DebugExport.writeZip(zip, walletDir, "meta")

        assertEquals(setOf("export-info.txt", "client.db/CURRENT"), entries(zip).keys)
    }
}
