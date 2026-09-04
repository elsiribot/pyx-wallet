package cash.pyx.app.data

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletDataDirectoryTest {
    private fun tempDir(): File = createTempDirectory("wallet-dir-test").toFile()

    @Test
    fun `uses files directory when no legacy flutter database exists`() {
        val filesDir = tempDir()
        val legacyDir = File(tempDir(), "app_flutter")
        assertEquals(filesDir, WalletDataDirectory.resolve(filesDir, legacyDir))
    }

    @Test
    fun `uses files directory when legacy directory exists without a database`() {
        val filesDir = tempDir()
        val legacyDir = File(tempDir(), "app_flutter").apply { mkdirs() }
        assertEquals(filesDir, WalletDataDirectory.resolve(filesDir, legacyDir))
    }

    @Test
    fun `uses legacy directory when it contains a rocksdb client database directory`() {
        val filesDir = tempDir()
        val legacyDir = File(tempDir(), "app_flutter")
        File(legacyDir, "client.db").mkdirs()
        assertEquals(legacyDir, WalletDataDirectory.resolve(filesDir, legacyDir))
    }

    @Test
    fun `prefers the legacy wallet over a database in the files directory`() {
        // An upgraded install may have both: the Flutter-created wallet under
        // app_flutter plus an uninitialized database a previous native launch
        // created under files. The legacy wallet must win.
        val filesDir = tempDir()
        File(filesDir, "client.db").mkdirs()
        val legacyDir = File(tempDir(), "app_flutter")
        File(legacyDir, "client.db").mkdirs()
        assertEquals(legacyDir, WalletDataDirectory.resolve(filesDir, legacyDir))
    }

    @Test
    fun `ignores a legacy client db that is a plain file rather than a rocksdb directory`() {
        val filesDir = tempDir()
        val legacyDir = File(tempDir(), "app_flutter").apply { mkdirs() }
        File(legacyDir, "client.db").writeText("not a database")
        assertEquals(filesDir, WalletDataDirectory.resolve(filesDir, legacyDir))
    }
}
