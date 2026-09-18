# Building and running the app

Assumes `docs/02-BUILD-NATIVE.md` is done and `app/src/main/jniLibs/` contains
`libbitcoind.so`. If it doesn't, debug builds still compile — you just get an
app that cannot start a node.

## Requirements

| | |
|---|---|
| Android Studio | Ladybug or newer |
| JDK | 17 (bundled with Studio) |
| Gradle | 8.11.1, via the wrapper |
| AGP / Kotlin | 8.9.1 / 2.1.0 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 28 |

Versions are pinned in `gradle/libs.versions.toml`, deliberately. Move them with
Studio's AGP Upgrade Assistant rather than by hand — the Compose compiler plugin
version is tied to the Kotlin version and they must move together.

## Build

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/
./gradlew installDebug           # to a connected device
./gradlew bundleRelease          # AAB for Play; needs keystore.properties
```

Debug builds get `.debug` appended to the application ID, so a debug and a
release build coexist on one device. That matters here: you will want to run a
signet debug build next to a mainnet release build.

## Module layout

Single-module by choice. The obvious alternative — `:core`, `:data`, `:ui`,
`:wallet` — buys parallel compilation and enforced boundaries, and costs a pile
of Gradle configuration for an app this size. The package structure already
mirrors the boundaries, and if the build time becomes a problem, splitting along
those package lines is a mechanical change.

```
com.solitech.bitcoincorenode
├── core/       rpc, node, model, prefs, util   ← no Android UI imports
├── data/       repositories, db, tor
├── wallet/     key handling, descriptors, PSBT
├── ui/         theme, components, screens, nav
├── service/    NodeService, TorService
└── di/         Hilt modules
```

`core/` deliberately avoids Compose and Activity imports. That is what makes the
iOS port (doc 07) a UI rewrite rather than an architecture rewrite.

## Running against a node

Three ways, easiest first.

**Signet, embedded.** Settings → Network → signet, then start the node. Signet's
chain is small enough to sync from scratch on a phone in minutes, which makes it
the right target for everyday development.

**Regtest, remote.** Run `bitcoind -regtest` on your machine, then add a LAN
endpoint in the app. Instant blocks, free coins, no waiting:

```bash
bitcoind -regtest -rpcbind=0.0.0.0 -rpcallowip=192.168.0.0/16 \
         -rpcuser=dev -rpcpassword=dev -fallbackfee=0.0002
bitcoin-cli -regtest createwallet test
bitcoin-cli -regtest -generate 101      # 101 blocks: coinbase maturity
```

The app requires a typed confirmation before saving a plaintext LAN endpoint.
That is intentional and it applies to debug builds too — the friction is the
point, and turning it off for convenience is how it ends up off in a release.

**Mainnet, embedded.** Real. Slow. Do this last, and with an amount you would
not mind losing.

## Tests

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest    # needs a device or emulator
```

Where the unit tests should concentrate, in priority order:

1. **`Sats` parsing and formatting.** Every boundary: `0`, one satoshi,
   21 000 000 BTC, nine decimal places (must reject), a value with a comma, a
   negative. This is the money type and an off-by-one satoshi here is a real
   defect.
2. **`RpcError.Rpc.Kind` mapping.** Each of Core's codes lands in the right
   bucket and produces the right sentence.
3. **The `warnings` string-or-array serializer**, both shapes.
4. **`Bip21.parse`** against malformed and hostile input, including a `req-`
   parameter it must refuse.
5. **`ChainRepository.snapshot()`** against a MockWebServer batch response
   returned out of order — that path silently misattributes results if the id
   matching breaks, which shows up as a wrong balance rather than an error.

## Things that will look like bugs but aren't

**The node takes 20+ seconds to respond after launch.** Core loads its block
index before binding the RPC port. That's what the log tailer is for — the
startup screen should be narrating, not spinning. If it *is* just spinning,
check that `debug.log` exists in the datadir.

**Progress sits at 99.99% for a long time.** `verificationprogress` is
exponential near the tip. The UI shows blocks-behind alongside it for exactly
this reason.

**Fee estimates say "no estimate".** A node that just started, or one in
blocks-only mode, genuinely cannot estimate. That message is correct and
deliberate; the alternative is inventing a number and getting a transaction
stuck.

**The mempool is empty on a synced node.** Almost always blocks-only mode.

## Common build failures

| Symptom | Cause |
|---|---|
| `libbitcoind.so not found in native library directory` | Native build not run, or `abiFilters` doesn't match the device |
| Node starts then dies instantly, exit 13 | `useLegacyPackaging` got set to false — see the comment in `app/build.gradle.kts` |
| `Unresolved reference: Hilt...` | KSP didn't run; `./gradlew clean` then rebuild |
| Compose compiler version mismatch | Kotlin and the Compose plugin were bumped independently |
