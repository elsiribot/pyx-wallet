package cash.pyx.app

import cash.pyx.app.data.SubscriptionDtos
import cash.pyx.app.nativeapi.JniNativeWalletApi
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.RecoveryExpirySnapshot
import cash.pyx.app.ui.RecoveryPresentation
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryContractTest {
    @Test fun `strict recovery progress enforces bounds and terminal consistency`() {
        val valid = SubscriptionDtos.recovery("""{"moduleId":1,"complete":5,"total":5,"finished":true,"aggregateComplete":9,"aggregateTotal":9,"allFinished":true}""")
        assertTrue(valid.allFinished)
        val invalid = """{"moduleId":1,"complete":6,"total":5,"finished":false,"aggregateComplete":6,"aggregateTotal":5,"allFinished":false}"""
        assertTrue(runCatching { SubscriptionDtos.recovery(invalid) }.exceptionOrNull() is JSONException)
    }

    @Test fun `expiry snapshot retains bounded successor only when explicitly present`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, recoveryExpiryAsyncBinding = { _, callback ->
            callback.onSuccess(3, """{"hasPendingRecoveries":true,"expiresAtEpochSeconds":1800000000,"successorInvitePresent":true,"successorInvite":"fedimint:secret"}"""); 3
        })
        val value = (api.recoveryExpirySnapshotAsync(1) as NativeResult.Success).value
        assertEquals("fedimint:secret", value.successorInvite)
        assertTrue(RecoveryPresentation.canReviewSuccessor(value))
    }

    @Test fun `successor confirmation is unavailable without payload`() {
        assertFalse(RecoveryPresentation.canReviewSuccessor(RecoveryExpirySnapshot(false, null, false, null)))
    }

    @Test fun `invalid successor presence mismatch fails closed`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, recoveryExpiryAsyncBinding = { _, callback ->
            callback.onSuccess(4, """{"hasPendingRecoveries":false,"expiresAtEpochSeconds":null,"successorInvitePresent":true,"successorInvite":null}"""); 4
        })
        assertTrue(api.recoveryExpirySnapshotAsync(1) is NativeResult.Failure)
    }
}
