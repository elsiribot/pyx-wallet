package cash.pyx.app.security

object SeedVerification {
    const val REQUIRED_WORDS = 12
    val positions: List<Int> = listOf(2, 6, 10)

    fun verify(seedWords: List<String>, answers: List<String>): Boolean {
        if (seedWords.size != REQUIRED_WORDS || answers.size != positions.size) return false
        return positions.indices.all { index ->
            val answer = answers[index].trim()
            answer.isNotEmpty() && answer.equals(seedWords[positions[index]], ignoreCase = true)
        }
    }
}
