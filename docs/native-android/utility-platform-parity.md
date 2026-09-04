# Native Android utility and platform parity

This audit follows the retained Flutter product contract; it does not add new
notification, background-service, privacy-policy, or spending policy features.

## Implemented

- Satoshi integers and canonical decimal-string fiat values are localized only
  at presentation time. JNI/storage remain locale-independent ASCII values.
  Payment timestamps use the device locale and time zone.
- Non-secret receive payloads use Android `ACTION_SEND` through the system
  Sharesheet. Seed words, ecash and other secret result surfaces retain their
  no-share policy. Copy remains explicit and sensitive copies use timed,
  ownership-safe clearing.
- Payment events remain in-app Snackbar notices, matching Flutter's transient
  overlay behavior. The Snackbar host is a polite accessibility live region;
  errors remain assertive. The app requests no notification permission and
  posts no system notification.
- Settings/About exposes currency, federation/access/backup/contact routes,
  Bitcoin network, runtime version/build type, source, and the repository's
  open-source license.

## Intentionally absent

- No system notifications, notification channels, background payment service,
  notification actions, widgets, shortcuts, or quick-spend action exist in the
  current Flutter product contract.
- No quick-spend limit is shown because there is no authoritative persisted
  wallet policy behind it.
- No privacy-policy hyperlink is invented: the current product supplies no
  approved privacy URL. Backup/data extraction restrictions and secret-handling
  behavior remain documented technical controls, not a substitute legal policy.
- Locale tests prove deterministic formatting behavior, but full TalkBack,
  locale matrix, bidirectional text, Sharesheet target, and hardware review
  remain external certification gates.
