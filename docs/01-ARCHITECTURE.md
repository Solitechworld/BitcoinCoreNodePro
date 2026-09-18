# Bitcoin Core Node — Architecture

**Target:** Android 8.0 (API 26) → Android 16 (API 36)
**Upstream:** Bitcoin Core 30.3 (`bitcoin-30.3.zip`, commit `49faec4f87f5cd19c88db01a82e5c68b087c8227`)
**Language split:** Kotlin 2.x + Jetpack Compose (app) · C++20 (Bitcoin Core, unmodified) · CMake/Bash (native build)

---

## 1. The core design decision

Every feature in this app is expressed as **Bitcoin Core JSON-RPC**.

That single choice is what makes the hybrid node model tractable. There is exactly one
`RpcClient` interface. It has two transports:

| Mode | Transport | Auth | Where the chain lives |
|---|---|---|---|
| **Embedded** | HTTP to `127.0.0.1:8332` | `.cookie` file read from app-private storage | On the phone |
| **Remote** | HTTP over Tor SOCKS5, or LAN HTTP | `rpcuser:rpcpassword` or uploaded cookie | On your own machine |

The UI layer, the wallet layer, the explorer, the mempool view and the RPC console are
*identical* in both modes. Switching a wallet from embedded to remote changes one field in a
Room row. No screen knows or cares which transport is underneath.

This is deliberately different from how most mobile Bitcoin apps are built (a bespoke SPV
client with a hand-written protocol layer). Here, the protocol layer **is** Bitcoin Core.
Consensus rules, mempool policy, fee estimation, descriptor parsing, PSBT handling and
signing are all upstream C++ that we compile but never reimplement.

### What that buys, and what it costs

**Buys:** zero consensus divergence; PSBT/descriptor/miniscript support for free; every future
Core release is a recompile, not a rewrite; the RPC console is a real console.

**Costs:** the APK carries a ~12–15 MB native payload; the embedded node needs a foreground
service, a wakelock policy, and a storage budget; and JSON-RPC over localhost has real
serialization overhead on hot paths (mitigated in §6).

---

## 2. Process model

```
┌──────────────────────────────────────────────────────────────────────┐
│ Android app process  (com.solitech.bitcoincorenode)                       │
│                                                                      │
│  ┌────────────┐  ┌──────────────┐  ┌───────────────┐  ┌───────────┐  │
│  │  Compose   │  │  ViewModels  │  │ Repositories  │  │   Room    │  │
│  │     UI     │◄─┤ (StateFlow)  │◄─┤  (suspend)    │◄─┤   cache   │  │
│  └────────────┘  └──────────────┘  └───────┬───────┘  └───────────┘  │
│                                            │                          │
│                                    ┌───────▼────────┐                 │
│                                    │   RpcClient    │                 │
│                                    │  (interface)   │                 │
│                                    └───┬────────┬───┘                 │
│                             Embedded   │        │  Remote             │
│                                        │        │                     │
│  ┌─────────────────────────────────────▼──┐  ┌──▼───────────────────┐ │
│  │ NodeService (foreground, own :node proc)│  │ TorTransport (SOCKS5)│ │
│  │  • spawns + supervises child process    │  └──────────┬───────────┘ │
│  │  • parses debug.log → StateFlow         │             │             │
│  │  • owns the notification + wakelock      │             │             │
│  └────────────────┬─────────────────────────┘             │             │
└───────────────────┼───────────────────────────────────────┼─────────────┘
                    │ fork/exec                              │ .onion:8332
        ┌───────────▼─────────────┐                 ┌────────▼──────────┐
        │  libbitcoind.so         │                 │  Your own node    │
        │  (Bitcoin Core 30.3,    │                 │  somewhere else   │
        │   unmodified, arm64)    │                 └───────────────────┘
        │  HTTP RPC on 127.0.0.1  │
        └─────────────────────────┘
```

### Why a child process and not a JNI library

Bitcoin Core's `bitcoind` is written as a program, not a library: it owns global state
(`chainman`, `node.args`, the scheduler, signal handlers) and its shutdown path assumes the
process is about to end. Loading it in-process via JNI means:

