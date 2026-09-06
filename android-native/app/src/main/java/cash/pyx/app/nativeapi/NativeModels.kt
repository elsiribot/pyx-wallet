package cash.pyx.app.nativeapi

sealed interface BootstrapSession {
    val nativeVersion: String
    val databaseHandle: Long

    data class Uninitialized(
        override val nativeVersion: String,
        override val databaseHandle: Long,
    ) : BootstrapSession

    data class Ready(
        override val nativeVersion: String,
        override val databaseHandle: Long,
        val factoryHandle: Long,
    ) : BootstrapSession
}

data class CreatedWallet(val factoryHandle: Long, val seedWords: List<String>)
data class RestoredWallet(val factoryHandle: Long)

data class WalletSnapshot(
    val currencyCode: String,
    val federations: List<FederationSummary>,
    val selected: SelectedWallet?,
)

data class SelectedWallet(
    val clientHandle: Long,
    val federationId: String,
    val name: String,
    val balanceSat: Long,
    val payments: List<Payment>,
)

data class LightningReceive(
    val payload: String,
    val amountSat: Long,
    val feeSat: Long,
    val gatewayUrl: String,
    val expiresAtEpochSeconds: Long,
)
data class OnchainReceive(val payload: String)
data class LightningSend(val operationId: String, val feeSat: Long, val correlationId: String = "")
data class OnchainSend(val feeSat: Long, val correlationId: String = "", val operationId: String? = null)
data class EcashCreated(val payload: String, val amountSat: Long, val correlationId: String = "", val operationId: String? = null)
data class EcashClaimed(val amountSat: Long, val correlationId: String = "", val operationId: String? = null)
enum class DurableOperationKind { LIGHTNING, ONCHAIN, ECASH_CREATE, ECASH_CLAIM }
enum class DurableOperationStatus { SUBMITTED, IN_FLIGHT, PENDING, NOT_SUBMITTED, SUCCEEDED, FAILED, AMBIGUOUS }
data class PendingNativeOperation(
    val correlationId: String,
    val kind: DurableOperationKind,
    val status: DurableOperationStatus,
)
data class PendingNativeOperations(val operations: List<PendingNativeOperation>)
data class OperationReconciliationResult(
    val correlationId: String,
    val kind: DurableOperationKind,
    val status: DurableOperationStatus,
    val operationId: String?,
    val candidateCount: Int,
)
data class DurableOperationCleared(val cleared: Boolean)
data class EcashCodecHandle(val handle: Long)
data class EcashFragment(val fragment: String)
data class EcashDecodeResult(val complete: Boolean, val payload: String?)
data class EcashCodecClosed(val closed: Boolean)
data class CurrencyChanged(val currencyCode: String)
data class SeedWords(val seedWords: List<String>)
data class SeedWordSuggestions(val suggestions: List<String>)
data class SeedPhraseValidation(
    val valid: Boolean,
    val wordCount: Int,
    val invalidIndices: List<Int>,
    val checksumValid: Boolean,
)
data class FederationLeft(val left: Boolean)
data class AndroidSessionShutdown(val shutdown: Boolean)
data class FederationStats(val totalValueSat: Long, val blockCount: Long, val feerateSatPerKvb: Long?)
data class FederationDetails(
    val name: String,
    val id: String,
    val currencyCode: String,
    val stats: FederationStats?,
)
data class LnurlSession(val sessionHandle: Long, val minSat: Long, val maxSat: Long, val fixedAmount: Boolean)
data class ContactsSnapshot(val contacts: List<Contact>)
data class ContactSaved(val saved: Boolean)
data class ContactDeleted(val deleted: Boolean)
data class GuardianConnectionSnapshot(
    val guardians: List<GuardianStatus>,
    val onlineCount: Int,
    val totalCount: Int,
    val requiredCount: Int,
    val state: ConnectionState,
)
data class FiatCurrencies(val currencies: List<FiatCurrency>)
data class OnchainAddress(val tweakIndex: Long, val address: String)
data class OnchainAddresses(val addresses: List<OnchainAddress>)
data class AddressRechecked(val rechecked: Boolean)
data class LightningQuote(
    val quoteHandle: Long, val amountSat: Long, val feeSat: Long, val gatewayUrl: String, val direct: Boolean,
)
data class OnchainQuote(
    val quoteHandle: Long,
    val amountSat: Long,
    val feeSat: Long,
    val address: String,
    val uriAmountSat: Long? = null,
    val amountLocked: Boolean = false,
    val label: String? = null,
    val message: String? = null,
)
enum class InputType { ECASH, INVITE, LIGHTNING, BITCOIN, LNURL, UNKNOWN }
data class ClassifiedInput(val type: InputType, val payload: String)
data class LnurlReceive(val payload: String)
data class TransientClosed(val closed: Boolean)

