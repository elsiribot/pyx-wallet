package cash.pyx.app.ui

import cash.pyx.app.nativeapi.InputType
import cash.pyx.app.nativeapi.ClassifiedInput

/** A payload-free destination whose identifier is safe for saved navigation state. */
sealed interface PyxRoute {
    val route: String
}

/** A payload-free, exhaustive model of destinations in the unlocked wallet. */
enum class WalletRoute(override val route: String, val title: String) : PyxRoute {
    HOME("home", "Home"), RECEIVE("receive", "Receive"), SEND("send", "Send"),
    SCAN("scan", "Scan"), WALLETS("wallets", "Wallets"),
    DETAILS("details", "Details"), GUARDIANS("guardians", "Guardians"),
    SETTINGS("settings", "Settings"), CURRENCY("currency", "Currency"), CONTACTS("contacts", "Contacts"),
    ADDRESSES("addresses", "Addresses"), ACCESS("access", "Access"),
    SEED_BACKUP("seed_backup", "Recovery words"), JOIN("join", "Join"), RECOVER("recover", "Recover");

    companion object {
        fun forInput(type: InputType): WalletRoute = when (type) {
            InputType.INVITE -> JOIN
            InputType.ECASH -> RECEIVE
            else -> SEND
        }
    }
}

/**
 * Typed identities for transient wallet surfaces.
 *
 * Context such as a contact, invite, quote, or operation id deliberately does not live here: those
 * values stay in ephemeral Compose/ViewModel state and never become a saved route argument.
 */
enum class WalletModalRoute(override val route: String) : PyxRoute {
    CONTACT_DELETE("modal_contact_delete"),
    LEAVE_FEDERATION("modal_leave_federation"),
    ADDRESS_MUTATION("modal_address_mutation"),
    SUCCESSOR_REVIEW("modal_successor_review"),
    PAYMENT_DETAIL("modal_payment_detail"),
    JOIN_CONFIRMATION("modal_join_confirmation"),
    SEND_CONFIRMATION("modal_send_confirmation"),
    ANOTHER_ADDRESS("modal_another_address"),
    SEED_COPY_WARNING("modal_seed_copy_warning"),
}

/** Payload stays in ephemeral memory; only the payload-free [WalletRoute] enters saved navigation state. */
sealed interface IncomingRequest {
    val payload: String
    val type: InputType

    data class Ecash(override val payload: String) : IncomingRequest { override val type = InputType.ECASH }
    data class Invite(override val payload: String) : IncomingRequest { override val type = InputType.INVITE }
    data class Lightning(override val payload: String) : IncomingRequest { override val type = InputType.LIGHTNING }
    data class Bitcoin(override val payload: String) : IncomingRequest { override val type = InputType.BITCOIN }
    data class Lnurl(override val payload: String) : IncomingRequest { override val type = InputType.LNURL }
    data class Unknown(override val payload: String) : IncomingRequest { override val type = InputType.UNKNOWN }

    companion object {
        fun from(input: ClassifiedInput): IncomingRequest = when (input.type) {
            InputType.ECASH -> Ecash(input.payload)
            InputType.INVITE -> Invite(input.payload)
            InputType.LIGHTNING -> Lightning(input.payload)
            InputType.BITCOIN -> Bitcoin(input.payload)
            InputType.LNURL -> Lnurl(input.payload)
            InputType.UNKNOWN -> Unknown(input.payload)
        }
    }
}
