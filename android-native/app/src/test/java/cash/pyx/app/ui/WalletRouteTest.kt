package cash.pyx.app.ui

import cash.pyx.app.nativeapi.InputType
import cash.pyx.app.nativeapi.ClassifiedInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WalletRouteTest {
    @Test fun `every route has a stable unique payload-free identifier`() {
        val routes: List<PyxRoute> = WalletRoute.entries + WalletModalRoute.entries
        assertEquals(routes.size, routes.map { it.route }.toSet().size)
        routes.forEach { route ->
            assertFalse(route.route.contains('/'))
            assertFalse(route.route.contains('?'))
            assertFalse(route.route.contains('{'))
        }
    }

    @Test fun `all implemented transient surfaces have typed payload-free destinations`() {
        assertEquals(
            setOf(
                WalletModalRoute.CONTACT_DELETE,
                WalletModalRoute.LEAVE_FEDERATION,
                WalletModalRoute.ADDRESS_MUTATION,
                WalletModalRoute.SUCCESSOR_REVIEW,
                WalletModalRoute.JOIN_CONFIRMATION,
                WalletModalRoute.SEED_COPY_WARNING,
                WalletModalRoute.LNADDR_RELEASE,
            ),
            WalletModalRoute.entries.toSet(),
        )
    }

    @Test fun `classified input maps to the expected typed destination`() {
        assertEquals(WalletRoute.JOIN, WalletRoute.forInput(InputType.INVITE))
        assertEquals(WalletRoute.RECEIVE, WalletRoute.forInput(InputType.ECASH))
        listOf(InputType.LIGHTNING, InputType.BITCOIN, InputType.LNURL, InputType.UNKNOWN).forEach {
            assertEquals(WalletRoute.SEND, WalletRoute.forInput(it))
        }
    }

    @Test fun `sealed incoming requests remain ephemeral and typed`() {
        InputType.entries.forEach { type ->
            val fixture = "synthetic-$type"
            val request = IncomingRequest.from(ClassifiedInput(type, fixture))
            assertEquals(type, request.type)
            assertEquals(fixture, request.payload)
            assertFalse(WalletRoute.forInput(type).route.contains(fixture))
        }
    }

    @Test fun `implemented access and backup surfaces have typed destinations`() {
        assertEquals("access", WalletRoute.ACCESS.route)
        assertEquals("seed_backup", WalletRoute.SEED_BACKUP.route)
    }

    @Test fun `currency and guardians have distinct typed destinations`() {
        assertEquals("currency", WalletRoute.CURRENCY.route)
        assertEquals("guardians", WalletRoute.GUARDIANS.route)
        assertFalse(WalletRoute.CURRENCY == WalletRoute.SETTINGS)
        assertFalse(WalletRoute.GUARDIANS == WalletRoute.DETAILS)
    }
}
