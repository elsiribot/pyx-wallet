package cash.pyx.app

import cash.pyx.app.nativeapi.Contact
import cash.pyx.app.ui.ContactsPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsPresentationTest {
    private val contacts = listOf(
        Contact("Zoe", "zoe@example.com"),
        Contact("Alice", "LNURL1DP68GURN8GHJ7"),
    )

    @Test fun `searches names and payment addresses and sorts names`() {
        assertEquals(listOf("Alice", "Zoe"), ContactsPresentation.filter(contacts, "").map { it.name })
        assertEquals(listOf("Zoe"), ContactsPresentation.filter(contacts, "example").map { it.name })
        assertTrue(ContactsPresentation.filter(contacts, "missing").isEmpty())
    }

    @Test fun `accepts Lightning addresses bech32 LNURLs and secure LNURL URLs`() {
        assertTrue(ContactsPresentation.isPaymentInput("name@example.com"))
        assertTrue(ContactsPresentation.isPaymentInput("LNURL1DP68GURN8GHJ7"))
        assertTrue(ContactsPresentation.isPaymentInput("https://example.com/.well-known/lnurlp/name"))
        assertFalse(ContactsPresentation.isPaymentInput("http://example.com/pay"))
        assertFalse(ContactsPresentation.isPaymentInput("not an address"))
    }

    @Test fun `reports duplicate name and payment address independently`() {
        val duplicate = ContactsPresentation.validate("alice", "zoe@example.com", contacts)
        assertEquals("A contact with this name already exists", duplicate.nameError)
        assertEquals("This payment address is already saved", duplicate.paymentError)
    }

    @Test fun `editing excludes the original contact from duplicate checks`() {
        val result = ContactsPresentation.validate("Zoe", "zoe@example.com", contacts, "zoe@example.com")
        assertTrue(result.valid)
        assertNull(result.nameError)
        assertNull(result.paymentError)
    }

    @Test fun `blank and malformed fields provide targeted feedback`() {
        val blank = ContactsPresentation.validate(" ", " ", emptyList())
        assertEquals("Enter a name", blank.nameError)
        assertEquals("Enter a Lightning address or LNURL", blank.paymentError)
        assertEquals("Enter a valid Lightning address or LNURL",
            ContactsPresentation.validate("Bob", "ftp://example.com", emptyList()).paymentError)
    }
}
