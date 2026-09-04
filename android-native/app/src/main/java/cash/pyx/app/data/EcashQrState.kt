package cash.pyx.app.data

import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Native fountain-code boundary used by the camera scanner and animated ecash QR surface. */
interface EcashQrRepository {
    suspend fun classify(payload: String): NativeResult<InputType>
    fun createEncoder(payload: String): NativeResult<EcashCodecHandle>
    fun nextFragment(handle: Long): NativeResult<EcashFragment>
    fun createDecoder(): NativeResult<EcashCodecHandle>
    fun addFragment(handle: Long, fragment: String): NativeResult<EcashDecodeResult>
    fun closeCodec(handle: Long, kind: String): NativeResult<EcashCodecClosed>
}

data class EcashQrState(
    val displayFrame: String? = null,
    val decodeProgress: Int = 0,
    val decodedPayload: String? = null,
    val classifiedInput: ClassifiedInput? = null,
    val classifying: Boolean = false,
    val error: String? = null,
    val encoding: Boolean = false,
)

class EcashQrStateOwner(private val repository: EcashQrRepository) {
    private val mutableState = MutableStateFlow(EcashQrState())
    val state: StateFlow<EcashQrState> = mutableState.asStateFlow()
    private val decoderLock = Any()
    private val seenFrames = linkedSetOf<String>()
    private var encoderHandle = 0L
    private var decoderHandle = 0L
    private var encoderGeneration = 0L
    private var decoderGeneration = 0L
    private var encoderJob: Job? = null
    private var classificationGeneration = 0L
    private var classificationJob: Job? = null

