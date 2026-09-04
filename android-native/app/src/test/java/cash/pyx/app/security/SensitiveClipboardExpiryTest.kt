package cash.pyx.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class SensitiveClipboardExpiryTest {
    @Test fun matchingAppWriteIsClearedAfterTimeout() {
        val clipboard = FakeClipboard()
        val scheduler = FakeScheduler()
        val snapshot = SensitiveClipboardSnapshot("token-1", "secret")
        clipboard.current = snapshot

        SensitiveClipboardExpiry(clipboard, scheduler).schedule(snapshot)
        assertEquals(60_000L, scheduler.delayMillis)
        assertEquals(snapshot, clipboard.current)

        scheduler.run()
        assertEquals(null, clipboard.current)
    }

    @Test fun userReplacementIsNeverClearedByOldTimer() {
        val clipboard = FakeClipboard()
        val scheduler = FakeScheduler()
        val written = SensitiveClipboardSnapshot("token-1", "secret")
        SensitiveClipboardExpiry(clipboard, scheduler).schedule(written)

        clipboard.current = SensitiveClipboardSnapshot("user-owned", "other")
        scheduler.run()

        assertEquals(SensitiveClipboardSnapshot("user-owned", "other"), clipboard.current)
    }

    @Test fun sameTextWithoutOwnershipTokenIsNeverCleared() {
        val clipboard = FakeClipboard()
        val scheduler = FakeScheduler()
        val written = SensitiveClipboardSnapshot("token-1", "secret")
        SensitiveClipboardExpiry(clipboard, scheduler).schedule(written)

        clipboard.current = SensitiveClipboardSnapshot("different-token", "secret")
        scheduler.run()

        assertEquals(SensitiveClipboardSnapshot("different-token", "secret"), clipboard.current)
    }

    @Test fun oldTimerCannotClearNewerAppWrite() {
        val clipboard = FakeClipboard()
        val oldScheduler = FakeScheduler()
        val newScheduler = FakeScheduler()
        val old = SensitiveClipboardSnapshot("token-1", "old secret")
        val newer = SensitiveClipboardSnapshot("token-2", "new secret")
        SensitiveClipboardExpiry(clipboard, oldScheduler).schedule(old)
        SensitiveClipboardExpiry(clipboard, newScheduler).schedule(newer)
        clipboard.current = newer

        oldScheduler.run()
        assertEquals(newer, clipboard.current)
        newScheduler.run()
        assertEquals(null, clipboard.current)
    }

    private class FakeClipboard : OwnedClipboard {
        var current: SensitiveClipboardSnapshot? = null
        override fun clearIfOwned(expected: SensitiveClipboardSnapshot) {
            if (current == expected) current = null
        }
    }

    private class FakeScheduler : ClipboardExpiryScheduler {
        var delayMillis: Long? = null
        private var action: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, action: () -> Unit) {
            this.delayMillis = delayMillis
            this.action = action
        }
        fun run() = requireNotNull(action).invoke()
    }
}
