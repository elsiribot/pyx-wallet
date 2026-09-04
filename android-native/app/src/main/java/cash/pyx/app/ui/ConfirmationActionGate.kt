package cash.pyx.app.ui

/** Holds at most one explicitly confirmed mutation and consumes it exactly once. */
class ConfirmationActionGate {
    private var pending: (() -> Unit)? = null

    @Synchronized
    fun request(action: () -> Unit): Boolean {
        if (pending != null) return false
        pending = action
        return true
    }

    fun confirm(): Boolean {
        val action = synchronized(this) {
            val value = pending ?: return false
            pending = null
            value
        }
        action()
        return true
    }

    @Synchronized
    fun cancel() {
        pending = null
    }

    @Synchronized
    fun hasPending(): Boolean = pending != null
}
