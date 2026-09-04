package cash.pyx.app.data

import cash.pyx.app.nativeapi.ConnectionState
import cash.pyx.app.nativeapi.GuardianConnectionSnapshot
import cash.pyx.app.nativeapi.GuardianStatus
import cash.pyx.app.nativeapi.NativeBindings
import cash.pyx.app.nativeapi.NativeSubscriptionCallback
import cash.pyx.app.nativeapi.RecoveryEvent
import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface WalletSubscriptionBindings {
    fun subscribeBalance(client: Long, callback: NativeSubscriptionCallback): Long
    fun subscribeConnection(client: Long, callback: NativeSubscriptionCallback): Long
    fun subscribeRecovery(client: Long, callback: NativeSubscriptionCallback): Long
    fun subscribePayments(client: Long, callback: NativeSubscriptionCallback): Long
    fun close(handle: Long)
}

internal object JniWalletSubscriptionBindings : WalletSubscriptionBindings {
    override fun subscribeBalance(client: Long, callback: NativeSubscriptionCallback) = NativeBindings.subscribeBalance(client, callback)
    override fun subscribeConnection(client: Long, callback: NativeSubscriptionCallback) = NativeBindings.subscribeConnection(client, callback)
    override fun subscribeRecovery(client: Long, callback: NativeSubscriptionCallback) = NativeBindings.subscribeRecovery(client, callback)
    override fun subscribePayments(client: Long, callback: NativeSubscriptionCallback) = NativeBindings.subscribePayments(client, callback)
    override fun close(handle: Long) { NativeBindings.closeSubscription(handle) }
}

internal class SubscriptionHandle(private val handle: Long, private val closeNative: (Long) -> Unit) : Closeable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true) && handle > 0) closeNative(handle) }
}

private sealed interface EarlyCallback {
    val handle: Long
    data class Event(override val handle: Long, val json: String) : EarlyCallback
    data class Error(override val handle: Long, val cause: Throwable) : EarlyCallback
}

internal object SubscriptionDtos {
    fun balance(json: String): Long {
        val value = bounded(json)
        if (value.length() != 1 || !value.has("balanceSat")) throw JSONException("balance fields")
        return value.getLong("balanceSat").also { if (it < 0) throw JSONException("balanceSat") }
    }

    fun connection(json: String): GuardianConnectionSnapshot {
        val value = bounded(json)
        val allowed = setOf("guardians", "onlineCount", "totalCount", "requiredCount", "state")
        if (value.keys().asSequence().toSet() != allowed) throw JSONException("connection fields")
        val array = value.getJSONArray("guardians")
        if (array.length() > 256) throw JSONException("guardians")
        val guardians = (0 until array.length()).map { index ->
            val guardian = array.getJSONObject(index)
            if (guardian.keys().asSequence().toSet() != setOf("name", "connected")) throw JSONException("guardian fields")
            val name = guardian.getString("name")
            if (name.isBlank() || name.length > 256) throw JSONException("name")
            GuardianStatus(name, guardian.getBoolean("connected"))
        }
        val online = value.getInt("onlineCount")
        val total = value.getInt("totalCount")
        val required = value.getInt("requiredCount")
        val expectedRequired = if (total > 0) total - (total - 1) / 3 else 0
        if (online < 0 || total != guardians.size || required != expectedRequired || online > total || guardians.count { it.connected } != online) throw JSONException("counts")
        val state = when (value.getString("state")) {
            "connected" -> ConnectionState.CONNECTED
            "degraded" -> ConnectionState.DEGRADED
            "offline" -> ConnectionState.OFFLINE
            else -> throw JSONException("state")
        }
        val expectedState = when { online == 0 -> ConnectionState.OFFLINE; online == total -> ConnectionState.CONNECTED; online >= required -> ConnectionState.DEGRADED; else -> ConnectionState.OFFLINE }
        if (state != expectedState) throw JSONException("state")
        return GuardianConnectionSnapshot(guardians, online, total, required, state)
    }

    fun recovery(json: String): RecoveryEvent {
        val value = bounded(json)
        if (value.keys().asSequence().toSet() != setOf("moduleId", "complete", "total", "finished", "aggregateComplete", "aggregateTotal", "allFinished")) throw JSONException("recovery fields")
        fun nonnegative(key: String) = value.getLong(key).also { if (it < 0) throw JSONException(key) }
        val module = nonnegative("moduleId"); val complete = nonnegative("complete"); val total = nonnegative("total")
        val aggregateComplete = nonnegative("aggregateComplete"); val aggregateTotal = nonnegative("aggregateTotal")
        if (complete > total || aggregateComplete > aggregateTotal) throw JSONException("progress")
        val finished = value.get("finished") as? Boolean ?: throw JSONException("finished")
        val allFinished = value.get("allFinished") as? Boolean ?: throw JSONException("allFinished")
        if (finished != (complete == total)) throw JSONException("finished")
        return RecoveryEvent(module, complete, total, finished, aggregateComplete, aggregateTotal, allFinished)
    }

