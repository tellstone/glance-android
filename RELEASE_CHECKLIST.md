# Glance public-beta release checklist

Do not create or publish a release tag until every applicable item has been completed and recorded in the GitHub release notes or linked issue.

## Maintainer setup (one time)

- Create an offline-kept Android APK signing keystore and record its certificate SHA-256 fingerprint in a secure maintainer record.
- Add GitHub environment `release` and restrict approval to maintainers.
- Add secrets: `GLANCE_RELEASE_KEYSTORE_BASE64`, `GLANCE_RELEASE_STORE_PASSWORD`, `GLANCE_RELEASE_KEY_ALIAS`, `GLANCE_RELEASE_KEY_PASSWORD`, `GLANCE_DONATION_ON_CHAIN`, `GLANCE_DONATION_LIGHTNING`, and `ZAPSTORE_BUNKER_URL`.
- Add the maintainer's public `npub` to `zapstore.yaml`, commit it, and complete Zapstore certificate linking/allowlisting.
- Set repository variable `ZAPSTORE_PUBLISH_ENABLED` to `true` only after the committed `npub` has been verified by Zapstore.

## Release candidate evidence

- Run `./gradlew :app:testDebugUnitTest :core-crypto:test :core-network:test :core-data:test :core-security:test :app:lintRelease`.
- Run the connected Android test suite on a physical device.
- Run the Tor-only Mempool current-price and history smoke test on a physical device; record that no clearnet fallback occurred.
- Validate a disposable real mainnet watch target through import, sync, receive, transactions, UTXOs, charts, deletion, fresh install, upgrade, duress isolation, and erase-all-data.
- Complete the dependency audit, sensitive-log review, and manual security checklist from Phase 10 of the product specification.
- Confirm the generated app icons and VectorDrawable branding assets visually on supported Android versions.

## Publish

- Tag the reviewed commit `v0.1.0-beta.N` and push the tag.
- Verify the signed APK and SHA-256 asset on the resulting GitHub release.
- Verify the Zapstore beta listing’s package ID, version code, signer certificate, description, and APK download.