* a node crash takes the UI down with it;
* you cannot stop and restart the node without restarting the app;
* `boost::signals2` and `libevent` teardown races surface as ANRs.

So we `exec()` it instead. The app supervises a real OS process, exactly like a desktop.

**The Android constraint:** since API 29, apps may not `exec()` files from their writable data
directory (W^X). Files inside `nativeLibraryDir` are the documented exception. We therefore
ship the `bitcoind` ELF binary as **`libbitcoind.so`** in `jniLibs/arm64-v8a/`, with
`android:extractNativeLibs="true"` so the installer places a real executable file on disk at
`ApplicationInfo.nativeLibraryDir/libbitcoind.so`. It is a normal PIE executable that happens
to be named `.so`. This is the same mechanism Tor's Android package uses.

`bitcoin-cli` ships the same way as `libbitcoincli.so` and backs the RPC console's
"raw CLI" mode.

### One app process, not two

An earlier revision put `NodeService` in `android:process=":node"` for crash isolation. That
was wrong and has been reversed.

`NodeSupervisor` is a Hilt `@Singleton`, and **Hilt singletons are per process**. Under
`:node` the service constructed its own supervisor while every screen observed a different
one that had never started anything: the service really did run a node, and the UI could not
see it. Bridging the two would need AIDL or a `Messenger` for no real gain — `bitcoind` is
already a separate OS process, so a node crash cannot take the app down either way, which was
the only benefit the split was bought for.

### Background policy: minimise keeps syncing, close stops

Three distinct events, three behaviours:

| Event | What happens |
| --- | --- |
| App minimised | Nothing. `NodeService` is a foreground service (`dataSync`), so the process stays alive and the sync continues. The ongoing notification carries a **Stop node** action. |
| App closed (swiped from recents) | `NodeService.onTaskRemoved()` shuts the node down **cleanly** — Core flushes its UTXO cache — and records `node_was_running = true`. |
| App reopened | `NodeAutoResume.resumeIfNeeded()`, called from `MainActivity.onCreate`, starts the service again if and only if the supervisor is `Stopped` *and* that flag is set. |

`android:stopWithTask="false"` is required for the middle row: with `true`, Android kills the
process the moment the task is removed, which can interrupt the flush and corrupt the block
database.

Two deliberate exclusions:

* **A crashed node does not auto-resume.** Only `NodeState.Stopped` resumes. Relaunching a
  binary that just died — on a corrupt datadir, or a full disk — turns one failure into a
  loop whose first cause the user never sees.
* **Resume is not gated on the app lock.** Downloading blocks touches no wallet, balance or
  address, so making the user unlock before their node will resume would penalise them for
  having a lock at all.

An explicit **Stop node** clears the flag, so "stopped" stays stopped across restarts.

---

## 3. Making a full node fit on a phone

A stock IBD is ~700 GB of block data validated from genesis. That is not happening on a
handset. Three upstream mechanisms, used together, make it viable:

### 3.1 assumeutxo (`loadtxoutset`)

Core 30.3 ships hardcoded, hash-verified UTXO snapshot commitments in `chainparams.cpp`.
Loading a snapshot gives you a node that is **usable at the tip within minutes**, while a
background chainstate validates history from genesis and eventually converges.

Flow implemented in `AssumeUtxoManager`:

1. Read the compiled-in snapshot commitments from `AssumeUtxoCommitments` (transcribed
   from Core's `kernel/chainparams.cpp`; mainnet currently offers heights 840 000,
   880 000 and 910 000).
2. Download `utxo-<height>.dat` from a source the user chooses — **we ship no default
   host**, see §7.
3. `loadtxoutset <path>` with no client timeout.
4. Poll `getchainstates` to drive the dual-progress UI (snapshot chain vs background chain).
5. Delete the `.dat` on success.

**On who verifies what.** It is tempting to assume the app should SHA256 the downloaded
file against a hash compiled into Core. It cannot, and it does not need to. What Core
commits to in `chainparams.cpp` is `hash_serialized` — a hash of the *deserialized UTXO
set*, not of the file's bytes. Core computes it while loading and rejects a snapshot that
does not match. So the trust anchor is Core's own compiled-in commitment, checked by
Core's own code, and a corrupted or malicious download fails at `loadtxoutset` rather than
being silently accepted.

