# Glance

Glance is a privacy-focused, watch-only Bitcoin wallet for Android. Monitor single-signature HD wallets without importing or storing private keys.

<p>
  <a href="https://zapstore.dev/apps/app.glance.wallet">
    <img src=".github/assets/get-it-on-zapstore.svg" alt="Get it on Zapstore" height="50" />
  </a>
  <a href="https://github.com/tellstone/glance-android">
    <img src=".github/assets/get-it-on-github.png" alt="View Glance on GitHub" height="50" />
  </a>
</p>

> **Beta software:** Use only public or disposable wallet data while testing, and independently verify wallet information before acting on it.

## Features

- Watch-only BIP44, BIP49, BIP84, and BIP86 wallets, descriptors, and fixed mainnet addresses.
- Monitor balances, transactions, UTXOs, and receiving addresses.
- Tor-routed Electrum, Esplora, and fiat requests by default.
- Encrypted local storage with PIN and biometric protection.

Glance deliberately does not import or store private keys or seed phrases, sign or broadcast transactions, support multisig or BIP47, or provide production testnet/signet support.

## Build from source

Requirements: JDK 17 and Android SDK Platform 37 with Build Tools 36.0.0.

On Windows:

```powershell
.\gradlew.bat build lint
```

## License

Glance is licensed under the [MIT License](LICENSE).
