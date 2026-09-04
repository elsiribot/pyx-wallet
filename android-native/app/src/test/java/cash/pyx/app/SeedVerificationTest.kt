package cash.pyx.app

import cash.pyx.app.security.SeedVerification
import org.junit.Assert.*
import org.junit.Test

class SeedVerificationTest {
    private val words = (1..12).map { "word$it" }
    private val correct = SeedVerification.positions.map(words::get)

    @Test fun deterministicPositionsAreOrderedAndDistinct() {
        assertEquals(listOf(2, 6, 10), SeedVerification.positions)
        assertEquals(SeedVerification.positions.sorted(), SeedVerification.positions)
        assertEquals(SeedVerification.positions.size, SeedVerification.positions.distinct().size)
    }

    @Test fun correctOrderedAnswersVerify() {
        assertTrue(SeedVerification.verify(words, correct))
        assertTrue(SeedVerification.verify(words, correct.map(String::uppercase)))
    }

    @Test fun invalidIncompleteAndReorderedAnswersFail() {
        assertFalse(SeedVerification.verify(words, emptyList()))
        assertFalse(SeedVerification.verify(words, correct.dropLast(1)))
        assertFalse(SeedVerification.verify(words, correct.reversed()))
        assertFalse(SeedVerification.verify(words, correct.toMutableList().also { it[1] = "wrong" }))
        assertFalse(SeedVerification.verify(words.dropLast(1), correct))
    }
}