data class FederationSummary(
    val id: String,
    val name: String,
    val guardianCount: Int,
    val expiresAtEpochSeconds: Long? = null,
    val successorInviteHandle: Long? = null,
    val recovering: Boolean = false,
)

data class FiatAmount(val decimal: String, val currencyCode: String)
data class Balance(val federationId: String, val availableSat: Long, val fiat: FiatAmount? = null)
data class GuardianStatus(val name: String, val connected: Boolean)

enum class ConnectionState { CONNECTED, DEGRADED, OFFLINE }
data class ConnectionStatus(
    val federationId: String,
    val guardians: List<GuardianStatus>,
    val onlineCount: Int,
    val requiredCount: Int? = null,
    val state: ConnectionState,
)

enum class PaymentDirection { INCOMING, OUTGOING }
enum class PaymentType { LIGHTNING, ONCHAIN, ECASH }
enum class PaymentStatus { PENDING, SUCCEEDED, FAILED }

data class Payment(
    val operationId: String,
    val direction: PaymentDirection,
    val type: PaymentType,
    val amountSat: Long,
    val feeSat: Long? = null,
    val timestampMillis: Long,
    val status: PaymentStatus,
    val fiat: FiatAmount? = null,
    val ecash: String? = null,
    val txid: String? = null,
    val address: String? = null,
    val preimage: String? = null,
)

data class PaymentHistory(val payments: List<Payment>)
data class PaymentPage(val payments: List<Payment>, val nextCursor: String?)
data class PaymentDetails(val payment: Payment)
data class PaymentNotification(val direction: PaymentDirection, val success: Boolean, val amountSat: Long, val type: PaymentType)
data class PaymentUpdate(val payments: List<Payment>, val notification: PaymentNotification?)

enum class ReceiveType { LIGHTNING, LNURL, ONCHAIN, WALLET_V2, ECASH }
data class ReceiveRequest(
    val type: ReceiveType,
    val payload: String,
    val amountSat: Long? = null,
    val feeSat: Long? = null,
    val gatewayUrl: String? = null,
    val expiresAtEpochSeconds: Long? = null,
)

enum class FeeType { LIGHTNING, ONCHAIN }
data class FeeQuote(
    val type: FeeType,
    val amountSat: Long,
    val feeSat: Long,
    val gatewayUrl: String? = null,
    val direct: Boolean? = null,
    val quoteHandle: Long,
)

data class RecoveryProgress(val moduleId: Long, val complete: Long, val total: Long)
data class RecoveryEvent(val moduleId: Long, val complete: Long, val total: Long, val finished: Boolean,
    val aggregateComplete: Long, val aggregateTotal: Long, val allFinished: Boolean)
data class RecoveryExpirySnapshot(val hasPendingRecoveries: Boolean, val expiresAtEpochSeconds: Long?,
    val successorInvitePresent: Boolean, val successorInvite: String?)
data class Contact(val name: String, val lnurl: String)
data class FiatCurrency(val code: String, val name: String, val symbol: String, val decimalDigits: Int)
data class FiatDisplay(val amountDecimal: String, val currencyCode: String, val currencyName: String, val currencySymbol: String, val decimalDigits: Int)
data class FiatToSats(val amountSat: Long, val currencyCode: String)
data class BitcoinPayment(val address: String, val amountSat: Long?, val label: String?, val message: String?, val isUri: Boolean)

data class LnAddress(val domain: String, val username: String, val serverOrigin: String,
    val federationId: String?, val destination: String, val isPrimary: Boolean, val claimedAtSecs: Long) {
    val display: String get() = "$username@$domain"
}
data class LnAddressSnapshot(val addresses: List<LnAddress>)
data class LnaddrServer(val origin: String, val name: String, val domains: List<String>, val freeDomains: List<String>)
data class LnaddrDiscovery(val servers: List<LnaddrServer>)
sealed interface LnaddrQuote { data object Free : LnaddrQuote; data class Paid(val priceMsat: Long) : LnaddrQuote
    data object Taken : LnaddrQuote; data object Reserved : LnaddrQuote
    data class Invalid(val reason: String) : LnaddrQuote; data object RateLimited : LnaddrQuote }
data class LnaddrMutation(val ok: Boolean)
data class LnaddrRecovery(val recovered: Long)
