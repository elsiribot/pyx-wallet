package cash.pyx.app

import androidx.test.platform.app.InstrumentationRegistry
import cash.pyx.app.nativeapi.NativeBindings
import cash.pyx.app.nativeapi.NativeRequestCallback
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Offline packaged-JNI ownership regression. This proves deterministic handle generations and
 * exactly-once request terminals; it is intentionally not a heap-profiler or LeakCanary claim.
 */
class NativeOwnershipCycleTest {
    @Test
    fun oneHundredBootstrapShutdownCyclesRotateHandlesAndCompleteCallbacksOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "jni-ownership-100-cycle")
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val terminals = AtomicInteger(0)
        val databaseHandles = linkedSetOf<Long>()

        try {
            // Test order is unspecified; establish an empty process-owned graph first.
            awaitNative(terminals = null, NativeBindings::shutdownAndroidSession)

            repeat(CYCLES) { cycle ->
                val bootstrap = awaitNative(terminals) {
                    NativeBindings.bootstrapAsync(root.absolutePath, it)
                }
                val value = JSONObject(bootstrap)
                assertEquals(
                    "cycle $cycle must remain an uninitialized offline database",
                    "uninitialized",
                    value.getString("status"),
                )
                val handle = value.getLong("databaseHandle")
                assertTrue("cycle $cycle returned a non-positive database handle", handle > 0)
                assertTrue(
                    "cycle $cycle reused a stale database generation: $handle",
                    databaseHandles.add(handle),
                )
                assertTrue(
                    "cycle $cycle did not open the production-path client.db",
                    File(root, "client.db").exists(),
                )
                val repeatedHandle = JSONObject(
                    awaitNative(terminals) { NativeBindings.bootstrapAsync(root.absolutePath, it) },
                ).getLong("databaseHandle")
                assertEquals("cycle $cycle active session did not preserve handle ownership", handle, repeatedHandle)

                val shutdown = JSONObject(awaitNative(terminals, NativeBindings::shutdownAndroidSession))
                assertTrue("cycle $cycle did not confirm shutdown", shutdown.getBoolean("shutdown"))
            }

            assertEquals(CYCLES, databaseHandles.size)
            assertEquals(
                "every initial/repeated bootstrap and shutdown must have one terminal callback",
                CYCLES * 3,
                terminals.get(),
            )
        } finally {
            runCatching { awaitNative(terminals = null, NativeBindings::shutdownAndroidSession) }
            // Test-owned and uninitialized: no wallet entropy or federation state is created.
            root.deleteRecursively()
        }
    }

    private fun awaitNative(
        terminals: AtomicInteger?,
        start: (NativeRequestCallback) -> Long,
    ): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        val failure = AtomicReference<String>()
        val callbackRequestId = AtomicLong(-1)
        val callbackCount = AtomicInteger(0)
        val requestId = start(object : NativeRequestCallback {
            override fun onSuccess(requestId: Long, json: String) {
                callbackRequestId.set(requestId)
                result.set(json)
                callbackCount.incrementAndGet()
                terminals?.incrementAndGet()
                latch.countDown()
            }

            override fun onError(requestId: Long, code: String, message: String, retryable: Boolean) {
                callbackRequestId.set(requestId)
                failure.set("$code: $message")
                callbackCount.incrementAndGet()
                terminals?.incrementAndGet()
                latch.countDown()
            }
        })
        assertTrue("native ownership-cycle callback timed out", latch.await(30, TimeUnit.SECONDS))
        assertEquals("callback must identify its originating request", requestId, callbackRequestId.get())
        assertEquals("request must have exactly one terminal callback", 1, callbackCount.get())
        failure.get()?.let { throw AssertionError("native ownership-cycle call failed: $it") }
        return result.get() ?: throw AssertionError("native ownership-cycle callback returned no payload")
    }

    companion object {
        private const val CYCLES = 100

        @JvmStatic
        @BeforeClass
        fun loadPackagedLibrary() {
            System.loadLibrary("pyx")
        }
    }
}
