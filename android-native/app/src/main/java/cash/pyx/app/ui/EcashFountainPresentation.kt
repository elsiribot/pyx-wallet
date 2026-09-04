package cash.pyx.app.ui

object EcashFountainPresentation {
    const val STATIC_QR_MAX_BYTES = 1_024
    const val FRAME_MILLIS = 350L
    fun useStatic(payloadBytes: Int, reducedMotion: Boolean): Boolean = reducedMotion || payloadBytes <= STATIC_QR_MAX_BYTES
}

class EcashFragmentAccumulator {
    private val seen = linkedSetOf<String>()
    fun add(fragment: String): Boolean = seen.add(fragment)
    val progress: Int get() = seen.size
    fun clear() = seen.clear()
}
