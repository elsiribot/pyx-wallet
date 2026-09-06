package cash.pyx.app.ui

import cash.pyx.app.nativeapi.AndroidError
import cash.pyx.app.nativeapi.FiatCurrencies
import cash.pyx.app.nativeapi.InputType
import cash.pyx.app.nativeapi.LnAddress
import cash.pyx.app.nativeapi.LnAddressSnapshot
import cash.pyx.app.nativeapi.LnaddrDiscovery
import cash.pyx.app.nativeapi.LnaddrMutation
import cash.pyx.app.nativeapi.LnaddrQuote
import cash.pyx.app.nativeapi.LnaddrRecovery
import cash.pyx.app.nativeapi.LnaddrServer
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.NativeWalletApi
import cash.pyx.app.nativeapi.TransientClosed
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LnAddressStateOwnerTest {
    @Test fun `refresh combines snapshot and discovery once both resolve`() = runTest {
        val api = FakeLnaddrApi()
        val owner = LnAddressStateOwner(api, backgroundScope)

        owner.refresh(1)
        assertTrue(owner.state.value.loading)
        runCurrent()
        api.succeedSnapshot(address("alice"))
        api.succeedDiscovery(server("primal.net"))
        runCurrent()

        assertEquals(listOf("alice"), owner.state.value.addresses.map(LnAddress::username))
        assertEquals(listOf("primal.net"), owner.state.value.servers.map(LnaddrServer::name))
        assertFalse(owner.state.value.loading)
    }

    @Test fun `refresh keeps stale addresses and servers visible while a new refresh is in flight`() = runTest {
        val api = FakeLnaddrApi()
        val owner = LnAddressStateOwner(api, backgroundScope)
        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(address("alice"))
        api.succeedDiscovery(server("primal.net"))
        runCurrent()

        owner.refresh(1)
        runCurrent()
        assertTrue(owner.state.value.loading)
        assertEquals(listOf("alice"), owner.state.value.addresses.map(LnAddress::username))
        assertEquals(listOf("primal.net"), owner.state.value.servers.map(LnaddrServer::name))
    }

    @Test fun `discovery failure keeps the previous server list while snapshot still updates`() = runTest {
        val api = FakeLnaddrApi()
        val owner = LnAddressStateOwner(api, backgroundScope)
        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(address("alice"))
        api.succeedDiscovery(server("primal.net"))
        runCurrent()

        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(address("bob"))
        api.failDiscovery("discovery offline")
        runCurrent()

        assertEquals(listOf("bob"), owner.state.value.addresses.map(LnAddress::username))
        assertEquals(listOf("primal.net"), owner.state.value.servers.map(LnaddrServer::name))
        assertFalse(owner.state.value.loading)
    }

    @Test fun `snapshot failure surfaces a message but does not clear addresses`() = runTest {
        val api = FakeLnaddrApi()
        val owner = LnAddressStateOwner(api, backgroundScope)
        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(address("alice"))
        api.succeedDiscovery(server("primal.net"))
        runCurrent()

        owner.refresh(1)
        runCurrent()
        api.failSnapshot("boom")
        api.succeedDiscovery(server("primal.net"))
        runCurrent()

        assertEquals(listOf("alice"), owner.state.value.addresses.map(LnAddress::username))
        assertEquals("boom", owner.state.value.message)
        assertFalse(owner.state.value.loading)
    }

    @Test fun `a fully-successful refresh clears a stale message left by an earlier failure`() = runTest {
        val api = FakeLnaddrApi()
        val owner = LnAddressStateOwner(api, backgroundScope)
        owner.refresh(1)
        runCurrent()
        api.failSnapshot("boom")
        api.succeedDiscovery(server("primal.net"))
        runCurrent()
        assertEquals("boom", owner.state.value.message)

        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(address("alice"))
        api.succeedDiscovery(server("primal.net"))
        runCurrent()

        assertNull(owner.state.value.message)
        assertEquals(listOf("alice"), owner.state.value.addresses.map(LnAddress::username))
    }

    @Test fun `claim success triggers a refresh and reports done true`() = runTest {
        val api = FakeLnaddrApi()
        api.claimResult = NativeResult.Success(address("carol"))
        val owner = LnAddressStateOwner(api, backgroundScope)
        var done: Boolean? = null

        owner.claim(1, 2, "https://primal.net", "primal.net", "carol") { done = it }
        runCurrent()
        assertEquals(true, done)
        assertEquals(1, api.claimCalls)
        assertEquals(1, api.snapshotCalls.size)

        api.succeedSnapshot(address("carol"))
        api.succeedDiscovery()
        runCurrent()
        assertEquals(listOf("carol"), owner.state.value.addresses.map(LnAddress::username))
    }

    @Test fun `claim failure sets a clock-hinted message and reports done false`() = runTest {
        val api = FakeLnaddrApi()
        api.claimResult = NativeResult.Failure(AndroidError("unauthorized", "unauthorized: bad signature", false))
        val owner = LnAddressStateOwner(api, backgroundScope)
        var done: Boolean? = null

        owner.claim(1, 2, "https://primal.net", "primal.net", "carol") { done = it }
        runCurrent()

        assertEquals(false, done)
        assertTrue(owner.state.value.message!!.lowercase().contains("clock"))
        assertEquals(0, api.snapshotCalls.size)
    }

    @Test fun `setPrimary success refreshes so the updated primary flag becomes visible`() = runTest {
        val api = FakeLnaddrApi()
        val owner = LnAddressStateOwner(api, backgroundScope)
        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(address("alice", isPrimary = false))
        api.succeedDiscovery()
        runCurrent()

        owner.setPrimary(1, owner.state.value.addresses.single())
        runCurrent()
        assertEquals(1, api.setPrimaryCalls)
        api.succeedSnapshot(address("alice", isPrimary = true))
        api.succeedDiscovery()
        runCurrent()

        assertTrue(owner.state.value.addresses.single().isPrimary)
    }

    @Test fun `release success refreshes and the released address disappears`() = runTest {
        val api = FakeLnaddrApi()
        val owner = LnAddressStateOwner(api, backgroundScope)
        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(address("alice"))
        api.succeedDiscovery()
        runCurrent()

        owner.release(1, owner.state.value.addresses.single())
        runCurrent()
        api.succeedSnapshot()
        api.succeedDiscovery()
        runCurrent()

        assertTrue(owner.state.value.addresses.isEmpty())
    }

    @Test fun `release failure surfaces a message and does not refresh`() = runTest {
        val api = FakeLnaddrApi()
        api.releaseResult = NativeResult.Failure(AndroidError("denied", "denied", false))
        val owner = LnAddressStateOwner(api, backgroundScope)
        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(address("alice"))
        api.succeedDiscovery()
        runCurrent()

        owner.release(1, owner.state.value.addresses.single())
        runCurrent()

        assertEquals("denied", owner.state.value.message)
        assertEquals(listOf("alice"), owner.state.value.addresses.map(LnAddress::username))
        assertEquals(1, api.snapshotCallCount)
    }

    @Test fun `primaryFor returns the primary address within a federation only`() = runTest {
        val api = FakeLnaddrApi()
        val owner = LnAddressStateOwner(api, backgroundScope)
        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(
            address("alice-secondary", federationId = "fed-a", isPrimary = false),
            address("alice-primary", federationId = "fed-a", isPrimary = true),
            address("bob-primary", federationId = "fed-b", isPrimary = true),
        )
        api.succeedDiscovery()
        runCurrent()

        assertEquals("alice-primary", owner.primaryFor("fed-a")?.username)
        assertEquals("bob-primary", owner.primaryFor("fed-b")?.username)
        assertNull(owner.primaryFor("fed-c"))
        assertNull(owner.primaryFor(null))
    }

    @Test fun `clearMessage resets the message`() = runTest {
        val api = FakeLnaddrApi()
        api.releaseResult = NativeResult.Failure(AndroidError("denied", "denied", false))
        val owner = LnAddressStateOwner(api, backgroundScope)
        owner.refresh(1)
        runCurrent()
        api.succeedSnapshot(address("alice"))
        api.succeedDiscovery()
        runCurrent()
        owner.release(1, owner.state.value.addresses.single())
        runCurrent()
        assertEquals("denied", owner.state.value.message)

        owner.clearMessage()
        assertNull(owner.state.value.message)
    }

    @Test fun `recover success refreshes so the recovered addresses become visible`() = runTest {
        val api = FakeLnaddrApi()
        api.recoverResult = NativeResult.Success(LnaddrRecovery(1))
        val owner = LnAddressStateOwner(api, backgroundScope)

        owner.recover(1)
        runCurrent()
        api.succeedSnapshot(address("alice"))
        api.succeedDiscovery(server("primal.net"))
        runCurrent()

        assertEquals(1, api.recoverCalls)
        assertEquals(listOf("alice"), owner.state.value.addresses.map(LnAddress::username))
    }

    @Test fun `quote maps a successful lookup to a ClaimCheck without touching state`() = runTest {
        val api = FakeLnaddrApi()
        api.quoteResult = NativeResult.Success(LnaddrQuote.Free)
        val owner = LnAddressStateOwner(api, backgroundScope)

        val check = owner.quote(1, "https://primal.net", "primal.net", "carol")

        assertEquals(ClaimCheck.Available, check)
        assertNull(owner.state.value.message)
    }

    @Test fun `quote clock-hints an unauthorized failure`() = runTest {
        val api = FakeLnaddrApi()
        api.quoteResult = NativeResult.Failure(AndroidError("unauthorized", "unauthorized: bad signature", false))
        val owner = LnAddressStateOwner(api, backgroundScope)

        val check = owner.quote(1, "https://primal.net", "primal.net", "carol")

        assertTrue((check as ClaimCheck.Error).hint.lowercase().contains("clock"))
    }

    @Test fun `recover failure keeps its message visible and does not refresh`() = runTest {
        val api = FakeLnaddrApi()
        api.recoverResult = NativeResult.Failure(AndroidError("unauthorized", "unauthorized: expired signature", false))
        val owner = LnAddressStateOwner(api, backgroundScope)

        owner.recover(1)
        runCurrent()

        // Nothing changed remotely when recovery fails, so there is nothing to refresh — the
        // failure message (clock-hinted here, since an expired-looking signature is often a
        // device clock drift) must stay visible rather than being wiped by a refresh it never
        // triggers.
        assertEquals(1, api.recoverCalls)
        assertEquals(0, api.snapshotCallCount)
        assertTrue(owner.state.value.message!!.lowercase().contains("clock"))
    }

    private fun address(
        username: String,
        federationId: String? = "fed-a",
        isPrimary: Boolean = false,
    ) = LnAddress(
        domain = "primal.net",
        username = username,
        serverOrigin = "https://primal.net",
        federationId = federationId,
        destination = "lnbc-dest",
        isPrimary = isPrimary,
        claimedAtSecs = 1_000L,
    )

    private fun server(name: String) = LnaddrServer(
        origin = "https://$name",
        name = name,
        domains = listOf(name),
        freeDomains = listOf(name),
    )

    private class FakeLnaddrApi : NativeWalletApi {
        val snapshotCalls = mutableListOf<Continuation<NativeResult<LnAddressSnapshot>>>()
        val discoveryCalls = mutableListOf<Continuation<NativeResult<LnaddrDiscovery>>>()
        var claimResult: NativeResult<LnAddress> = NativeResult.Success(
            LnAddress("primal.net", "carol", "https://primal.net", "fed-a", "lnbc-dest", false, 1_000L),
        )
        var setPrimaryResult: NativeResult<LnaddrMutation> = NativeResult.Success(LnaddrMutation(true))
        var releaseResult: NativeResult<LnaddrMutation> = NativeResult.Success(LnaddrMutation(true))
        var repointResult: NativeResult<LnaddrMutation> = NativeResult.Success(LnaddrMutation(true))
        var recoverResult: NativeResult<LnaddrRecovery> = NativeResult.Success(LnaddrRecovery(0))
        var quoteResult: NativeResult<LnaddrQuote> = NativeResult.Success(LnaddrQuote.Free)

        var claimCalls = 0
        var setPrimaryCalls = 0
        var releaseCalls = 0
        var repointCalls = 0
        var recoverCalls = 0
        var snapshotCallCount = 0
        var discoveryCallCount = 0

        override suspend fun lnaddrSnapshotAsync(factoryHandle: Long): NativeResult<LnAddressSnapshot> {
            snapshotCallCount++
            return suspendCoroutine { snapshotCalls += it }
        }
        override suspend fun lnaddrDiscoverAsync(factoryHandle: Long): NativeResult<LnaddrDiscovery> {
            discoveryCallCount++
            return suspendCoroutine { discoveryCalls += it }
        }
        override suspend fun lnaddrClaimAsync(clientHandle: Long, origin: String, domain: String, username: String): NativeResult<LnAddress> {
            claimCalls++
            return claimResult
        }
        override suspend fun lnaddrSetPrimaryAsync(factoryHandle: Long, domain: String, username: String): NativeResult<LnaddrMutation> {
            setPrimaryCalls++
            return setPrimaryResult
        }
        override suspend fun lnaddrReleaseAsync(factoryHandle: Long, domain: String, username: String): NativeResult<LnaddrMutation> {
            releaseCalls++
            return releaseResult
        }
        override suspend fun lnaddrRepointAsync(clientHandle: Long, domain: String, username: String): NativeResult<LnaddrMutation> {
            repointCalls++
            return repointResult
        }
        override suspend fun lnaddrRecoverAsync(factoryHandle: Long): NativeResult<LnaddrRecovery> {
            recoverCalls++
            return recoverResult
        }
        override suspend fun lnaddrQuoteAsync(factoryHandle: Long, origin: String, domain: String, username: String): NativeResult<LnaddrQuote> =
            quoteResult

        override fun listFiatCurrencies() = NativeResult.Success(FiatCurrencies(emptyList()))
        override fun classifyInput(payload: String) = NativeResult.Success(InputType.UNKNOWN)
        override fun closeTransientHandle(handle: Long, kind: String) = NativeResult.Success(TransientClosed(true))

        fun succeedSnapshot(vararg addresses: LnAddress) {
            snapshotCalls.removeAt(snapshotCalls.size - 1).resume(NativeResult.Success(LnAddressSnapshot(addresses.toList())))
        }
        fun failSnapshot(message: String) {
            snapshotCalls.removeAt(snapshotCalls.size - 1).resume(NativeResult.Failure(AndroidError("err", message, true)))
        }
        fun succeedDiscovery(vararg servers: LnaddrServer) {
            discoveryCalls.removeAt(discoveryCalls.size - 1).resume(NativeResult.Success(LnaddrDiscovery(servers.toList())))
        }
        fun failDiscovery(message: String) {
            discoveryCalls.removeAt(discoveryCalls.size - 1).resume(NativeResult.Failure(AndroidError("err", message, true)))
        }
    }
}
