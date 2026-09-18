# Bitcoin Core Node

A full **Bitcoin Core 30.3** node and descriptor wallet for Android, with a
cyberpunk HUD interface.

Not a light client. Not an SPV wallet. Not a front end to somebody else's
server. The actual `bitcoind` binary, cross-compiled for arm64, running as a
child process on the phone — with the option to drive a node you run elsewhere
instead, over Tor.

```
┌───────────────┐   JSON-RPC    ┌──────────────────────────┐
│  Compose UI   │◄─────────────►│ libbitcoind.so (arm64)   │
│  Kotlin       │  127.0.0.1    │ Bitcoin Core 30.3        │
└───────────────┘               └──────────────────────────┘
        │           JSON-RPC over Tor
        └──────────────────────────────► your own node, anywhere
```

One `RpcClient` interface, two transports. Every screen works identically
against either.

---

## Status

| Area | State |
|---|---|
| Native build chain (Core → Android) | Written, not yet run end-to-end |
| RPC layer, models, error taxonomy | Complete; models verified against Core source |
| Node supervisor, lifecycle, log tailing | Complete |
| Design system | Complete |
| Screens | Dashboard, Node, Peers, Mempool, Console, Wallets, Wallet, Send, Receive, Settings |
| Wallet: send / coin control / RBF / PSBT | Complete via Core RPC |
| Block-explorer fallback (Blockbook) | Client, models and settings complete; **schema not yet verified against a live server** |
| Donation footer / panel | On every screen, plus the send screen; address checksum-tested in CI |
| Tor | Transport implemented; requires Orbot. Bundled Tor is a follow-up |
| Play release pipeline | Scaffolded — see `docs/06-PLAY-RELEASE.md` |
| iOS | Planned — see `docs/07-IOS-PORT.md` |

**Nothing here has been compiled yet.** It was written without an Android SDK
available. Expect the first build to surface import and signature fixes; the
architecture and the Core integration are the parts that were worth getting
right up front.

---

## Build

Two steps, in order.

### 1. The native payload (once, ~30–60 min)

```bash
cd native/scripts
export BCN_BUILD_DIR="$HOME/bcn-build"
export BITCOIN_SRC="$HOME/bitcoin-30.3"          # extracted bitcoin-30.3.zip
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.2.12479018"
./build-all.sh
```

Produces `app/src/main/jniLibs/{arm64-v8a,x86_64}/libbitcoind.so`.
Full detail, including why the build paths must be space-free: **`docs/02-BUILD-NATIVE.md`**.

### 2. The app

```bash
./gradlew assembleDebug
```

A release build refuses to start if the native payload is missing, rather than
producing an APK with no node in it.

---

## Documentation

| | |
|---|---|
| `docs/01-ARCHITECTURE.md` | Why it is built this way, and what was rejected |
| `docs/02-BUILD-NATIVE.md` | Cross-compiling Bitcoin Core for Android |
| `docs/03-BUILD-APP.md` | Gradle, modules, running it |
| `docs/04-SECURITY.md` | Threat model, key handling, what this app does not protect against |
| `docs/05-USER-MANUAL.md` | For the person holding the phone |
| `docs/06-PLAY-RELEASE.md` | Signing, Data Safety, store listing, the release runbook |
| `docs/07-IOS-PORT.md` | What ports cleanly, and the one thing that does not |

---

## Licence

Bitcoin Core is MIT. This app is MIT. The Core source is used unmodified —
there are no patches to consensus, wallet, or validation code, and there never
should be.
