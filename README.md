# Pyx Wallet

A minimal Fedimint wallet built with Flutter and Rust, based on
[conduit](https://github.com/joschisan/conduit) and styled after the design
prototype in `docs/design/prototype.html` (stable-balance module off).

## Features

- Lightning payments
- eCash transactions
- Multi-federation support
- Biometric authentication
- Seed phrase backup & recovery

## Development

```bash
nix develop            # Flutter, Rust (android targets), SDK/NDK, tooling
tool/build-android-nix.sh
```

Design system reference: `docs/design/tokens.md`. Implementation plan:
`docs/superpowers/plans/2026-08-23-design-system-port.md`.
