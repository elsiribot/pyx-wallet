package cash.pyx.app.nativeapi

data class AndroidError(
    val code: String,
    val userMessage: String,
    val retryable: Boolean,
)

sealed interface NativeResult<out T> {
    data class Success<T>(val value: T) : NativeResult<T>
    data class Failure(val error: AndroidError) : NativeResult<Nothing>
}