The app therefore does **not** claim to have verified the file before handing it over, and
the UI does not display a reassuring green tick until Core has finished loading it. An
app-level integrity check on the download would be theatre wearing the costume of security.

The UI is explicit that this is a **trust-minimised bootstrap, not trustless**: until the
background chainstate finishes, you are trusting that the snapshot hash in Core's source is
correct. The Security screen states this in those words.

### 3.2 Pruning

Default `prune=5000` (5 GB of recent blocks). Storage budget picker in Settings offers
2 GB / 5 GB / 10 GB / manual. Pruning is incompatible with rescanning old wallets from
before the prune horizon, and the wallet import flow refuses birthdates below it with a clear
message rather than silently missing funds.

### 3.3 Block filters

`blockfilterindex=1` + `peerblockfilters=1` so descriptor rescans over the pruned window stay
fast, and so the app can serve filters if the user opts into inbound connections.

### Resulting storage envelope

| Component | Size |
|---|---|
| APK install (arm64 only) | ~28 MB |
| assumeutxo snapshot (transient) | ~11 GB during load, deleted after |
| Pruned blocks (default) | 5 GB |
| chainstate | ~7 GB |
| Block filter index | ~4 GB |
| Wallets + app DB | < 100 MB |
| **Steady state** | **~16 GB** |

Onboarding refuses to start an embedded node with less than 25 GB free and says why.

---

## 4. Module map

```
BitcoinCoreNode/
├── native/                     ← everything that produces libbitcoind.so
│   ├── scripts/
│   │   ├── 00-fetch-deps.sh        libevent, sqlite, boost headers
│   │   ├── 10-build-deps.sh        NDK cross-compile of the three deps
│   │   ├── 20-build-core.sh        Bitcoin Core 30.3 → bitcoind + bitcoin-cli
│   │   ├── 30-package-jnilibs.sh   rename → lib*.so, verify 16 KB alignment
│   │   └── build-all.sh            one-shot driver
│   ├── cmake/android-core.cmake    toolchain glue Core needs on Android
│   └── patches/                    minimal, documented, upstreamable
│
└── app/src/main/java/com/solitech/bitcoincorenode/
    ├── core/
    │   ├── rpc/        RpcClient, JsonRpc envelope, typed method surface,
    │   │               CookieAuth, RpcError taxonomy, batching
    │   ├── node/       NodeSupervisor, BitcoinConf builder, DebugLogTailer,
    │   │               AssumeUtxoManager, NodeState machine
    │   ├── model/      Immutable domain types (no JSON in the UI layer)
    │   ├── prefs/      DataStore-backed settings
    │   └── util/       Amount/unit formatting, Bech32, hex, QR
    ├── data/
    │   ├── repo/       ChainRepository, WalletRepository, PeerRepository,
    │   │               MempoolRepository, TxRepository
    │   ├── db/         Room: cached headers, tx history, address book, contacts
    │   └── tor/        Embedded Tor client + SOCKS5 dialer for remote mode
    ├── wallet/
    │   ├── key/        BIP39 mnemonic, Keystore-wrapped seed vault, biometrics
    │   ├── descriptor/ Descriptor construction, import, health checks
    │   └── psbt/       PSBT encode/decode, QR (BBQr/UR2), file, NFC
    ├── ui/
    │   ├── theme/      The Cyberspace design system (the visual language;
    │   │                the app itself is Bitcoin Core Node)
│   ├── components/ HUD primitives: NeonPanel, GlitchText, ScanlineOverlay,
    │   │               DataGrid, TerminalView, SignalMeter, HexDump
    │   ├── screens/    17 feature screens (see doc 05)
    │   └── nav/        Type-safe Navigation Compose graph
    ├── service/        NodeService, TorService, SyncWorker (WorkManager)
    └── di/             Hilt modules
```

---

## 5. State model

`NodeState` is a sealed hierarchy, and it is the single source of truth the whole UI reacts to:

