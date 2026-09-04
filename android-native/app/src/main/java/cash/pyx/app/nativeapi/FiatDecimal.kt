package cash.pyx.app.nativeapi

object FiatDecimal {
    fun isCanonical(value: String, allowZero: Boolean = false): Boolean =
        Regex("^(0|[1-9][0-9]*)(\\.[0-9]+)?$").matches(value) && (allowZero || value.any { it in '1'..'9' })
}
