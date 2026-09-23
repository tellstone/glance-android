# Glance

Glance is an independent, open-source Android Bitcoin wallet for watching single-signature HD wallets. It lets you monitor balances, transactions, UTXOs, and receive addresses without ever importing or storing private keys.

<p>
  <a href="https://zapstore.dev/apps/app.glance.wallet">
    <img src="https://img.shields.io/badge/Get%20it%20on-Zapstore-E16D3E?style=for-the-badge&logo=android&logoColor=white" alt="Get it on Zapstore" height="50" />
  </a>
  <a href="https://github.com/tellstone/glance-android">
    <img src="https://img.shields.io/badge/View%20on-GitHub-181717?style=for-the-badge&logo=github&logoColor=white" alt="View Glance on GitHub" height="50" />
  </a>
</p>

> **Beta software:** Glance is preparing its first public beta and is not production-ready. Use only non-sensitive, disposable public wallet data while developing or testing it, and independently verify displayed wallet information before acting on it.

## Features

- Watch-only BIP44, BIP49, BIP84, and BIP86 wallets.
- Supported output descriptors and fixed mainnet address monitoring.
- Cached-first balances, transaction history, UTXOs, and receiving addresses.
- Tor-routed Electrum, Esplora, and fiat requests by default, with an explicit direct-connection opt-out.
- Encrypted local persistence, PIN and biometric protection, and an isolated duress profile.
- Dark-only Compose UI with incremental wallet synchronization.

Glance deliberately does not import or store private keys or seed phrases, sign or broadcast transactions, support multisig or BIP47, or provide production testnet/signet support.

## Install beta releases

Signed beta APKs are published through [GitHub Releases](https://github.com/tellstone/glance-android/releases) and the [Glance Zapstore listing](https://zapstore.dev/apps/app.glance.wallet) as publishing becomes available.

Before installing an APK, verify its adjacent SHA-256 checksum and Android signing certificate. Releases support Android 7.0 and newer (API 24+) and never contain private keys or seed phrases.

## Build from source

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

## Project structure

| Module | Responsibility |
| --- | --- |
| `app` | Compose UI, navigation, and dependency wiring |
| `core-crypto` | Kotlin/JVM BIP32/44/49/84/86 derivation and address encoding |
| `core-network` | Electrum, Esplora, fiat, and Tor integration |
| `core-data` | Encrypted Room persistence and repositories |
| `core-security` | Android Keystore, encrypted preferences, PIN, and profile isolation |
| `core-common` | Shared domain models and utilities |

## Security and privacy

Never commit real extended public keys, descriptors, addresses, transaction IDs, server credentials, signing material, or local configuration. Use redacted fixtures for tests and diagnostics.

Tor helps hide the device’s network origin, but public Electrum and Esplora servers can still correlate wallet queries made together. Review the security-sensitive code and focused tests before changing wallet, network, persistence, or authentication behavior.

To report an issue, include only redacted diagnostics. Never attach extended public keys, descriptors, addresses, transaction IDs, server credentials, PINs, or release-signing material.

## License

Glance is licensed under the [MIT License](LICENSE).
