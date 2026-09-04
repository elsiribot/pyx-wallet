# Recovery phrase assistance JNI contract

These APIs are pure validation helpers. They do not open or mutate a wallet,
persist input, log it, or return submitted phrase content.

```kotlin
internal object NativeBindings {
    external fun seedWordSuggestions(prefix: String): String
    external fun validateSeedPhrase(wordsJson: String): String
}
```

`seedWordSuggestions` accepts an ASCII lowercase prefix of at most 16 bytes and
returns at most eight lowercase English BIP39 words:

```json
{"suggestions":["abandon","ability","able","about","above","absent","absorb","abstract"]}
```

An empty prefix is allowed for an initial bounded suggestion list. Uppercase,
non-ASCII, or oversized prefixes are rejected with a bounded error that does
not echo the prefix.

`validateSeedPhrase` accepts a JSON string array and returns only structural
metadata:

```json
{"valid":false,"wordCount":12,"invalidIndices":[3],"checksumValid":false}
```

Indices are zero-based. `invalidIndices` identifies empty, malformed, or
non-English-BIP39 words. `checksumValid` can be true only when exactly 12 words
are individually valid and the same BIP39 parser used by `restoreWallet`
accepts their checksum. `valid` is currently identical to `checksumValid`
because native restore has an exact 12-word policy.

The JSON request is capped at 1024 bytes, at most 24 diagnostic positions are
accepted, and each word is capped at 16 ASCII lowercase bytes. Malformed or
oversized JSON is rejected without including any word in the error. Kotlin must
still clear phrase state when the restore screen backgrounds or exits.
