package cash.pyx.app

import android.app.Application
import java.io.File

class PyxApplication : Application() {
    private val crashMarker: File get() = File(noBackupFilesDir, CRASH_MARKER)

    override fun onCreate() {
        super.onCreate()
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Persist only the fact of failure. Never persist exception text,
            // stack traces, JNI arguments, paths, or wallet payloads.
            runCatching { crashMarker.createNewFile() }
            if (prior != null) prior.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun hasRootCrashMarker(): Boolean = crashMarker.isFile
    fun clearRootCrashMarker(): Boolean = !crashMarker.exists() || crashMarker.delete()

    internal fun createRootCrashMarkerForTest(): Boolean = crashMarker.createNewFile()

    private companion object { const val CRASH_MARKER = "root_failure" }
}
