package cash.pyx.app.security

class OneShotActionGate {
    private var generation = 0L
    private var pending: (() -> Unit)? = null

    @Synchronized
    fun request(action: () -> Unit, authenticate: (onSuccess: () -> Unit, onFailure: () -> Unit) -> Unit): Boolean {
        if (pending != null) return false
        val token = ++generation
        pending = action
        authenticate({ complete(token) }, { cancel(token) })
        return true
    }

    @Synchronized
    private fun complete(token: Long) {
        if (token != generation) return
        val action = pending ?: return
        pending = null
        generation++
        action()
    }

    @Synchronized
    private fun cancel(token: Long) {
        if (token == generation) { pending = null; generation++ }
    }

    @Synchronized fun clear() { pending = null; generation++ }
    @Synchronized fun hasPending(): Boolean = pending != null
}
