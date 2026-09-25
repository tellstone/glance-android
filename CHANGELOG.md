# Changelog

All notable changes to Glance are documented in this file.

## [0.1.0-beta.12] - 2026-09-25

### Added

- Expanded the watch-only wallet presentation with wallet detail, transaction, UTXO, receive, support, and settings flows.
- Added transaction input/output details and persisted transaction labels.
- Added an isolated, independently generated BIP84 watch-only decoy wallet for duress unlock.

### Improved

- Hardened wallet UI, persistence, sync, Tor, and security behavior ahead of interim distribution.
- Refined release verification for signed APKs, stripped debug logging, and public donation configuration.

### Known limitations

- Stealth/street mode and encrypted backup/import remain planned work.
- Use only public wallet data and verify balances independently before acting on them.

## [0.1.0-beta.1] - Unreleased

### Added

- Initial public beta of the watch-only Android wallet.
- Watch-only BIP44, BIP49, BIP84, and BIP86 HD keys, descriptors, and fixed mainnet addresses.
- Tor-routed Electrum, Esplora, and fiat traffic by default.
- Encrypted local storage, PIN/biometric protection, and an isolated duress profile.

### Known limitations

- This beta does not yet include stealth/street mode or encrypted backup/import.
- Use only public wallet data and verify balances independently before acting on them.
