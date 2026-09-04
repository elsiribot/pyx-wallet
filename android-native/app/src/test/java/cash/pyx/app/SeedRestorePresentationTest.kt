package cash.pyx.app

import cash.pyx.app.nativeapi.SeedPhraseValidation
import cash.pyx.app.ui.SeedRestorePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedRestorePresentationTest {
    @Test fun `editing sanitizes one word and suggestion selection replaces only that word`() {
        val initial = SeedRestorePresentation.cleared()
        val edited = SeedRestorePresentation.edit(initial, 3, "AbÄndon123")
        assertEquals("abndon", edited[3])
        val selected = SeedRestorePresentation.selectSuggestion(edited, 3, "abandon")
        assertEquals("abandon", selected[3])
        assertTrue(selected.filterIndexed { index, _ -> index != 3 }.all(String::isEmpty))
    }

    @Test fun `invalid index and checksum states remain distinct`() {
        val invalidWord = SeedPhraseValidation(false, 12, listOf(3), false)
        assertFalse(SeedRestorePresentation.checksumError(invalidWord))
        assertTrue(SeedRestorePresentation.checksumError(SeedPhraseValidation(false, 12, emptyList(), false)))
        assertFalse(SeedRestorePresentation.checksumError(SeedPhraseValidation(true, 12, emptyList(), true)))
    }

    @Test fun `background reset returns twelve empty transient fields`() {
        val cleared = SeedRestorePresentation.cleared()
        assertEquals(12, cleared.size)
        assertTrue(cleared.all(String::isEmpty))
    }

    @Test fun `repeated words remain independent positional entries`() {
        val first = SeedRestorePresentation.edit(SeedRestorePresentation.cleared(), 0, "abandon")
        val repeated = SeedRestorePresentation.edit(first, 10, "abandon")
        val corrected = SeedRestorePresentation.selectSuggestion(repeated, 0, "about")

        assertEquals("about", corrected[0])
        assertEquals("abandon", corrected[10])
        assertTrue(corrected.filterIndexed { index, _ -> index != 0 && index != 10 }.all(String::isEmpty))
    }
}
