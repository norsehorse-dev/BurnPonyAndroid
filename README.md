# BurnPony for Android

Self-destructing encrypted notes where the recipient installs nothing. You
write a note, set self-destruct rules, and share one link; it decrypts
client-side in any browser. The server stores only ciphertext it cannot
read — the decryption key travels in the URL fragment, which browsers never
transmit. No accounts anywhere.

This repository is the Android sender app (Kotlin, Jetpack Compose). The
recipient side is the web viewer.

## The protocol, server, viewer, and test vectors live elsewhere

[norsehorse-dev/BurnPonyiOS](https://github.com/norsehorse-dev/BurnPonyiOS)
is canonical for the wire-format specification (PROTOCOL.md, also live at
[burnpony.app/protocol](https://burnpony.app/protocol)), the PHP server, the
single-file viewer, and the shared cross-implementation test vectors. This
repository references them and does not fork them. The `shared/` directory
carries a copy of the canonical vectors so `:core:test` is self-contained;
the files in BurnPonyiOS are ground truth.

## Modules and flavors

- `core/` — the BurnPony v1 crypto core: pure Kotlin/JVM, zero third-party
  dependencies (JCA primitives, hand-rolled RFC 5869 HKDF, deterministic
  payload serializer, strict base64). Byte-compatible with the CryptoKit and
  WebCrypto implementations, proven by the vector suite.
- `app/` — the Compose app. Two flavors on the `dist` dimension:
  - `standard` — Play distribution; adds firebase-messaging for read-receipt
    push. Firebase is initialized manually; the google-services Gradle
    plugin is not applied.
  - `foss` — F-Droid distribution; no Google artifacts anywhere in the
    dependency graph (`./gradlew :app:dependencies --configuration
    fossReleaseRuntimeClasspath` shows none). Read receipts arrive via
    status polling.

## Building

JDK 17+, Android SDK 36. The Gradle wrapper pins everything else.

    ./gradlew :core:test                 # crypto gate: 100% vector parity
    ./gradlew :app:assembleFossDebug
    ./gradlew :app:assembleStandardDebug

Release builds read signing material from `app/keystore.properties`
(untracked); without it the release output is unsigned, which is what
F-Droid's verification build expects.

## Trust model, briefly

Every note gets a per-note management token, issued at creation, capable of
burn/status/expiry-change but never decryption, and stored server-side only
as a SHA-256 hash. The optional note label never leaves the device. The
full framing is on the [protocol page](https://burnpony.app/protocol).

## License

Apache-2.0. BurnPony is part of the pony family of privacy tools:
encryption you can inspect instead of taking a developer's word for it.