    fun classify(scope: CoroutineScope, payload: String) {
        if (payload.isBlank() || payload.length > MAX_DECODED_PAYLOAD_CHARS) return
        val generation = ++classificationGeneration
        classificationJob?.cancel()
        mutableState.value = mutableState.value.copy(classifying = true, error = null)
        classificationJob = scope.launch {
            try {
                when (val result = repository.classify(payload)) {
                    is NativeResult.Success -> if (generation == classificationGeneration) {
                        mutableState.value = if (result.value == InputType.UNKNOWN) {
                            mutableState.value.copy(classifying = false, error = "This QR code or link is not supported.")
                        } else mutableState.value.copy(
                            classifying = false,
                            classifiedInput = ClassifiedInput(result.value, payload),
                            error = null,
                        )
                    }
                    is NativeResult.Failure -> if (generation == classificationGeneration) {
                        mutableState.value = mutableState.value.copy(classifying = false, error = result.error.userMessage)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == classificationGeneration) mutableState.value = mutableState.value.copy(
                    classifying = false,
                    error = "QR processing failed unexpectedly. Try again.",
                )
            }
        }
    }

    fun startDisplay(scope: CoroutineScope, payload: String, useStaticQr: Boolean, frameDelayMillis: Long) {
        stopDisplay()
        val generation = encoderGeneration
        if (useStaticQr) {
            mutableState.value = mutableState.value.copy(displayFrame = payload, error = null)
            return
        }
        mutableState.value = mutableState.value.copy(encoding = true, error = null)
        encoderJob = scope.launch {
            when (val created = repository.createEncoder(payload)) {
                is NativeResult.Failure -> publishEncoder(generation) { it.copy(encoding = false, error = created.error.userMessage) }
                is NativeResult.Success -> {
                    val handle = created.value.handle
                    if (generation != encoderGeneration) {
                        repository.closeCodec(handle, ENCODER)
                        return@launch
                    }
                    encoderHandle = handle
                    try {
                        while (generation == encoderGeneration) {
                            when (val next = repository.nextFragment(handle)) {
                                is NativeResult.Success -> publishEncoder(generation) { it.copy(displayFrame = next.value.fragment, encoding = true) }
                                is NativeResult.Failure -> {
                                    publishEncoder(generation) { it.copy(encoding = false, error = next.error.userMessage) }
                                    break
                                }
                            }
                            delay(frameDelayMillis)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        publishEncoder(generation) { it.copy(encoding = false, error = UNEXPECTED_ERROR) }
                    } finally {
                        repository.closeCodec(handle, ENCODER)
                        if (encoderHandle == handle) encoderHandle = 0L
                    }
                }
            }
        }
    }

    fun stopDisplay() {
        encoderGeneration++
        encoderJob?.cancel(); encoderJob = null
        mutableState.value = mutableState.value.copy(displayFrame = null, encoding = false)
    }

    fun feedFrame(scope: CoroutineScope, fragment: String, onDecoded: (String) -> Unit = {}) {
        if (!fragment.startsWith(ECASH_PREFIX) || fragment.length > MAX_FRAGMENT_CHARS) return
        val generation = decoderGeneration
        scope.launch {
            synchronized(decoderLock) {
                if (generation != decoderGeneration || mutableState.value.decodedPayload != null || !seenFrames.add(fragment)) return@synchronized
                if (decoderHandle == 0L) when (val created = repository.createDecoder()) {
                    is NativeResult.Success -> decoderHandle = created.value.handle
                    is NativeResult.Failure -> {
                        mutableState.value = mutableState.value.copy(error = created.error.userMessage)
                        return@synchronized
                    }
                }
                when (val result = repository.addFragment(decoderHandle, fragment)) {
                    is NativeResult.Failure -> {
                        mutableState.value = mutableState.value.copy(error = result.error.userMessage)
                        stopDecoderLocked()
                    }
                    is NativeResult.Success -> if (result.value.complete) {
                        val payload = result.value.payload
                        if (payload.isNullOrBlank() || payload.length > MAX_DECODED_PAYLOAD_CHARS) {
                            mutableState.value = mutableState.value.copy(error = "The animated ecash QR code is invalid.")
                            stopDecoderLocked()
                        } else {
                            // The native registry consumes a completed decoder handle.
                            decoderHandle = 0L
                            seenFrames.clear()
                            mutableState.value = mutableState.value.copy(
                                decodeProgress = 0,
                                decodedPayload = payload,
                                classifiedInput = ClassifiedInput(InputType.ECASH, payload),
                                error = null,
                            )
                            onDecoded(payload)
                        }
                    } else mutableState.value = mutableState.value.copy(decodeProgress = seenFrames.size, error = null)
                }
            }
        }
    }

    fun consumeClassifiedInput() {
        mutableState.value = mutableState.value.copy(decodedPayload = null, classifiedInput = null)
    }
    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }

    fun stopDecoder() {
        synchronized(decoderLock) {
            decoderGeneration++
            mutableState.value = mutableState.value.copy(decodeProgress = 0, decodedPayload = null)
            stopDecoderLocked()
        }
    }

    fun close() {
        stopDisplay()
        classificationGeneration++
        classificationJob?.cancel(); classificationJob = null
        synchronized(decoderLock) {
            decoderGeneration++
            stopDecoderLocked()
            mutableState.value = EcashQrState()
        }
    }

    private fun stopDecoderLocked() {
        val handle = decoderHandle
        decoderHandle = 0L
        seenFrames.clear()
        mutableState.value = mutableState.value.copy(decodeProgress = 0)
        if (handle > 0) repository.closeCodec(handle, DECODER)
    }

    private inline fun publishEncoder(generation: Long, update: (EcashQrState) -> EcashQrState) {
        if (generation == encoderGeneration) mutableState.value = update(mutableState.value)
    }

    private companion object {
        const val ENCODER = "encoder"
        const val DECODER = "decoder"
        const val ECASH_PREFIX = "fedimint1"
        const val MAX_FRAGMENT_CHARS = 4 * 1024
        const val MAX_DECODED_PAYLOAD_CHARS = 16 * 1024
        const val UNEXPECTED_ERROR = "Animated ecash QR processing failed unexpectedly. Try again."
    }
}
