package cash.pyx.app.data

import cash.pyx.app.nativeapi.RecoveryEvent
import cash.pyx.app.nativeapi.WalletSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide wallet state. This deliberately stays smaller than the UI's
 * [BootstrapState]: creating a wallet and confirming its seed are screens,
 * whereas readiness and recovery describe the lifetime of the native wallet.
 */
sealed interface WalletApplicationState {
    data object Uninitialized : WalletApplicationState
    data object Loading : WalletApplicationState

    data class Ready(
        val factoryHandle: Long,
        val snapshot: WalletSnapshot,
        val selectedFederationId: String? = snapshot.selected?.federationId,
    ) : WalletApplicationState

    data class Recovering(
        val lastReady: Ready?,
        val message: String,
        val progress: RecoveryEvent? = null,
    ) : WalletApplicationState

    data class Fatal(val message: String) : WalletApplicationState
}

/** Narrow seam used to deterministically exercise startup and retry races. */
fun interface ApplicationBootstrapRepository {
    suspend fun load(): BootstrapState
}

class WalletApplicationStateOwner {
    private val mutableState = MutableStateFlow<WalletApplicationState>(WalletApplicationState.Uninitialized)
    val state: StateFlow<WalletApplicationState> = mutableState.asStateFlow()

    private var loadGeneration = 0L
    private var lastReady: WalletApplicationState.Ready? = null

    /**
     * Starts a generation-guarded load. A cancelled or slower prior request
     * cannot overwrite a newer result, even if its fake/native call ignores cancellation.
     */
    fun load(scope: CoroutineScope, repository: ApplicationBootstrapRepository): Job {
        val generation = ++loadGeneration
        mutableState.value = WalletApplicationState.Loading
        return scope.launch {
            val result = repository.load()
            if (generation == loadGeneration) accept(result)
        }
    }

    fun invalidateLoads() {
        loadGeneration++
    }

    fun accept(bootstrap: BootstrapState) {
        mutableState.value = when (bootstrap) {
            is BootstrapState.Home -> WalletApplicationState.Ready(
                bootstrap.factoryHandle,
                bootstrap.snapshot,
            ).also { lastReady = it }
            is BootstrapState.Onboarding -> WalletApplicationState.Uninitialized
            is BootstrapState.Error -> if (bootstrap.retryable) {
                WalletApplicationState.Recovering(lastReady, bootstrap.message)
            } else {
                WalletApplicationState.Fatal(bootstrap.message)
            }
            is BootstrapState.Unavailable -> WalletApplicationState.Fatal(bootstrap.message)
            BootstrapState.Loading,
            BootstrapState.Creating,
            BootstrapState.Restoring,
            is BootstrapState.SeedConfirmation,
            BootstrapState.LoadingWallet -> WalletApplicationState.Loading
        }
    }

    fun acceptRecovery(progress: RecoveryEvent?) {
        mutableState.value = when {
            progress == null || progress.allFinished -> lastReady ?: WalletApplicationState.Loading
            else -> WalletApplicationState.Recovering(lastReady, "Recovering wallet…", progress)
        }
    }
}
