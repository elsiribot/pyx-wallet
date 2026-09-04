package cash.pyx.app.data

import java.io.File

/**
 * Chooses the directory whose `client.db` the native wallet opens.
 *
 * The Flutter app created the wallet through path_provider's application
 * documents directory, which on Android is `<dataDir>/app_flutter`, not the
 * `files` directory the frozen contract originally recorded. An install-over
 * upgrade must therefore keep opening the legacy `app_flutter/client.db`
 * RocksDB directory whenever one exists; anything else silently presents an
 * existing wallet as uninitialized. Fresh native installs, which have no
 * legacy database, use the `files` directory. The wallet database is never
 * copied, moved, or rewritten.
 */
object WalletDataDirectory {
    fun resolve(filesDir: File, legacyFlutterDir: File): File =
        if (File(legacyFlutterDir, "client.db").isDirectory) legacyFlutterDir else filesDir
}
