# Flutter visual oracle inventory

Audit date: 2026-09-03.

## Current retained Flutter build

The current retained Flutter sources were rebuilt successfully with
`flutter build apk --debug` and installed into the empty `cash.pyx.app` sandbox
on the disposable Android 14 x86_64 emulator. After its entrance animation
settled, the onboarding screen and a bounded restore-entry interaction were
captured under `build/flutter-reference/20260902T211000Z/`:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `onboarding.png` | 47,391 | `cbba7501cbec5e702eade2cbb8578d498d0558cc4d3eec0740b5b5091326da94` |
| `onboarding-restore-entry.mp4` | 195,910 | `2a55b684f835a33f7af5a7798feb504d96010578859472a3d8a8e36edc4be753` |

No wallet was created or restored, no seed word was entered, and no federation
or network action was attempted. The Flutter package and its empty emulator
data were uninstalled after capture. This is current runtime evidence for only
the onboarding/restore-entry surface, not for initialized-wallet screens.

## Historical design-pass captures

The Flutter application was captured at a 390×844 dp Android viewport during
the earlier design-system port. These checked-in `shots/app` images are
historical visual references for hierarchy, dark surfaces, and the Pyx orange
accent. Several are intermediate captures (for example, the landing image still
contains pre-Pyx wording), so the frozen behavior contract and screen inventory
override their labels and actions. They are not evidence that the current
Flutter revision still renders identically. Native Compose intentionally uses
Material 3 geometry, typography, controls, insets, and motion instead of
pixel-matching Flutter.

The `shots/ref` rows below are prototype renders, not Flutter runtime evidence.
They remain useful only where the old app bundle has no corresponding capture.
That distinction is explicit so prototype imagery is never presented as proof
of Flutter behavior.

| Required state | Existing reference | Kind | SHA-256 |
|---|---|---|---|
| Onboarding | `docs/design/shots/app/landing.png` | Flutter app | `3d554427dacfb7b0805645de29da567f1bfd68e646cae794b41bab188bc6d03c` |
| Home | `docs/design/shots/app/final-home.png` | Flutter app | `050f49cb736a982c45edfe4391c03d25349f5f9af018a0a51326546119fef183` |
| Home empty | `docs/design/shots/app/home-empty.png` | Flutter app | `821ceb1d0b5d55bc6e103f5b689a48b616183e487dcce76b3b3cc881fe67d32d` |
| Receive modes | `docs/design/shots/app/receive.png` | Flutter app | `154d5cdf065714c642538dc56073f0d1dd469365b95217f6cc440a00a7eedf5b` |
| Receive on-chain | `docs/design/shots/app/receive-onchain.png` | Flutter app | `e8b3c830918d22397778381bea566a06f50abd9446a5b323fd219faca9cc168c` |
| Lightning invoice | `docs/design/shots/app/lnv1-invoice.png` | Flutter app | `47dd162f6868f3c0ff017675eca163e37b6c7676f9f152c7df75b11775cb4b80` |
| LNURL receive | `docs/design/shots/app/lnv1-lnurl.png` | Flutter app | `881f4eaa95f560025f2814749d8818d49c817586755301c63e31573f5b43ea58` |
| Send entry | `docs/design/shots/app/send.png` | Flutter app | `d1ea0f6dcbe1ed8a04fa1d3163e5dff09ac95bbf19b3b6e8cda27f293eeeb6a0` |
| Federation/guardians | `docs/design/shots/app/fed-details.png` | Flutter app | `234bfdfc07eac7591e93169aaf6d44421f294d92718df47f737ae1081604e488` |
| Wallet selection | `docs/design/shots/app/wallets.png` | Flutter app | `ae3650575c62c24a525a3a9ab8805b3060b4e359956d0eccaeac9f5c119f41ee` |
| Settings | `docs/design/shots/app/settings.png` | Flutter app | `c9d5503ad3cf7e1082c5fc6e5a8a3cf2ac13d283497230494e7be5fad941b899` |
| Federation join states | `docs/design/shots/app/join.png`, `join2.png`, `join3.png` | Flutter app | `8067307e785bdabb45e1858d132f773fae7c91051c5e259e7ac88725d21b95e6`, `007960acf0d4c33c5f1d4c360a66ca5c332a0c0bbfb652d14a6f4ef25ce8d0ae`, `90446a3e371ac330e9b8728c205914d7fbe0857827201b46f96b3d2ddd03a45a` |
| Activity | `docs/design/shots/ref/activity.png` | Prototype only | `13cde8639fb1b490931ac5c0964b6ef7942d97cf3acd08459a2a72d94dd20ca3` |
| Payment detail | `docs/design/shots/ref/note-detail.png` | Prototype only | `80dd947f2289e3f2cde60cc244d602e9539efc869514fc44d0d3db7c877a31dd` |
| Seed backup | `docs/design/shots/ref/seed.png` | Prototype only | `4a36113a25a83f9e2916b501677e65c8544300bc5b88cad09b40274b80e37fab` |
| Access | `docs/design/shots/ref/access.png` | Prototype only | `9588953637d7714519f1a42e9b54d114594bf68110f67b393087db65fa8c0862` |

The current native counterpart is the secret-free, integrity-hashed matrix at
`build/screenshot-certification/20260903T092318Z/`. Review compares information,
state, action priority, and color identity—not raster geometry.

## Remaining oracle evidence

Flutter runtime screenshots are still missing for activity detail, authenticated
backup/recovery, explicit offline/error states, and several send confirmations.
Only the onboarding/restore-entry interaction has a current short Flutter
recording. Capturing the initialized-wallet states requires either a sanitized
initialized Flutter wallet fixture or the approved
live test federation; a prototype render or newly invented fake Flutter screen
must not be substituted for runtime behavior.

The 2026-09-03 headless-artifact audit confirmed that no additional current
Flutter runtime capture or guarded Flutter-oracle runner exists elsewhere in
the repository. Historical `shots/app` files and prototype `shots/ref` files
remain useful design context, but cannot close this runtime-evidence gate.
