package cash.pyx.app.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * The deliberately minimal non-secret record written before an irreversible
 * wallet call. Payment destinations, amounts, tokens, quotes and operation
 * results must never be added here. Correlation and federation IDs allow a
 * restarted app to reconcile the correct native record without exposing payment
 * details.
 */
data class PendingIrreversibleOperation(
    val kind: Kind,
    val startedAtEpochMillis: Long,
    /** Null only for a conservative v1 journal migration. */
    val correlationId: String? = null,
    /** The federation that owns the native operation record. */
    val federationId: String? = null,
    val reconciliationStatus: ReconciliationStatus = ReconciliationStatus.LOCAL_ONLY,
) {
    enum class Kind {
        LIGHTNING_SEND,
        ONCHAIN_SEND,
        ECASH_CREATE,
        ECASH_CLAIM,
    }

    enum class ReconciliationStatus {
        LOCAL_ONLY,
        IN_FLIGHT,
        PENDING,
        NOT_SUBMITTED,
        AMBIGUOUS,
    }
}

/** 128 bits of process-local entropy, encoded without locale-sensitive formatting. */
fun secureOperationCorrelationId(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    return buildString(32) { bytes.forEach { append(HEX[(it.toInt() ushr 4) and 0x0f]); append(HEX[it.toInt() and 0x0f]) } }
}

private const val HEX = "0123456789abcdef"

interface IrreversibleOperationJournal {
    fun read(): PendingIrreversibleOperation?

    /** Must durably commit before returning true. */
    fun begin(operation: PendingIrreversibleOperation): Boolean

    /** Must durably commit before returning true. */
    fun clear(): Boolean
}

/**
 * Deliberate DataStore exception: callers synchronously require a durable true
 * result before invoking an irreversible native operation. A suspend migration
 * would either infect the pre-action boundary or tempt fire-and-forget writes;
 * this tiny non-secret record therefore retains one atomic commit whose failure
 * prevents submission. DataStore remains the default for ordinary preferences.
 */
class SharedPreferencesIrreversibleOperationJournal(context: Context) : IrreversibleOperationJournal {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): PendingIrreversibleOperation? {
        val encodedKind = preferences.getString(KEY_KIND, null) ?: return null
        val startedAt = preferences.getLong(KEY_STARTED_AT, -1L)
        val kind = runCatching { PendingIrreversibleOperation.Kind.valueOf(encodedKind) }.getOrNull()
            ?: return null
        if (startedAt < 0) return null
        val correlation = preferences.getString(KEY_CORRELATION_ID, null)?.takeIf { it.matches(CORRELATION) }
        val federation = preferences.getString(KEY_FEDERATION_ID, null)?.takeIf(String::isNotBlank)
        val status = preferences.getString(KEY_STATUS, null)?.let {
            runCatching { PendingIrreversibleOperation.ReconciliationStatus.valueOf(it) }.getOrNull()
        } ?: PendingIrreversibleOperation.ReconciliationStatus.LOCAL_ONLY
        return PendingIrreversibleOperation(kind, startedAt, correlation, federation, status)
    }

    override fun begin(operation: PendingIrreversibleOperation): Boolean = preferences.edit()
        .putString(KEY_KIND, operation.kind.name)
        .putLong(KEY_STARTED_AT, operation.startedAtEpochMillis)
        .putString(KEY_CORRELATION_ID, operation.correlationId)
        .putString(KEY_FEDERATION_ID, operation.federationId)
        .putString(KEY_STATUS, operation.reconciliationStatus.name)
        .commit()

    override fun clear(): Boolean = preferences.edit().clear().commit()

    private companion object {
        const val PREFERENCES = "irreversible_operation_journal_v1"
        const val KEY_KIND = "kind"
        const val KEY_STARTED_AT = "started_at_epoch_millis"
        const val KEY_CORRELATION_ID = "correlation_id"
        const val KEY_FEDERATION_ID = "federation_id"
        const val KEY_STATUS = "reconciliation_status"
        val CORRELATION = Regex("[0-9a-f]{32}")
    }
}

class IrreversibleOperationReconciliation(
    private val journal: IrreversibleOperationJournal,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutablePending = MutableStateFlow(journal.read())
    val pending: StateFlow<PendingIrreversibleOperation?> = mutablePending.asStateFlow()

    @Synchronized
    fun begin(kind: PendingIrreversibleOperation.Kind, correlationId: String, federationId: String): Boolean {
        if (mutablePending.value != null) return false
        if (!correlationId.matches(Regex("[0-9a-f]{32}")) || federationId.isBlank()) return false
        val operation = PendingIrreversibleOperation(kind, nowEpochMillis(), correlationId, federationId)
        if (!journal.begin(operation)) return false
        mutablePending.value = operation
        return true
    }

    /** Recover a native record when the process died after native commit but before local state was observed. */
    @Synchronized
    fun adopt(operation: PendingIrreversibleOperation): Boolean {
        val current = mutablePending.value
        if (current != null) return current.correlationId == operation.correlationId
        if (operation.correlationId?.matches(Regex("[0-9a-f]{32}")) != true || operation.federationId.isNullOrBlank()) return false
        if (!journal.begin(operation)) return false
        mutablePending.value = operation
        return true
    }

    @Synchronized
    fun updateStatus(correlationId: String, status: PendingIrreversibleOperation.ReconciliationStatus): Boolean {
        val current = mutablePending.value ?: return false
        if (current.correlationId != correlationId) return false
        val updated = current.copy(reconciliationStatus = status)
        if (!journal.begin(updated)) return false
        mutablePending.value = updated
        return true
    }

    @Synchronized
    fun resolved(correlationId: String): Boolean {
        if (mutablePending.value?.correlationId != correlationId) return false
        if (!journal.clear()) return false
        mutablePending.value = null
        return true
    }
}
