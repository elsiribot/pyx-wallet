package cash.pyx.app.ui

import cash.pyx.app.nativeapi.Contact

data class ContactValidation(val nameError: String? = null, val paymentError: String? = null) {
    val valid: Boolean get() = nameError == null && paymentError == null
}

object ContactsPresentation {
    fun filter(contacts: List<Contact>, query: String): List<Contact> {
        val needle = query.trim()
        return contacts.filter { needle.isEmpty() || it.name.contains(needle, true) || it.lnurl.contains(needle, true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun validate(name: String, paymentInput: String, contacts: List<Contact>, editingLnurl: String? = null): ContactValidation {
        val cleanName = name.trim()
        val cleanInput = paymentInput.trim()
        val nameError = when {
            cleanName.isEmpty() -> "Enter a name"
            cleanName.length > 128 -> "Name is too long"
            contacts.any { it.lnurl != editingLnurl && it.name.equals(cleanName, true) } -> "A contact with this name already exists"
            else -> null
        }
        val paymentError = when {
            cleanInput.isEmpty() -> "Enter a Lightning address or LNURL"
            cleanInput.length > 16 * 1024 -> "Payment address is too long"
            !isPaymentInput(cleanInput) -> "Enter a valid Lightning address or LNURL"
            contacts.any { it.lnurl != editingLnurl && it.lnurl.equals(cleanInput, true) } -> "This payment address is already saved"
            else -> null
        }
        return ContactValidation(nameError, paymentError)
    }

    fun isPaymentInput(value: String): Boolean {
        if (value.any(Char::isWhitespace)) return false
        val lightningAddress = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")
        val lnurl = Regex("^lnurl1[023456789ac-hj-np-z]+$", RegexOption.IGNORE_CASE)
        val httpsLnurl = runCatching { java.net.URI(value) }.getOrNull()?.let {
            it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null
        } == true
        return lightningAddress.matches(value) || lnurl.matches(value) || httpsLnurl
    }
}
