# Glance

Glance is an in-progress Android Bitcoin wallet for **watching** single-signature HD wallets. It is a Kotlin implementation with no private-key or seed storage, no transaction signing, and no transaction broadcasting.

> **Project status:** Glance is preparing its first public beta. It is not production-ready software. Use only non-sensitive, disposable public wallet data while developing or testing it, and independently verify all displayed wallet information before acting on it.

## What it supports

- Watch-only BIP44, BIP49, BIP84, and BIP86 HD keys, supported descriptors, and fixed mainnet addresses.
- Cached-first balances, transaction history, UTXOs, and receiving addresses.
- Tor-routed Electrum, Esplora, and fiat traffic by default, with an explicit direct-connection opt-out.
- Encrypted local persistence, PIN/biometric protection, and an isolated duress profile.

Glance deliberately does **not** import or store private keys or seed phrases, sign or broadcast transactions, support multisig or BIP47, or provide production testnet/signet support.

## Build

Requirements:

- JDK 17
- Android SDK Platform 37 and Build Tools 36.0.0
- An Android device or emulator for connected tests

On Windows:

```powershell
.\gradlew.bat build lint
```

Run a focused JVM test:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests app.glance.wallet.WalletDetailPresentationTest
```

Connected Android tests require a device or emulator already visible to ADB:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Beta releases

Signed beta APKs are published as [GitHub Releases](https://github.com/tellstone/glance-android/releases) and, once publisher verification is complete, through Zapstore's beta channel. Verify the APK's adjacent SHA-256 checksum and Android signing certificate before installation. Releases are for Android 7.0+ (API 24+) and never contain private keys or seed phrases.

To report an issue, include only redacted diagnostics. Never attach extended public keys, descriptors, addresses, transaction IDs, server credentials, PINs, or release-signing material.

## Repository layout

- `app` — Compose UI, navigation, and dependency wiring.
- `core-crypto` — Kotlin/JVM watch-only derivation and address encoding.
- `core-network` — Electrum, Esplora, fiat, and Tor integration.
- `core-data` — encrypted Room persistence and repositories.
- `core-security` — Android Keystore, encrypted preferences, PIN, and profile isolation.

## Security and privacy

Do not commit real extended public keys, descriptors, addresses, transaction IDs, server credentials, signing material, or local configuration. The repository ignores common Android signing and credential files; keep any local test configuration outside version control.

Using public Electrum or Esplora servers has privacy trade-offs: Tor hides the device network origin, but a server can still correlate the wallet data queried together. Review the security-sensitive code and its focused tests before changing wallet, network, or persistence behavior.

## License

Glance is licensed under the [MIT License](LICENSE).
