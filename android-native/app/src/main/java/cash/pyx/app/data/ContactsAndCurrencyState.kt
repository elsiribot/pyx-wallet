package cash.pyx.app.data

import cash.pyx.app.nativeapi.Contact
import cash.pyx.app.nativeapi.ContactDeleted
import cash.pyx.app.nativeapi.ContactSaved
import cash.pyx.app.nativeapi.ContactsSnapshot
import cash.pyx.app.nativeapi.CurrencyChanged
import cash.pyx.app.nativeapi.FiatCurrencies
import cash.pyx.app.nativeapi.FiatCurrency
import cash.pyx.app.nativeapi.NativeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface ContactsRepository {
    suspend fun listContacts(factoryHandle: Long): NativeResult<ContactsSnapshot>
    suspend fun saveContact(factoryHandle: Long, lnurl: String, name: String): NativeResult<ContactSaved>
    suspend fun deleteContact(factoryHandle: Long, lnurl: String): NativeResult<ContactDeleted>
}

interface CurrencySettingsRepository {
    fun listFiatCurrencies(): NativeResult<FiatCurrencies>
    suspend fun setCurrency(factoryHandle: Long, code: String): NativeResult<CurrencyChanged>
}

sealed interface FeatureMessage {
    data class Success(val title: String, val detail: String) : FeatureMessage
    data class Failure(val text: String) : FeatureMessage
}

enum class ContactMutation { SAVE, DELETE }

data class ContactsFeatureState(
    val contacts: List<Contact> = emptyList(),
    val loading: Boolean = false,
    val mutation: ContactMutation? = null,
    val message: FeatureMessage? = null,
)

data class CurrencySettingsState(
    val currencies: List<FiatCurrency> = emptyList(),
    val loading: Boolean = false,
    val savingCode: String? = null,
    val message: FeatureMessage? = null,
)

