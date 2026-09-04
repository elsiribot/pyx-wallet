package cash.pyx.app.data

import cash.pyx.app.nativeapi.AddressRechecked
import cash.pyx.app.nativeapi.AndroidError
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.OnchainAddress
import cash.pyx.app.nativeapi.OnchainAddresses
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
class OnchainAddressStateOwnerTest {
    @Test fun `client switch clears cache and rejects non-cooperative old load`() = runTest {
        val repository = ControllableRepository()
        val owner = OnchainAddressStateOwner(repository, backgroundScope)
        owner.selectClient(1)
        owner.load()
        runCurrent()

        owner.selectClient(2)
        assertTrue(owner.state.value.addresses.isEmpty())
        owner.load()
        runCurrent()
        repository.loads.single { it.client == 2L }.succeed(address(2, "new"))
        repository.loads.single { it.client == 1L }.succeed(address(1, "old"))
        runCurrent()

        assertEquals(2L, owner.state.value.clientHandle)
        assertEquals(listOf("new"), owner.state.value.addresses.map(OnchainAddress::address))
    }

    @Test fun `old recheck completion cannot start a reload after switch`() = runTest {
        val repository = ControllableRepository()
        val owner = OnchainAddressStateOwner(repository, backgroundScope)
        owner.selectClient(1)
        owner.recheck(4)
        runCurrent()
        owner.selectClient(2)
        repository.rechecks.single().succeed()
        runCurrent()

        assertTrue(repository.loads.isEmpty())
        assertEquals(2L, owner.state.value.clientHandle)
        assertNull(owner.state.value.recheckingTweakIndex)
    }

    @Test fun `same-client forced refresh rejects stale completion`() = runTest {
        val repository = ControllableRepository()
        val owner = OnchainAddressStateOwner(repository, backgroundScope)
        owner.selectClient(3)
        owner.load()
        runCurrent()
        owner.load(force = true)
        runCurrent()

        repository.loads[1].succeed(address(2, "fresh"))
        repository.loads[0].succeed(address(1, "stale"))
        runCurrent()
        assertEquals(listOf("fresh"), owner.state.value.addresses.map(OnchainAddress::address))
    }

    @Test fun `failed refresh retains cached addresses and exposes error`() = runTest {
        val repository = ControllableRepository()
        val owner = OnchainAddressStateOwner(repository, backgroundScope)
        owner.selectClient(4)
        owner.load()
        runCurrent()
        repository.loads.single().succeed(address(1, "cached"))
        runCurrent()

        owner.load(force = true)
        runCurrent()
        repository.loads.last().fail("Offline")
        runCurrent()

        assertEquals(listOf("cached"), owner.state.value.addresses.map(OnchainAddress::address))
        assertEquals("Offline", owner.state.value.error?.userMessage)
        assertTrue(owner.state.value.initialized)
        assertFalse(owner.state.value.loading)
    }

    @Test fun `duplicate loads and rechecks are suppressed`() = runTest {
        val repository = ControllableRepository()
        val owner = OnchainAddressStateOwner(repository, backgroundScope)
        owner.selectClient(5)
        assertTrue(owner.load() != null)
        assertNull(owner.load())
        runCurrent()
        assertEquals(1, repository.loads.size)
        repository.loads.single().succeed(address(1, "one"))
        runCurrent()

        assertTrue(owner.recheck(1) != null)
        assertNull(owner.recheck(1))
        runCurrent()
        assertEquals(1, repository.rechecks.size)
        repository.rechecks.single().succeed()
        runCurrent()
        assertEquals(2, repository.loads.size)
    }

    @Test fun `generation refresh is ignored after its client is no longer selected`() = runTest {
        val repository = ControllableRepository()
        val owner = OnchainAddressStateOwner(repository, backgroundScope)
        owner.selectClient(6)
        owner.selectClient(7)
        owner.refreshAfterMutation(6)
        runCurrent()
        assertTrue(repository.loads.isEmpty())
    }

    private class ControllableRepository : OnchainAddressRepository {
        val loads = mutableListOf<LoadCall>()
        val rechecks = mutableListOf<RecheckCall>()

        override suspend fun addresses(clientHandle: Long): NativeResult<OnchainAddresses> =
            suspendCoroutine { loads += LoadCall(clientHandle, it) }

        override suspend fun recheck(clientHandle: Long, tweakIndex: Long): NativeResult<AddressRechecked> =
            suspendCoroutine { rechecks += RecheckCall(clientHandle, tweakIndex, it) }
    }

    private class LoadCall(
        val client: Long,
        private val continuation: Continuation<NativeResult<OnchainAddresses>>,
    ) {
        fun succeed(vararg addresses: OnchainAddress) =
            continuation.resume(NativeResult.Success(OnchainAddresses(addresses.toList())))
        fun fail(message: String) = continuation.resume(
            NativeResult.Failure(AndroidError("offline", message, true)),
        )
    }

    private class RecheckCall(
        val client: Long,
        val tweak: Long,
        private val continuation: Continuation<NativeResult<AddressRechecked>>,
    ) {
        fun succeed() = continuation.resume(NativeResult.Success(AddressRechecked(true)))
    }

    private companion object {
        fun address(index: Long, value: String) = OnchainAddress(index, value)
    }
}