    fun payments(json: String): PaymentUpdate {
        if (json.length > 256 * 1024) throw JSONException("oversized")
        val value = bounded(json)
        if (value.keys().asSequence().toSet() != setOf("payments", "notification")) throw JSONException("payment update fields")
        val array = value.getJSONArray("payments")
        if (array.length() > 100) throw JSONException("payments")
        val payments = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val allowed = setOf("operationId", "type", "direction", "amountSat", "feeSat", "timestampMillis", "status", "fiatAmount", "fiatCurrencyCode")
            if (item.keys().asSequence().toSet() != allowed) throw JSONException("payment fields")
            val id = item.getString("operationId")
            if (id.isBlank() || id.toByteArray(Charsets.UTF_8).size > 256) throw JSONException("operationId")
            val amount = item.getLong("amountSat").also { if (it < 0) throw JSONException("amountSat") }
            val fee = if (item.isNull("feeSat")) null else item.getLong("feeSat").also { if (it < 0) throw JSONException("feeSat") }
            val timestamp = item.getLong("timestampMillis").also { if (it < 0) throw JSONException("timestamp") }
            val fiatAmount = if (item.isNull("fiatAmount")) null else item.getString("fiatAmount")
            val fiatCode = if (item.isNull("fiatCurrencyCode")) null else item.getString("fiatCurrencyCode")
            if ((fiatAmount == null) != (fiatCode == null) || fiatAmount?.let { !FiatDecimal.isCanonical(it, true) } == true || fiatCode?.matches(Regex("^[A-Z]{3}$")) == false) throw JSONException("fiat")
            Payment(id, direction(item.getString("direction")), type(item.getString("type")), amount, fee, timestamp,
                status(item.getString("status")), fiatAmount?.let { FiatAmount(it, fiatCode!!) })
        }
        if (payments.map { it.operationId }.toSet().size != payments.size || payments.zipWithNext().any { it.first.timestampMillis < it.second.timestampMillis }) throw JSONException("payment order")
        val notification = if (value.isNull("notification")) null else value.getJSONObject("notification").let { item ->
            if (item.keys().asSequence().toSet() != setOf("direction", "success", "amountSat", "type")) throw JSONException("notification fields")
            PaymentNotification(direction(item.getString("direction")), item.get("success") as? Boolean ?: throw JSONException("success"),
                item.getLong("amountSat").also { if (it < 0) throw JSONException("amountSat") }, type(item.getString("type")))
        }
        return PaymentUpdate(payments, notification)
    }

    private fun direction(value: String) = when (value) { "incoming" -> PaymentDirection.INCOMING; "outgoing" -> PaymentDirection.OUTGOING; else -> throw JSONException("direction") }
    private fun type(value: String) = when (value) { "lightning" -> PaymentType.LIGHTNING; "onchain" -> PaymentType.ONCHAIN; "ecash" -> PaymentType.ECASH; else -> throw JSONException("type") }
    private fun status(value: String) = when (value) { "pending" -> PaymentStatus.PENDING; "succeeded" -> PaymentStatus.SUCCEEDED; "failed" -> PaymentStatus.FAILED; else -> throw JSONException("status") }

    private fun bounded(json: String): JSONObject {
        if (json.length > 128 * 1024) throw JSONException("oversized")
        return JSONObject(json)
    }
}

internal fun <T> subscriptionFlow(
    subscribe: (NativeSubscriptionCallback) -> Long,
    closeNative: (Long) -> Unit,
    parse: (String) -> T,
    cleanupScope: CoroutineScope,
    capacity: Int = Channel.CONFLATED,
): Flow<T> = callbackFlow {
    val expectedHandle = AtomicLong()
    val earlyCallback = AtomicReference<EarlyCallback?>(null)
    fun emit(handle: Long, json: String) {
        if (handle != expectedHandle.get()) return
        runCatching { parse(json) }.onSuccess { trySend(it) }.onFailure { close(it) }
    }
    val callback = object : NativeSubscriptionCallback {
        override fun onEvent(subscriptionHandle: Long, json: String) {
            if (expectedHandle.get() == 0L) earlyCallback.set(EarlyCallback.Event(subscriptionHandle, json)) else emit(subscriptionHandle, json)
        }
        override fun onError(subscriptionHandle: Long, code: String, message: String, retryable: Boolean) {
            val cause = IllegalStateException("$code: $message")
            if (expectedHandle.get() == 0L) earlyCallback.set(EarlyCallback.Error(subscriptionHandle, cause))
            else if (subscriptionHandle == expectedHandle.get()) close(cause)
        }
    }
    val handle = subscribe(callback)
    require(handle > 0) { "Invalid subscription handle" }
    expectedHandle.set(handle)
    when (val early = earlyCallback.getAndSet(null)) {
        is EarlyCallback.Event -> emit(early.handle, early.json)
        is EarlyCallback.Error -> if (early.handle == handle) close(early.cause)
        null -> Unit
    }
    val owned = SubscriptionHandle(handle, closeNative)
    awaitClose { cleanupScope.launch { owned.close() } }
}.buffer(capacity)