class ContactsStateOwner(private val repository: ContactsRepository) {
    private val mutableState = MutableStateFlow(ContactsFeatureState())
    val state: StateFlow<ContactsFeatureState> = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun load(scope: CoroutineScope, factoryHandle: Long): Job? {
        if (mutableState.value.mutation != null) return null
        val request = ++generation
        job?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, message = null)
        return scope.launch {
            try {
                when (val result = repository.listContacts(factoryHandle)) {
                    is NativeResult.Success -> publish(request) {
                        it.copy(contacts = result.value.contacts, loading = false)
                    }
                    is NativeResult.Failure -> publish(request) {
                        it.copy(loading = false, message = FeatureMessage.Failure(result.error.userMessage))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(request) { it.copy(loading = false, message = FeatureMessage.Failure(UNEXPECTED_ERROR)) }
            } finally {
                if (request == generation) job = null
            }
        }.also { job = it }
    }

    fun save(scope: CoroutineScope, factoryHandle: Long, lnurl: String, name: String): Job? =
        mutate(scope, factoryHandle, ContactMutation.SAVE, "Contact saved") {
            repository.saveContact(factoryHandle, lnurl, name)
        }

    fun delete(scope: CoroutineScope, factoryHandle: Long, lnurl: String): Job? =
        mutate(scope, factoryHandle, ContactMutation.DELETE, "Contact deleted") {
            repository.deleteContact(factoryHandle, lnurl)
        }

    private fun mutate(
        scope: CoroutineScope,
        factoryHandle: Long,
        mutation: ContactMutation,
        title: String,
        call: suspend () -> NativeResult<*>,
    ): Job? {
        if (mutableState.value.mutation != null) return null
        val request = ++generation
        job?.cancel()
        mutableState.value = mutableState.value.copy(loading = false, mutation = mutation, message = null)
        return scope.launch {
            try {
                when (val changed = call()) {
                    is NativeResult.Failure -> publish(request) {
                        it.copy(mutation = null, message = FeatureMessage.Failure(changed.error.userMessage))
                    }
                    is NativeResult.Success -> when (val refreshed = repository.listContacts(factoryHandle)) {
                        is NativeResult.Success -> publish(request) {
                            it.copy(
                                contacts = refreshed.value.contacts,
                                mutation = null,
                                message = FeatureMessage.Success(title, "Contact list refreshed"),
                            )
                        }
                        is NativeResult.Failure -> publish(request) {
                            it.copy(
                                mutation = null,
                                message = FeatureMessage.Failure(
                                    "$title, but the contact list could not be refreshed. Refresh the list before making another change.",
                                ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(request) { it.copy(mutation = null, message = FeatureMessage.Failure(UNEXPECTED_ERROR)) }
            } finally {
                if (request == generation) job = null
            }
        }.also { job = it }
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }

    fun invalidate() {
        generation++
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(loading = false, mutation = null)
    }

    private inline fun publish(request: Long, update: (ContactsFeatureState) -> ContactsFeatureState) {
        if (request == generation) mutableState.value = update(mutableState.value)
    }
}

class CurrencySettingsStateOwner(private val repository: CurrencySettingsRepository) {
    private val mutableState = MutableStateFlow(CurrencySettingsState())
    val state: StateFlow<CurrencySettingsState> = mutableState.asStateFlow()
    private var loadGeneration = 0L
    private var selectGeneration = 0L
    private var loadJob: Job? = null
    private var selectJob: Job? = null

    fun load(scope: CoroutineScope): Job {
        val request = ++loadGeneration
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, message = null)
        return scope.launch {
            try {
                when (val result = repository.listFiatCurrencies()) {
                    is NativeResult.Success -> publishLoad(request) {
                        it.copy(currencies = result.value.currencies, loading = false)
                    }
                    is NativeResult.Failure -> publishLoad(request) {
                        it.copy(loading = false, message = FeatureMessage.Failure(result.error.userMessage))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishLoad(request) { it.copy(loading = false, message = FeatureMessage.Failure(UNEXPECTED_ERROR)) }
            } finally {
                if (request == loadGeneration) loadJob = null
            }
        }.also { loadJob = it }
    }

    fun select(
        scope: CoroutineScope,
        factoryHandle: Long,
        code: String,
        onChanged: (String) -> Unit = {},
    ): Job? {
        // A native setting may still be applied after coroutine cancellation. Serializing selections
        // prevents an older native call from winning after a newer user choice.
        if (selectJob?.isActive == true) return null
        val request = ++selectGeneration
        mutableState.value = mutableState.value.copy(savingCode = code, message = null)
        return scope.launch {
            try {
                when (val result = repository.setCurrency(factoryHandle, code)) {
                    is NativeResult.Success -> if (request == selectGeneration) {
                        mutableState.value = mutableState.value.copy(
                            savingCode = null,
                            message = FeatureMessage.Success("Currency updated", result.value.currencyCode),
                        )
                        onChanged(result.value.currencyCode)
                    }
                    is NativeResult.Failure -> publishSelect(request) {
                        it.copy(savingCode = null, message = FeatureMessage.Failure(result.error.userMessage))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishSelect(request) { it.copy(savingCode = null, message = FeatureMessage.Failure(UNEXPECTED_ERROR)) }
            } finally {
                if (request == selectGeneration) selectJob = null
            }
        }.also { selectJob = it }
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }

    fun invalidate() {
        loadGeneration++
        selectGeneration++
        loadJob?.cancel(); loadJob = null
        selectJob?.cancel(); selectJob = null
        mutableState.value = mutableState.value.copy(loading = false, savingCode = null)
    }

    private inline fun publishLoad(request: Long, update: (CurrencySettingsState) -> CurrencySettingsState) {
        if (request == loadGeneration) mutableState.value = update(mutableState.value)
    }

    private inline fun publishSelect(request: Long, update: (CurrencySettingsState) -> CurrencySettingsState) {
        if (request == selectGeneration) mutableState.value = update(mutableState.value)
    }
}

private const val UNEXPECTED_ERROR = "The wallet operation failed unexpectedly. Try again."
