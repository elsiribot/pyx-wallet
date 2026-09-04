package cash.pyx.app.ui

import cash.pyx.app.nativeapi.RecoveryEvent
import cash.pyx.app.nativeapi.RecoveryExpirySnapshot

object RecoveryPresentation {
    fun canReviewSuccessor(snapshot: RecoveryExpirySnapshot?): Boolean =
        snapshot?.successorInvitePresent == true && snapshot.successorInvite != null
    fun aggregate(event: RecoveryEvent?): String = event?.let { "${it.aggregateComplete}/${it.aggregateTotal}" } ?: "0/0"
}
