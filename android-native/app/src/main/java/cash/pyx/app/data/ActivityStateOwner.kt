package cash.pyx.app.data

import cash.pyx.app.nativeapi.AndroidError
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.Payment
import cash.pyx.app.nativeapi.PaymentDetails
import cash.pyx.app.nativeapi.PaymentPage
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Narrow production seam for activity history and payment-detail reads. */
interface ActivityRepository {
    suspend fun page(clientHandle: Long, cursor: String?, pageSize: Int = 50): NativeResult<PaymentPage>
    suspend fun details(clientHandle: Long, operationId: String): NativeResult<PaymentDetails>
}

sealed interface ActivityDetailState {
    data object Closed : ActivityDetailState
    data class Loading(val operationId: String) : ActivityDetailState
    data class Open(val payment: Payment) : ActivityDetailState
    data class Error(val operationId: String, val message: String) : ActivityDetailState
}

data class ActivityState(
    val clientHandle: Long? = null,
    val payments: List<Payment> = emptyList(),
    val nextCursor: String? = null,
    val initialized: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val detail: ActivityDetailState = ActivityDetailState.Closed,
)

/**
 * Owns activity paging and detail races independently of a screen or JNI facade.
 *
 * Generation checks are intentional even though jobs are cancelled: a native or
 * test repository may finish non-cooperatively after cancellation.
 */
class ActivityStateOwner(
    private val repository: ActivityRepository,
    private val scope: CoroutineScope,
) : Closeable {
    private val mutableState = MutableStateFlow(ActivityState())
    val state: StateFlow<ActivityState> = mutableState.asStateFlow()

    private var pageJob: Job? = null
    private var detailJob: Job? = null
    private var pageGeneration = 0L
    private var detailGeneration = 0L
    private var closed = false

    fun selectClient(clientHandle: Long?) {
        if (closed || mutableState.value.clientHandle == clientHandle) return
        pageGeneration++
        detailGeneration++
        pageJob?.cancel()
        detailJob?.cancel()
        pageJob = null
        detailJob = null
        mutableState.value = ActivityState(clientHandle = clientHandle)
    }

    fun loadNextPage(reset: Boolean = false) {
        if (closed) return
        val current = mutableState.value
        val client = current.clientHandle?.takeIf { it > 0 } ?: return
        if (!reset && (current.loading || (current.initialized && current.nextCursor == null))) return

        if (reset) pageJob?.cancel() else if (pageJob?.isActive == true) return
        val generation = ++pageGeneration
        val cursor = if (reset || !current.initialized) null else current.nextCursor
        // A refresh keeps useful cached rows visible until its replacement succeeds.
        mutableState.value = current.copy(loading = true, error = null)
        pageJob = scope.launch {
            when (val result = repository.page(client, cursor)) {
                is NativeResult.Success -> {
                    if (!ownsPage(client, generation)) return@launch
                    val latest = mutableState.value
                    mutableState.value = latest.copy(
                        payments = if (reset || !current.initialized) {
                            ActivityMerge.page(emptyList(), result.value.payments)
                        } else {
                            ActivityMerge.page(latest.payments, result.value.payments)
                        },
                        nextCursor = result.value.nextCursor,
                        initialized = true,
                        loading = false,
                        error = null,
                    )
                }
                is NativeResult.Failure -> if (ownsPage(client, generation)) {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        error = result.error.userMessage,
                    )
                }
            }
            if (ownsPage(client, generation)) pageJob = null
        }
    }

    fun openDetail(operationId: String) {
        if (closed || operationId.isBlank()) return
        val client = mutableState.value.clientHandle?.takeIf { it > 0 } ?: return
        requestDetail(client, operationId, keepExistingOnFailure = false)
    }

    fun dismissDetail() {
        if (closed) return
        detailGeneration++
        detailJob?.cancel()
        detailJob = null
        mutableState.value = mutableState.value.copy(detail = ActivityDetailState.Closed)
    }

    /** Applies stream data synchronously; a potentially slow detail refresh runs separately. */
    fun acceptLivePayments(clientHandle: Long, payments: List<Payment>) {
        if (closed || mutableState.value.clientHandle != clientHandle) return
        val current = mutableState.value
        val open = current.detail as? ActivityDetailState.Open
        val liveDetail = open?.let { selected ->
            payments.firstOrNull { it.operationId == selected.payment.operationId }
        }
        mutableState.value = current.copy(
            payments = if (current.initialized) ActivityMerge.live(current.payments, payments) else current.payments,
            detail = liveDetail?.let(ActivityDetailState::Open) ?: current.detail,
        )
        if (liveDetail != null) requestDetail(clientHandle, liveDetail.operationId, keepExistingOnFailure = true)
    }

    private fun requestDetail(client: Long, operationId: String, keepExistingOnFailure: Boolean) {
        detailJob?.cancel()
        val generation = ++detailGeneration
        if (!keepExistingOnFailure) {
            mutableState.value = mutableState.value.copy(detail = ActivityDetailState.Loading(operationId))
        }
        detailJob = scope.launch {
            when (val result = repository.details(client, operationId)) {
                is NativeResult.Success -> if (ownsDetail(client, operationId, generation)) {
                    val payment = result.value.payment
                    mutableState.value = mutableState.value.copy(
                        detail = if (payment.operationId == operationId) ActivityDetailState.Open(payment)
                        else ActivityDetailState.Error(operationId, INVALID_DETAIL.userMessage),
                    )
                }
                is NativeResult.Failure -> if (ownsDetail(client, operationId, generation) && !keepExistingOnFailure) {
                    mutableState.value = mutableState.value.copy(
                        detail = ActivityDetailState.Error(operationId, result.error.userMessage),
                    )
                }
            }
            if (ownsDetail(client, operationId, generation)) detailJob = null
        }
    }

    private fun ownsPage(client: Long, generation: Long) =
        !closed && pageGeneration == generation && mutableState.value.clientHandle == client

    private fun ownsDetail(client: Long, operationId: String, generation: Long): Boolean {
        if (closed || detailGeneration != generation || mutableState.value.clientHandle != client) return false
        return when (val detail = mutableState.value.detail) {
            is ActivityDetailState.Loading -> detail.operationId == operationId
            is ActivityDetailState.Open -> detail.payment.operationId == operationId
            else -> false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pageGeneration++
        detailGeneration++
        pageJob?.cancel()
        detailJob?.cancel()
        pageJob = null
        detailJob = null
    }

    private object ActivityMerge {
        private val order = compareByDescending<Payment> { it.timestampMillis }.thenBy { it.operationId }
        fun page(existing: List<Payment>, page: List<Payment>) =
            (existing + page).associateBy(Payment::operationId).values.sortedWith(order)
        fun live(existing: List<Payment>, live: List<Payment>) =
            (existing.associateBy(Payment::operationId) + live.associateBy(Payment::operationId)).values.sortedWith(order)
    }

    private companion object {
        val INVALID_DETAIL = AndroidError("invalid_native_response", "Payment details were invalid.", false)
    }
}
