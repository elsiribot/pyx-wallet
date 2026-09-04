package cash.pyx.app

import cash.pyx.app.nativeapi.JniNativeWalletApi
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.SeedPhraseValidation
import cash.pyx.app.nativeapi.SeedWordSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedValidationContractTest {
    @Test fun suggestionsAreBoundedAndLowercase() {
        val api = JniNativeWalletApi(libraryLoader = {}, seedWordSuggestionsBinding = {
            """{"suggestions":["abandon","ability"]}"""
        })
        assertEquals(
            SeedWordSuggestions(listOf("abandon", "ability")),
            (api.seedWordSuggestions("ab") as NativeResult.Success).value,
        )
        assertTrue(api.seedWordSuggestions("Ü") is NativeResult.Failure)
        assertTrue(api.seedWordSuggestions("A") is NativeResult.Failure)
    }

    @Test fun validationReturnsOnlyPositionsAndChecksumState() {
        var captured = ""
        val api = JniNativeWalletApi(libraryLoader = {}, validateSeedPhraseBinding = { input ->
            captured = input
            """{"valid":false,"wordCount":3,"invalidIndices":[1],"checksumValid":false}"""
        })
        val secret = "neverecho"
        val result = api.validateSeedPhrase(listOf("abandon", secret, "about"))
        val validation = (result as NativeResult.Success).value
        assertEquals(
            SeedPhraseValidation(false, 3, listOf(1), false),
            validation,
        )
        assertTrue(captured.contains(secret))
        assertFalse(validation.toString().contains(secret))
    }

    @Test fun inconsistentOrOversizedNativeResponsesFailClosed() {
        val inconsistent = JniNativeWalletApi(libraryLoader = {}, validateSeedPhraseBinding = {
            """{"valid":true,"wordCount":12,"invalidIndices":[1],"checksumValid":true}"""
        })
        assertTrue(inconsistent.validateSeedPhrase(List(12) { "abandon" }) is NativeResult.Failure)
        assertTrue(inconsistent.validateSeedPhrase(List(25) { "abandon" }) is NativeResult.Failure)
    }
}
