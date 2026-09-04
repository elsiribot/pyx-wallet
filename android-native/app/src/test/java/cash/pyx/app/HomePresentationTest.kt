package cash.pyx.app

import cash.pyx.app.nativeapi.ConnectionState
import cash.pyx.app.nativeapi.GuardianConnectionSnapshot
import cash.pyx.app.nativeapi.GuardianStatus
import cash.pyx.app.ui.HomePresentation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HomePresentationTest {
    @Test fun maskingHidesSatsAndDerivedFiat() {
        assertEquals("1,234 sats", HomePresentation.balanceText(1234, false, Locale.US))
        assertEquals("•••• sats", HomePresentation.balanceText(1234, true))
        assertEquals("12.34 EUR", HomePresentation.fiatText("12.34", "EUR", false, Locale.US))
        assertEquals("•••• EUR", HomePresentation.fiatText("12.34", "EUR", true))
    }

    @Test fun displayUsesLocaleWithoutChangingCanonicalDecimalInput() {
        assertEquals("1.234.567 sats", HomePresentation.balanceText(1_234_567, false, Locale.GERMANY))
        assertEquals("1.234,50 EUR", HomePresentation.fiatText("1234.50", "EUR", false, Locale.GERMANY))
    }

    @Test fun connectionStatesHaveExplicitPresentation() {
        assertEquals("Connected", HomePresentation.connectionTitle(status(ConnectionState.CONNECTED, 3)))
        assertEquals("Connection degraded", HomePresentation.connectionTitle(status(ConnectionState.DEGRADED, 2)))
        assertEquals("Offline", HomePresentation.connectionTitle(status(ConnectionState.OFFLINE, 0)))
        assertEquals("Connection status unavailable", HomePresentation.connectionTitle(null))
        assertEquals("2 of 3 guardians online", HomePresentation.connectionDetail(status(ConnectionState.DEGRADED, 2)))
    }

    private fun status(state: ConnectionState, online: Int) = GuardianConnectionSnapshot(
        guardians = List(3) { GuardianStatus("Guardian $it", it < online) },
        onlineCount = online,
        totalCount = 3,
        requiredCount = 3,
        state = state,
    )
}
