# Third-party notices

OpenTunnel 0.3.5 includes the components below.

## sing-box / libbox

Source: https://github.com/SagerNet/sing-box, tag `v1.14.4`, commit
`c005f35300ce76427dc285481ec5695f5b179cd2`.

License: GPL-3.0.

File: `app/libs/libbox.aar`, built locally from that source. Build steps are in
[`docs/BUILDING-LIBBOX.md`](docs/BUILDING-LIBBOX.md). SHA-256:
`faad510cb3e8270859f6a1e1d2741e723a476980ba5dff0415e9095ef29e9053`.

libbox is linked into the APK. The upstream commit above is the corresponding
source; this repository contains the app source.

## usque

Source: https://github.com/Diniboy1123/usque, release `v4.2.1`.

License: MIT, copyright 2025 github.com/Diniboy1123. Text:
[`LICENSES/usque-MIT.txt`](LICENSES/usque-MIT.txt).

File: `app/src/main/jniLibs/arm64-v8a/libusque.so`. This is the `usque` binary
from the official `usque_4.2.1_android_arm64.zip`, renamed and not modified. Zip
SHA-256: `2754ff58786297319b4a037dccda4aefe7a33587d5c1bee34ee4b6e86a3fbaa9`.
Binary SHA-256:
`6c6c47ca3a0dcc5f0d6df75c703476fa9aa18e692015d3236dd11751a6ad16ce`.

## AndroidX, Jetpack Compose, Kotlin

Sources: https://developer.android.com/jetpack/androidx and
https://kotlinlang.org.

Components: core-ktx 1.15.0, activity-compose 1.10.1, compose-bom 2025.05.01,
Kotlin standard library and kotlinx-coroutines 1.10.2.

License: Apache-2.0. Text: [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).

## OkHttp

Source: https://square.github.io/okhttp/, version 4.12.0. Copyright Square, Inc.

License: Apache-2.0. Text: [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).
