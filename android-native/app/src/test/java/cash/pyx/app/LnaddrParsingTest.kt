package cash.pyx.app

import cash.pyx.app.nativeapi.JniNativeWalletApi
import cash.pyx.app.nativeapi.LnaddrQuote
import cash.pyx.app.nativeapi.NativeResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LnaddrParsingTest {
    private fun address(
        domain: String = "example.com",
        username: String = "alice",
        serverOrigin: String = "https://pay.example.com",
        federationId: String? = "fed1",
        destination: String = "LNURL1DUMMY",
        isPrimary: Boolean = true,
        claimedAtSecs: Long = 100,
    ) = """{"claimedAtSecs":$claimedAtSecs,"destination":"$destination","domain":"$domain","federationId":${federationId?.let { "\"$it\"" } ?: "null"},"isPrimary":$isPrimary,"serverOrigin":"$serverOrigin","username":"$username"}"""

    // --- snapshot ---

    @Test fun `snapshot happy path parses every address field`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrSnapshotAsyncBinding = { _, callback ->
            callback.onSuccess(1, """{"addresses":[${address()}]}"""); 1
        })
        val result = (api.lnaddrSnapshotAsync(1) as NativeResult.Success).value
        assertEquals(1, result.addresses.size)
        val addr = result.addresses.single()
        assertEquals("example.com", addr.domain)
        assertEquals("alice", addr.username)
        assertEquals("https://pay.example.com", addr.serverOrigin)
        assertEquals("fed1", addr.federationId)
        assertEquals("LNURL1DUMMY", addr.destination)
        assertTrue(addr.isPrimary)
        assertEquals(100L, addr.claimedAtSecs)
        assertEquals("alice@example.com", addr.display)
    }

    @Test fun `snapshot address with null federationId parses`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrSnapshotAsyncBinding = { _, callback ->
            callback.onSuccess(2, """{"addresses":[${address(federationId = null)}]}"""); 2
        })
        val result = (api.lnaddrSnapshotAsync(1) as NativeResult.Success).value
        assertNull(result.addresses.single().federationId)
    }

    @Test fun `snapshot address missing a required field is rejected`() = runTest {
        val missingDestination = """{"claimedAtSecs":100,"domain":"example.com","federationId":null,"isPrimary":true,"serverOrigin":"https://pay.example.com","username":"alice"}"""
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrSnapshotAsyncBinding = { _, callback ->
            callback.onSuccess(3, """{"addresses":[$missingDestination]}"""); 3
        })
        val result = api.lnaddrSnapshotAsync(1) as NativeResult.Failure
        assertEquals("invalid_native_response", result.error.code)
    }

    @Test fun `snapshot with an unexpected extra field is rejected`() = runTest {
        val extra = """{"claimedAtSecs":100,"destination":"LNURL1DUMMY","domain":"example.com","extra":"nope","federationId":null,"isPrimary":true,"serverOrigin":"https://pay.example.com","username":"alice"}"""
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrSnapshotAsyncBinding = { _, callback ->
            callback.onSuccess(4, """{"addresses":[$extra]}"""); 4
        })
        assertTrue(api.lnaddrSnapshotAsync(1) is NativeResult.Failure)
    }

    @Test fun `snapshot beyond the 64 address cap is rejected`() = runTest {
        val addresses = (1..65).joinToString(",") { address(username = "user$it") }
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrSnapshotAsyncBinding = { _, callback ->
            callback.onSuccess(5, """{"addresses":[$addresses]}"""); 5
        })
        val result = api.lnaddrSnapshotAsync(1) as NativeResult.Failure
        assertEquals("invalid_native_response", result.error.code)
    }

    @Test fun `snapshot exactly at the 64 address cap succeeds`() = runTest {
        val addresses = (1..64).joinToString(",") { address(username = "user$it") }
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrSnapshotAsyncBinding = { _, callback ->
            callback.onSuccess(6, """{"addresses":[$addresses]}"""); 6
        })
        assertEquals(64, (api.lnaddrSnapshotAsync(1) as NativeResult.Success).value.addresses.size)
    }

    @Test fun `snapshot rejects an over-long domain or username`() = runTest {
        val longDomain = address(domain = "d".repeat(65))
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrSnapshotAsyncBinding = { _, callback ->
            callback.onSuccess(7, """{"addresses":[$longDomain]}"""); 7
        })
        assertTrue(api.lnaddrSnapshotAsync(1) is NativeResult.Failure)
    }

    // --- claim ---

    @Test fun `claim parses a single address object`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrClaimAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(8, address()); 8
        })
        val result = (api.lnaddrClaimAsync(1, "https://pay.example.com", "example.com", "alice") as NativeResult.Success).value
        assertEquals("example.com", result.domain)
        assertEquals("alice", result.username)
    }

    // --- discover ---

    @Test fun `discover happy path parses servers`() = runTest {
        val server = """{"domains":["pay.example.com"],"freeDomains":["pay.example.com"],"name":"Example","origin":"https://pay.example.com"}"""
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrDiscoverAsyncBinding = { _, callback ->
            callback.onSuccess(9, """{"servers":[$server]}"""); 9
        })
        val result = (api.lnaddrDiscoverAsync(1) as NativeResult.Success).value
        val discovered = result.servers.single()
        assertEquals("https://pay.example.com", discovered.origin)
        assertEquals("Example", discovered.name)
        assertEquals(listOf("pay.example.com"), discovered.domains)
        assertEquals(listOf("pay.example.com"), discovered.freeDomains)
    }

    @Test fun `discover beyond the 32 server cap is rejected`() = runTest {
        val server = { n: Int -> """{"domains":[],"freeDomains":[],"name":"Server $n","origin":"https://s$n.example.com"}""" }
        val servers = (1..33).joinToString(",") { server(it) }
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrDiscoverAsyncBinding = { _, callback ->
            callback.onSuccess(10, """{"servers":[$servers]}"""); 10
        })
        assertTrue(api.lnaddrDiscoverAsync(1) is NativeResult.Failure)
    }

    // --- quote ---

    @Test fun `quote free state parses with no price`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(11, """{"priceMsat":null,"state":"free"}"""); 11
        })
        val result = (api.lnaddrQuoteAsync(1, "https://pay.example.com", "example.com", "alice") as NativeResult.Success).value
        assertEquals(LnaddrQuote.Free, result)
    }

    @Test fun `quote paid state requires a priceMsat`() = runTest {
        val missingPrice = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(12, """{"priceMsat":null,"state":"paid"}"""); 12
        })
        val result = missingPrice.lnaddrQuoteAsync(1, "https://pay.example.com", "example.com", "alice") as NativeResult.Failure
        assertEquals("invalid_native_response", result.error.code)

        val withPrice = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(13, """{"priceMsat":1000,"state":"paid"}"""); 13
        })
        val success = withPrice.lnaddrQuoteAsync(1, "https://pay.example.com", "example.com", "alice") as NativeResult.Success
        assertEquals(LnaddrQuote.Paid(1000), success.value)
    }

    @Test fun `quote paid state rejects a non-positive priceMsat`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(14, """{"priceMsat":0,"state":"paid"}"""); 14
        })
        assertTrue(api.lnaddrQuoteAsync(1, "https://pay.example.com", "example.com", "alice") is NativeResult.Failure)
    }

    @Test fun `quote taken state parses`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(15, """{"priceMsat":null,"state":"taken"}"""); 15
        })
        val result = (api.lnaddrQuoteAsync(1, "https://pay.example.com", "example.com", "alice") as NativeResult.Success).value
        assertEquals(LnaddrQuote.Taken, result)
    }

    @Test fun `quote reserved and rate_limited states parse`() = runTest {
        val reserved = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(16, """{"priceMsat":null,"state":"reserved"}"""); 16
        })
        assertEquals(LnaddrQuote.Reserved, (reserved.lnaddrQuoteAsync(1, "o", "d", "u") as NativeResult.Success).value)

        val rateLimited = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(17, """{"priceMsat":null,"state":"rate_limited"}"""); 17
        })
        assertEquals(LnaddrQuote.RateLimited, (rateLimited.lnaddrQuoteAsync(1, "o", "d", "u") as NativeResult.Success).value)
    }

    @Test fun `quote invalid state parses without a reason field`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(18, """{"priceMsat":null,"state":"invalid"}"""); 18
        })
        val result = (api.lnaddrQuoteAsync(1, "o", "d", "u") as NativeResult.Success).value as LnaddrQuote.Invalid
        assertEquals("", result.reason)
    }

    @Test fun `quote invalid state carries an optional reason`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(19, """{"priceMsat":null,"reason":"bad_domain","state":"invalid"}"""); 19
        })
        val result = (api.lnaddrQuoteAsync(1, "o", "d", "u") as NativeResult.Success).value as LnaddrQuote.Invalid
        assertEquals("bad_domain", result.reason)
    }

    @Test fun `quote with an unknown state is rejected`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrQuoteAsyncBinding = { _, _, _, _, callback ->
            callback.onSuccess(20, """{"priceMsat":null,"state":"bogus"}"""); 20
        })
        assertTrue(api.lnaddrQuoteAsync(1, "o", "d", "u") is NativeResult.Failure)
    }

    // --- mutations ---

    @Test fun `set primary, release and repoint parse the ok mutation`() = runTest {
        val setPrimary = JniNativeWalletApi(libraryLoader = {}, lnaddrSetPrimaryAsyncBinding = { _, _, _, callback ->
            callback.onSuccess(21, """{"ok":true}"""); 21
        })
        assertTrue((setPrimary.lnaddrSetPrimaryAsync(1, "example.com", "alice") as NativeResult.Success).value.ok)

        val release = JniNativeWalletApi(libraryLoader = {}, lnaddrReleaseAsyncBinding = { _, _, _, callback ->
            callback.onSuccess(22, """{"ok":true}"""); 22
        })
        assertTrue((release.lnaddrReleaseAsync(1, "example.com", "alice") as NativeResult.Success).value.ok)

        val repoint = JniNativeWalletApi(libraryLoader = {}, lnaddrRepointAsyncBinding = { _, _, _, callback ->
            callback.onSuccess(23, """{"ok":true}"""); 23
        })
        assertTrue((repoint.lnaddrRepointAsync(1, "example.com", "alice") as NativeResult.Success).value.ok)
    }

    // --- recover ---

    @Test fun `recovery count is non-negative`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrRecoverAsyncBinding = { _, callback ->
            callback.onSuccess(24, """{"recovered":3}"""); 24
        })
        assertEquals(3L, (api.lnaddrRecoverAsync(1) as NativeResult.Success).value.recovered)
    }

    @Test fun `negative recovery count is rejected`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, lnaddrRecoverAsyncBinding = { _, callback ->
            callback.onSuccess(25, """{"recovered":-1}"""); 25
        })
        val result = api.lnaddrRecoverAsync(1) as NativeResult.Failure
        assertEquals("invalid_native_response", result.error.code)
    }
}
