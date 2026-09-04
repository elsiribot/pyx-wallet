package cash.pyx.app.data

import cash.pyx.app.nativeapi.AndroidError
import cash.pyx.app.nativeapi.Contact
import cash.pyx.app.nativeapi.ContactDeleted
import cash.pyx.app.nativeapi.ContactSaved
import cash.pyx.app.nativeapi.ContactsSnapshot
import cash.pyx.app.nativeapi.CurrencyChanged
import cash.pyx.app.nativeapi.FiatCurrencies
import cash.pyx.app.nativeapi.FiatCurrency
import cash.pyx.app.nativeapi.NativeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsAndCurrencyStateOwnerTest {
    private val alice = Contact("Alice", "alice@example.com")
    private val bob = Contact("Bob", "bob@example.com")
    private val failure = NativeResult.Failure(AndroidError("offline", "Wallet is offline", true))

    @Test fun `late cancellation-ignoring contact load cannot replace newer factory`() = runTest {
        val first = CompletableDeferred<NativeResult<ContactsSnapshot>>()
        val repository = object : ContactsRepository {
            override suspend fun listContacts(factoryHandle: Long) = if (factoryHandle == 1L) {
                withContext(NonCancellable) { first.await() }
            } else NativeResult.Success(ContactsSnapshot(listOf(bob)))
            override suspend fun saveContact(factoryHandle: Long, lnurl: String, name: String) = NativeResult.Success(ContactSaved(true))
            override suspend fun deleteContact(factoryHandle: Long, lnurl: String) = NativeResult.Success(ContactDeleted(true))
        }
        val owner = ContactsStateOwner(repository)
        owner.load(backgroundScope, 1)
        runCurrent()
        owner.load(backgroundScope, 2)
        runCurrent()
        first.complete(NativeResult.Success(ContactsSnapshot(listOf(alice))))
        runCurrent()
        assertEquals(listOf(bob), owner.state.value.contacts)
    }

    @Test fun `contact mutation is suppressed on double tap and refetches once`() = runTest {
        val mutation = CompletableDeferred<NativeResult<ContactSaved>>()
        var saveCalls = 0
        var listCalls = 0
        val repository = object : ContactsRepository {
            override suspend fun listContacts(factoryHandle: Long): NativeResult<ContactsSnapshot> {
                listCalls++
                return NativeResult.Success(ContactsSnapshot(listOf(alice)))
            }
            override suspend fun saveContact(factoryHandle: Long, lnurl: String, name: String): NativeResult<ContactSaved> {
                saveCalls++
                return mutation.await()
            }
            override suspend fun deleteContact(factoryHandle: Long, lnurl: String) = NativeResult.Success(ContactDeleted(true))
        }
        val owner = ContactsStateOwner(repository)
        owner.save(backgroundScope, 1, alice.lnurl, alice.name)
        runCurrent()
        assertNull(owner.save(backgroundScope, 1, alice.lnurl, alice.name))
        mutation.complete(NativeResult.Success(ContactSaved(true)))
        runCurrent()
        assertEquals(1, saveCalls)
        assertEquals(1, listCalls)
        assertEquals(listOf(alice), owner.state.value.contacts)
        assertTrue(owner.state.value.message is FeatureMessage.Success)
    }

    @Test fun `failed mutation never refetches and preserves cached contacts`() = runTest {
        var listCalls = 0
        val repository = object : ContactsRepository {
            override suspend fun listContacts(factoryHandle: Long): NativeResult<ContactsSnapshot> {
                listCalls++
                return NativeResult.Success(ContactsSnapshot(listOf(alice)))
            }
            override suspend fun saveContact(factoryHandle: Long, lnurl: String, name: String) = failure
            override suspend fun deleteContact(factoryHandle: Long, lnurl: String) = failure
        }
        val owner = ContactsStateOwner(repository)
        owner.load(backgroundScope, 1)
        runCurrent()
        owner.save(backgroundScope, 1, bob.lnurl, bob.name)
        runCurrent()
        assertEquals(1, listCalls)
        assertEquals(listOf(alice), owner.state.value.contacts)
        assertEquals("Wallet is offline", (owner.state.value.message as FeatureMessage.Failure).text)
    }

    @Test fun `successful mutation with failed refresh keeps cache and warns against replay`() = runTest {
        var listCalls = 0
        val repository = object : ContactsRepository {
            override suspend fun listContacts(factoryHandle: Long): NativeResult<ContactsSnapshot> =
                if (listCalls++ == 0) NativeResult.Success(ContactsSnapshot(listOf(alice))) else failure
            override suspend fun saveContact(factoryHandle: Long, lnurl: String, name: String) = NativeResult.Success(ContactSaved(true))
            override suspend fun deleteContact(factoryHandle: Long, lnurl: String) = NativeResult.Success(ContactDeleted(true))
        }
        val owner = ContactsStateOwner(repository)
        owner.load(backgroundScope, 1)
        runCurrent()
        owner.delete(backgroundScope, 1, alice.lnurl)
        runCurrent()
        assertEquals(listOf(alice), owner.state.value.contacts)
        val message = (owner.state.value.message as FeatureMessage.Failure).text
        assertTrue(message.contains("Contact deleted"))
        assertTrue(message.contains("Refresh the list"))
    }

    @Test fun `invalidated cancellation-ignoring contact result cannot publish`() = runTest {
        val result = CompletableDeferred<NativeResult<ContactsSnapshot>>()
        val repository = object : ContactsRepository {
            override suspend fun listContacts(factoryHandle: Long) = withContext(NonCancellable) { result.await() }
            override suspend fun saveContact(factoryHandle: Long, lnurl: String, name: String) = NativeResult.Success(ContactSaved(true))
            override suspend fun deleteContact(factoryHandle: Long, lnurl: String) = NativeResult.Success(ContactDeleted(true))
        }
        val owner = ContactsStateOwner(repository)
        owner.load(backgroundScope, 1)
        runCurrent()
        owner.invalidate()
        result.complete(NativeResult.Success(ContactsSnapshot(listOf(alice))))
        runCurrent()
        assertTrue(owner.state.value.contacts.isEmpty())
        assertFalse(owner.state.value.loading)
    }

    @Test fun `currency selection failure preserves catalog and does not notify`() = runTest {
        var changed = 0
        val repository = CurrencyRepository(setResult = failure)
        val owner = CurrencySettingsStateOwner(repository)
        owner.load(backgroundScope)
        runCurrent()
        owner.select(backgroundScope, 4, "EUR") { changed++ }
        runCurrent()
        assertEquals(0, changed)
        assertEquals(listOf(repository.usd), owner.state.value.currencies)
        assertEquals("Wallet is offline", (owner.state.value.message as FeatureMessage.Failure).text)
    }

    @Test fun `currency double tap invokes native selection and callback once`() = runTest {
        val gate = CompletableDeferred<NativeResult<CurrencyChanged>>()
        val repository = CurrencyRepository(selectGate = gate)
        val owner = CurrencySettingsStateOwner(repository)
        var changed = 0
        owner.select(backgroundScope, 4, "EUR") { changed++ }
        runCurrent()
        assertNull(owner.select(backgroundScope, 4, "EUR") { changed++ })
        gate.complete(NativeResult.Success(CurrencyChanged("EUR")))
        runCurrent()
        assertEquals(1, repository.setCalls)
        assertEquals(1, changed)
        assertNull(owner.state.value.savingCode)
    }

    @Test fun `invalidated late currency selection cannot notify`() = runTest {
        val gate = CompletableDeferred<NativeResult<CurrencyChanged>>()
        val repository = CurrencyRepository(selectGate = gate, ignoreCancellation = true)
        val owner = CurrencySettingsStateOwner(repository)
        var changed = 0
        owner.select(backgroundScope, 4, "EUR") { changed++ }
        runCurrent()
        owner.invalidate()
        gate.complete(NativeResult.Success(CurrencyChanged("EUR")))
        runCurrent()
        assertEquals(0, changed)
        assertNull(owner.state.value.savingCode)
    }

    private class CurrencyRepository(
        private val setResult: NativeResult<CurrencyChanged> = NativeResult.Success(CurrencyChanged("USD")),
        private val selectGate: CompletableDeferred<NativeResult<CurrencyChanged>>? = null,
        private val ignoreCancellation: Boolean = false,
    ) : CurrencySettingsRepository {
        val usd = FiatCurrency("USD", "US Dollar", "$", 2)
        var setCalls = 0
        override fun listFiatCurrencies() = NativeResult.Success(FiatCurrencies(listOf(usd)))
        override suspend fun setCurrency(factoryHandle: Long, code: String): NativeResult<CurrencyChanged> {
            setCalls++
            val gate = selectGate ?: return setResult
            return if (ignoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
        }
    }
}
