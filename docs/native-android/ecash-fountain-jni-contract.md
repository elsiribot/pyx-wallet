# Animated ecash QR JNI contract

Simple ecash payload QR display/scanning remains the preferred fallback when a
payload fits one frame. These typed codecs add animated fountain fragments for
larger payloads without changing claim semantics.

```kotlin
internal object NativeBindings {
    external fun createEcashEncoder(payload: String): Long
    external fun nextEcashFragment(encoderHandle: Long): String
    external fun createEcashDecoder(): Long
    external fun addEcashFragment(decoderHandle: Long, fragment: String): String
    external fun closeEcashCodec(handle: Long, kind: String): String
}
```

`nextEcashFragment` returns bounded JSON:

```json
{"fragment":"fedimint1..."}
```

Fragments may be presented continuously. Fountain fragments never repeat, and
the decoder accepts valid fragments in any order and tolerates duplicates.
Because ordinary ecash tokens and fountain fragments share the `fedimint1...`
prefix, decoder ingestion first attempts a complete static ecash parse. A static
token therefore completes immediately; only non-token frames enter the fountain
decoder. This matches camera scanning without requiring prefix heuristics.
`addEcashFragment` returns `{"complete":false}` until reconstruction, then
exactly one caller can receive:

```json
{"complete":true,"payload":"fedimint1..."}
```

No partial payload is returned. Completion atomically closes the decoder; a
duplicate/concurrent terminal call receives an invalid-handle error and cannot
win again. The final payload must still be passed through the ordinary explicit
ecash claim/confirmation flow.

Payloads are capped at 64 KiB, fragments at 4 KiB, and both require the
lowercase Fedimint prefix. Invalid payloads, fragments, and panics cross JNI as
bounded errors that never echo input.

Encoder and decoder handles have distinct types and generation counters.
`closeEcashCodec(handle, "encoder")` and `closeEcashCodec(handle, "decoder")`
are idempotent for the current generation, wait for an in-flight codec call,
and reject wrong kinds or stale handles. Screens must close their codec in
`onDispose`; completion already closes the decoder safely.
