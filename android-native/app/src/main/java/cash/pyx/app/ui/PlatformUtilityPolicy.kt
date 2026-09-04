package cash.pyx.app.ui

/** Product-contract decisions; these must not grow platform features by accident. */
object PlatformUtilityPolicy {
    fun canShare(sensitive: Boolean): Boolean = !sensitive
    const val POSTS_SYSTEM_PAYMENT_NOTIFICATIONS = false
    const val HAS_QUICK_SPEND_POLICY = false
    val privacyPolicyUrl: String? = null
}
