package cash.pyx.app.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import cash.pyx.app.BuildConfig
import cash.pyx.app.PyxApplication
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the user-initiated debug export: the native log directory, the
 * wallet's `client.db` RocksDB directory, and a metadata file, zipped into the
 * app cache so a FileProvider share can hand it to the user's chosen target.
 *
 * The wallet database contains the root entropy — possession of the export is
 * possession of the funds. It must only be produced behind the
 * protected-action gate and leave the device by explicit user share.
 */
object DebugExport {
    const val CACHE_SUBDIR = "debug-export"
    private const val LOGS_DIR = "logs"
    private const val WALLET_DB = "client.db"

    fun create(context: Context): File {
        val walletDir = WalletDataDirectory.resolve(
            context.filesDir,
            File(context.dataDir, "app_flutter"),
        )
        val exportDir = File(context.cacheDir, CACHE_SUBDIR)
        exportDir.deleteRecursively()
        check(exportDir.mkdirs()) { "cannot create export directory" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val output = File(exportDir, "pyx-debug-$stamp.zip")
        writeZip(output, walletDir, metadata(context, walletDir))
        return output
    }

    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Export debug data")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Pure zip assembly; testable on the JVM without an Android context. */
    fun writeZip(output: File, walletDir: File, metadata: String) {
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("export-info.txt"))
            zip.write(metadata.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            addTree(zip, File(walletDir, LOGS_DIR), LOGS_DIR)
            addTree(zip, File(walletDir, WALLET_DB), WALLET_DB)
        }
    }

    private fun addTree(zip: ZipOutputStream, root: File, prefix: String) {
        if (!root.isDirectory) return
        root.walkTopDown().filter { it.isFile }.sorted().forEach { file ->
            // RocksDB compacts concurrently; a file that vanishes mid-walk is
            // skipped rather than failing the whole export.
            runCatching {
                zip.putNextEntry(ZipEntry("$prefix/${file.relativeTo(root).invariantSeparatorsPath}"))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun metadata(context: Context, walletDir: File): String = buildString {
        appendLine("Pyx debug export")
        appendLine("created_utc: ${Instant.now()}")
        appendLine(
            "app_version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}"
        )
        appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("wallet_dir_legacy_flutter: ${walletDir.name == "app_flutter"}")
        val crashMarker = (context.applicationContext as? PyxApplication)?.hasRootCrashMarker()
        appendLine("prior_native_crash_marker: ${crashMarker ?: "unknown"}")
    }
}
