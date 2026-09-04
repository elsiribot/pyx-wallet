package cash.pyx.app.ui

import cash.pyx.app.nativeapi.SeedPhraseValidation

object SeedRestorePresentation {
    fun edit(words: List<String>, index: Int, input: String): List<String> = words.toMutableList().also {
        it[index] = input.lowercase().filter { char -> char in 'a'..'z' }.take(16)
    }
    fun selectSuggestion(words: List<String>, index: Int, suggestion: String): List<String> = words.toMutableList().also { it[index] = suggestion }
    fun cleared(): List<String> = List(12) { "" }
    fun checksumError(validation: SeedPhraseValidation?): Boolean = validation != null && validation.wordCount == 12 &&
        validation.invalidIndices.isEmpty() && !validation.checksumValid
}
