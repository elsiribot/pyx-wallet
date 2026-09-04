# Recovery and expiry JNI contract

Recovery remains owned by the Fedimint client. Closing a native subscription
stops only observation; it does not cancel or roll back recovery. Kotlin resumes
the UI by reading a fresh snapshot and subscribing again.

```kotlin
internal object NativeBindings {
    external fun recoveryExpirySnapshotAsync(
        clientHandle: Long,
        callback: NativeRequestCallback,
    ): Long

    external fun subscribeRecovery(
        clientHandle: Long,
        callback: NativeSubscriptionCallback,
    ): Long

    // Shared with balance and connection subscriptions.
    external fun closeSubscription(subscriptionHandle: Long): String
}
```

The snapshot callback returns:

```json
{
  "hasPendingRecoveries": true,
  "expiresAtEpochSeconds": 1800000000,
  "successorInvitePresent": true,
  "successorInvite": "fedimint:..."
}
```

The expiration and successor fields are nullable. The successor payload is
returned only by this confirmation-oriented endpoint, is bounded to 64 KiB,
and must be held as sensitive transient UI state: do not log, persist, place in
navigation state, or copy automatically. `successorInvitePresent` is false when
the payload is absent or invalid. Snapshot requests use `NativeRequestCallback`
and the existing exactly-once `cancelRequest` contract.

Each recovery subscription event is bounded JSON:

```json
{
  "moduleId": 1,
  "complete": 2,
  "total": 5,
  "finished": false,
  "aggregateComplete": 2,
  "aggregateTotal": 5,
  "allFinished": false
}
```

The aggregate covers the latest values for modules observed by that
subscription. Kotlin should use `allFinished`, derived from the core client's
pending-recovery state, as the terminal signal and refresh the snapshot before
reloading the client. Module/progress fields must fit nonnegative Kotlin `Long`
values and `complete <= total`; malformed core events terminate observation with
a bounded error. `closeSubscription` is idempotent and waits for an in-flight
callback, guaranteeing no callback begins after close returns.
