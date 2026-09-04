package cash.pyx.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import cash.pyx.app.security.ClipboardExpiryScheduler
import cash.pyx.app.security.OwnedClipboard
import cash.pyx.app.security.SensitiveClipboardExpiry
import cash.pyx.app.security.SensitiveClipboardSnapshot
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID
import java.security.MessageDigest

data class ClipboardCopyFeedback(val announcement: String, val expiresAfterMillis: Long?)

object QrPayload {
    fun encode(value: String, size: Int = 640): Bitmap {
        val pixels = encodePixels(value, size)
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    fun encodePixels(value: String, size: Int): IntArray {
        require(value.isNotBlank() && value.length <= 16 * 1024 && size in 64..2048)
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        return IntArray(size * size) { index -> if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt() }
    }

    fun shareIntent(value: String, sensitive: Boolean): Intent {
        require(value.isNotBlank() && value.length <= MAX_PAYLOAD_CHARS)
        require(PlatformUtilityPolicy.canShare(sensitive)) { "Sensitive payloads must not enter the Android Sharesheet" }
        return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, value)
        }
    }

    fun copy(context: Context, label: String, value: String, sensitive: Boolean): ClipboardCopyFeedback {
        require(label.isNotBlank() && label.length <= 128)
        require(value.isNotBlank() && value.length <= MAX_PAYLOAD_CHARS)
        val clip = ClipData.newPlainText(label, value)
        val ownershipToken = if (sensitive) UUID.randomUUID().toString() else null
        if (sensitive) clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else LEGACY_SENSITIVE_CLIP_KEY, true)
            putString(CLIP_OWNERSHIP_TOKEN, ownershipToken)
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        if (ownershipToken != null) {
            SensitiveClipboardExpiry(
                clipboard = AndroidOwnedClipboard(clipboard),
                scheduler = ClipboardExpiryScheduler { delay, action ->
                    Handler(Looper.getMainLooper()).postDelayed(action, delay)
                },
            ).schedule(SensitiveClipboardSnapshot(ownershipToken, sha256(value)))
        }
        return if (sensitive) ClipboardCopyFeedback(
            "Sensitive content copied. It will be cleared from the clipboard in one minute.",
            SensitiveClipboardExpiry.DEFAULT_TIMEOUT_MILLIS,
        ) else ClipboardCopyFeedback("Copied to clipboard.", null)
    }

    private class AndroidOwnedClipboard(private val clipboard: ClipboardManager) : OwnedClipboard {
        override fun clearIfOwned(expected: SensitiveClipboardSnapshot) {
            // Clipboard reads can be denied when the app is backgrounded. In that case Android 13+
            // still applies its system expiry; importantly, never clear without proving ownership.
            val current = runCatching { clipboard.primaryClip }.getOrNull() ?: return
            val token = current.description.extras?.getString(CLIP_OWNERSHIP_TOKEN) ?: return
            val value = current.getItemAt(0).text?.toString() ?: return
            if (token != expected.ownershipToken || sha256(value) != expected.valueSha256) return
            if (Build.VERSION.SDK_INT >= 28) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private const val CLIP_OWNERSHIP_TOKEN = "cash.pyx.app.clipboard.ownership_token"
    private const val LEGACY_SENSITIVE_CLIP_KEY = "android.content.extra.IS_SENSITIVE"
    private const val MAX_PAYLOAD_CHARS = 16 * 1024
}
