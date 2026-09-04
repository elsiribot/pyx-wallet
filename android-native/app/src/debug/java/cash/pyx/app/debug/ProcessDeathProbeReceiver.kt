package cash.pyx.app.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cash.pyx.app.BuildConfig
import cash.pyx.app.security.ConsumedDeepLink
import cash.pyx.app.security.PendingIrreversibleOperation
import cash.pyx.app.security.SharedPreferencesDeepLinkConsumptionStore
import cash.pyx.app.security.SharedPreferencesIrreversibleOperationJournal

/**
 * Debug-preview-only checkpoint for the external adb process-death probe.
 * Constants are synthetic identifiers, never wallet payloads, destinations, amounts, or tokens.
 */
class ProcessDeathProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || context.packageName != PREVIEW_PACKAGE || intent.action != ACTION) {
            respond(false, "REFUSED")
            return
        }
        when (intent.getStringExtra(EXTRA_PHASE)) {
            PHASE_WRITE -> respond(writeCheckpoint(context), "WRITE")
            PHASE_VERIFY -> respond(verifyCheckpoint(context), "VERIFY")
            else -> respond(false, "UNKNOWN_PHASE")
        }
    }

    private fun writeCheckpoint(context: Context): Boolean {
        val deepLinkWritten = SharedPreferencesDeepLinkConsumptionStore(context).write(EXPECTED_DEEP_LINK)
        val journal = SharedPreferencesIrreversibleOperationJournal(context)
        return deepLinkWritten && journal.clear() && journal.begin(EXPECTED_OPERATION)
    }

    private fun verifyCheckpoint(context: Context): Boolean =
        SharedPreferencesDeepLinkConsumptionStore(context).read() == EXPECTED_DEEP_LINK &&
            SharedPreferencesIrreversibleOperationJournal(context).read() == EXPECTED_OPERATION

    private fun respond(success: Boolean, phase: String) {
        resultCode = if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED
        resultData = if (success) "${phase}_PASS" else "${phase}_FAIL"
    }

    private companion object {
        const val PREVIEW_PACKAGE = "cash.pyx.app.nativepreview"
        const val ACTION = "$PREVIEW_PACKAGE.PROCESS_DEATH_PROBE"
        const val EXTRA_PHASE = "phase"
        const val PHASE_WRITE = "write"
        const val PHASE_VERIFY = "verify"
        const val FINGERPRINT = "9f4c1e84d6b75b4958ad71bd4b83c90155c55830fbe99de4b4b10dc93eec4f66"
        const val CORRELATION_ID = "ab61f020cc6845f287683871ebe6564d"
        val EXPECTED_DEEP_LINK = ConsumedDeepLink(FINGERPRINT, 1_700_000_000_000L)
        val EXPECTED_OPERATION = PendingIrreversibleOperation(
            kind = PendingIrreversibleOperation.Kind.ECASH_CREATE,
            startedAtEpochMillis = 1_700_000_000_000L,
            correlationId = CORRELATION_ID,
            federationId = "offline-process-death-probe",
            reconciliationStatus = PendingIrreversibleOperation.ReconciliationStatus.LOCAL_ONLY,
        )
    }
}