```
Stopped
Starting(stage)          ← Verifying binary / Writing conf / Spawning / Handshake
Loading(progress, msg)   ← parsed from debug.log + getblockchaininfo
SnapshotLoading(pct)     ← loadtxoutset in flight
Syncing(headers, blocks, verificationProgress, peers, etaSeconds)
Synced(height, tipHash, peers)
Reindexing(progress)
Stopping
Crashed(exitCode, tail)  ← last 200 log lines retained for the report sheet
RemoteConnected(info)
RemoteUnreachable(cause)
```

Progress comes from two sources fused together: `getblockchaininfo.verificationprogress`
(authoritative but coarse and only available once RPC is up) and a `debug.log` tail parser
(available immediately at startup, before the HTTP server binds). The tailer is what makes
the first 20 seconds of app launch feel alive instead of frozen.

---

## 6. Performance rules

JSON-RPC over loopback is fine for a dashboard and terrible for a 50 000-transaction history.
The rules the codebase follows:

1. **Batch.** `RpcClient.batch()` sends a JSON array; the peers screen fetches
   `getpeerinfo` + `getnettotals` + `getnetworkinfo` in one round trip.
2. **Cache in Room, never re-fetch a confirmed thing.** Confirmed transactions and block
   headers are immutable; they are written once and read locally forever.
3. **Long-poll, don't spin.** `waitfornewblock` (hidden RPC, present in 30.3 at
   `rpc/blockchain.cpp:265`) blocks server-side until the tip moves. One thread, zero
   polling cost. Falls back to adaptive 2→30 s polling against remote nodes that reject it.
4. **Never call RPC on the main thread.** Enforced by a `StrictMode` penalty in debug builds
   and by every repository method being `suspend` on `Dispatchers.IO`.
5. **Paginate history.** `listtransactions` with a moving `skip` cursor, 100 at a time,
   fed into a Paging 3 `RemoteMediator`.

---

## 7. Security model (summary — full treatment in doc 07)

* **Seed material** never leaves `EncryptedSharedPreferences` backed by an Android Keystore
  key with `setUserAuthenticationRequired(true)` and StrongBox when the device has it.
* **The wallet passphrase** unlocks Core's own wallet encryption; it is held in memory only
  for the duration of a signing operation and zeroed after.
* **RPC cookie** lives in app-private storage, mode 0600, and is never logged.
* **No plaintext RPC over a network, ever.** Remote mode requires Tor or an explicit,
  scary-worded LAN exception the user must type to confirm.
* **No default snapshot host, no default remote node, no telemetry, no analytics SDK,
  no crash reporter that phones home.** The app makes zero network connections the user did
  not configure. This is stated in the Play Data Safety form and is true.
* **`android:allowBackup="false"`**, `FLAG_SECURE` on every screen that can display a seed,
  and screenshot suppression in the recents thumbnail.

---

## 8. Why not the alternatives

| Alternative | Why not |
|---|---|
| Neutrino/BIP157 light client | Doesn't use Bitcoin Core. Weaker privacy and validation. |
| `libbitcoinkernel` only | Would require reimplementing P2P, mempool, wallet and RPC in Kotlin — the exact code most likely to have consensus-relevant bugs. |
| Remote-only | Doesn't answer "a node in my pocket". |
| Qt GUI compiled for Android | `bitcoin-qt` on Android is technically buildable but the desktop UX is unusable on a handset and Qt adds ~40 MB. |

---

## 9. iOS forward-compatibility

Decisions taken now specifically so the iOS port is a UI rewrite and not an architecture
rewrite:

* All non-UI logic lives below `ui/` with no Android imports, so it can move to Kotlin
  Multiplatform with mechanical changes.
* The RPC surface is a pure-Kotlin interface with a pluggable HTTP engine (Ktor).
* **The hard part is known and documented:** iOS forbids `fork`/`exec` of a second process,
  so the embedded node must be linked as a static library into the app binary, with Core's
  `main()` renamed and run on a dedicated thread. iOS also caps background execution, so the
  embedded-node story there is materially weaker than Android's. See doc 12.
