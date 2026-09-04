package cash.pyx.app

import androidx.test.platform.app.InstrumentationRegistry
import cash.pyx.app.nativeapi.NativeBindings
import cash.pyx.app.nativeapi.NativeRequestCallback
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Device-side smoke coverage for the packaged Rust library and its real JNI symbols.
 *
 * The database test opens only an isolated, uninitialized production database. It never
 * creates wallet entropy, joins a federation, or accesses the network.
 */
class NativeJniSmokeTest {
    @Test
    fun packagedLibraryResolvesNativeVersionSymbol() {
        val version = NativeBindings.nativeVersion()

        assertTrue("Expected a Cargo semantic version, got: $version", SEMVER.matches(version))
    }

    @Test
    fun packagedLibraryClassifiesLocallyWithoutWalletState() {
        assertTrue(NativeBindings.classifyInput(VALID_MAINNET_BIP21).contains("bitcoin", ignoreCase = true))
        assertTrue(NativeBindings.classifyInput("deterministic-local-classifier-smoke").contains("unknown", ignoreCase = true))
    }

    @Test
    fun packagedLibraryAcceptsChecksumValidRepeatedSeedWordsAndRejectsBadChecksum() {
        val valid = NativeBindings.validateSeedPhrase(
            """["abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","about"]""",
        )
        val invalid = NativeBindings.validateSeedPhrase(
            """["abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","ability"]""",
        )

        assertTrue(valid.contains("\"valid\":true"))
        assertTrue(valid.contains("\"invalidIndices\":[]"))
        assertTrue(invalid.contains("\"valid\":false"))
        assertTrue(invalid.contains("\"checksumValid\":false"))
        assertTrue(invalid.contains("\"invalidIndices\":[]"))
    }

    @Test
    fun packagedLibraryBootstrapsAndShutsDownIsolatedDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstRoot = File(context.cacheDir, "jni-bootstrap-${UUID.randomUUID()}")
        val secondRoot = File(context.cacheDir, "jni-bootstrap-${UUID.randomUUID()}")
        assertTrue(firstRoot.mkdirs())
        assertTrue(secondRoot.mkdirs())

        try {
            assertShutdown()

            val first = awaitNative { NativeBindings.bootstrapAsync(firstRoot.absolutePath, it) }
            assertTrue(first.contains("uninitialized", ignoreCase = true))
            assertTrue("Production bootstrap must create filesDir/client.db", File(firstRoot, "client.db").exists())

            val repeated = awaitNative { NativeBindings.bootstrapAsync(firstRoot.absolutePath, it) }
            assertEquals(first, repeated)
            assertShutdown()
            assertShutdown()

            val second = awaitNative { NativeBindings.bootstrapAsync(secondRoot.absolutePath, it) }
            assertTrue(second.contains("uninitialized", ignoreCase = true))
            assertTrue(File(secondRoot, "client.db").exists())
        } finally {
            runCatching { assertShutdown() }
            // These roots are test-owned, contain no wallet entropy, and are closed above.
            firstRoot.deleteRecursively()
            secondRoot.deleteRecursively()
        }
    }

    private fun assertShutdown() {
        assertTrue(awaitNative(NativeBindings::shutdownAndroidSession).contains("shutdown", ignoreCase = true))
    }

    private fun awaitNative(start: (NativeRequestCallback) -> Long): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        val failure = AtomicReference<String>()
        start(object : NativeRequestCallback {
            override fun onSuccess(requestId: Long, json: String) {
                result.set(json)
                latch.countDown()
            }

            override fun onError(requestId: Long, code: String, message: String, retryable: Boolean) {
                failure.set("$code: $message")
                latch.countDown()
            }
        })
        assertTrue("Native callback timed out", latch.await(30, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Native call failed: $it") }
        return result.get() ?: throw AssertionError("Native callback returned no payload")
    }

    companion object {
        // BIP173 checksum-valid mainnet address, also covered by the Rust BIP21 parser tests.
        private const val VALID_MAINNET_BIP21 =
            "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=0.00000001"

        private val SEMVER = Regex("""\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?""")

        @JvmStatic
        @BeforeClass
        fun loadPackagedLibrary() {
            // A missing/incompatible .so is a test failure, not a skipped device precondition.
            System.loadLibrary("pyx")
        }
    }
}
