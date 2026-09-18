# Building the native payload (Bitcoin Core → Android)

This produces `libbitcoind.so` and `libbitcoincli.so` for `arm64-v8a` and `x86_64`
and drops them into `app/src/main/jniLibs/`. You run this **once**; after that,
normal Gradle builds just package what's already there.

---

## Read this first

Bitcoin Core **used to** support Android in its `depends/` build system. It doesn't
anymore — `depends/hosts/` in 30.3 contains only `darwin`, `linux`, `mingw32`,
`freebsd`, `netbsd` and `openbsd`. There is no `android.mk`.

That means this build path is **ours, not upstream's**. It is not exercised by Core's
CI. Expect the first run on a new NDK to surface a compile error or two in code that
nobody has built for bionic in a few years. §6 covers the ones that are likely.

What makes it tractable is that Core 30.3 needs remarkably little:

| Dependency | Why | Cross-compile difficulty |
|---|---|---|
| Boost 1.88 | **Headers only** — `multi_index`, `signals2`. Core's own CMake says so. | Trivial |
| libevent 2.1.12 | The HTTP/RPC server | Easy, clean CMake |
| SQLite 3.46.1 | Descriptor wallet storage | Trivial, single .c file |
| secp256k1, LevelDB, crc32c, minisketch | | **Vendored in Core's tree.** Nothing to do. |

Three dependencies. That's the whole list.

---

## 1. Prerequisites

| Tool | Minimum | Notes |
|---|---|---|
| Android NDK | **r27** | r27 is the first NDK that gets 16 KB pages right. Play requires that. |
| CMake | 3.22 | The one bundled with the Android SDK is fine |
| Ninja | any | `brew install ninja` |
| Bash | 4+ | macOS ships 3.2 — `brew install bash` if a script complains |
| curl, tar, patch | — | preinstalled |
| Disk | ~12 GB | Boost source alone is ~2 GB unpacked |
| Time | 25–60 min | First run, both ABIs, on an M1 Pro |

Install the NDK through Android Studio: **SDK Manager → SDK Tools → NDK (Side by side)**.

---

## 2. Point the build at space-free paths

The enclosing folder here is named `bitccoin-core Project` — with a space. A multi-hour
C++ cross-build through Ninja is not reliably space-safe, so the scripts **refuse to
start** rather than fail deep into the build with something unreadable.

Nothing needs to move. Just redirect the intermediates:

```bash
cd "BitcoinCoreNode/native/scripts"

export BCN_BUILD_DIR="$HOME/bcn-build"
cp -R "../../../src_extract/bitcoin-30.3" "$HOME/bitcoin-30.3"
export BITCOIN_SRC="$HOME/bitcoin-30.3"
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.2.12479018"   # your version
```

The finished binaries still land in the project's `app/src/main/jniLibs/`.

---

## 3. Build

```bash
./build-all.sh
```

Or step by step, which is what you want when something breaks:

```bash
./00-fetch-deps.sh        # download + SHA256-verify boost, libevent, sqlite
./10-build-deps.sh        # cross-compile all three
./20-build-core.sh        # cross-compile bitcoind + bitcoin-cli
./30-package-jnilibs.sh   # strip, rename, verify alignment, install
```

Phone-only build (roughly half the time — skip if you want emulator support):

```bash
ABIS=arm64-v8a ./build-all.sh
```

Every step is **idempotent and resumable**. Re-running skips finished work, so after a
failure you fix the cause and re-run the same script.

---

## 4. Where the version numbers come from

`config.sh` pins exact versions and SHA256 hashes. **None of them are invented.** Each is
copied verbatim out of the Core source tree you already have:

```
depends/packages/libevent.mk  → 2.1.12-stable, 92e6de1b…
depends/packages/sqlite.mk    → 3460100,       67d3fe6d…
depends/packages/boost.mk     → 1.88.0,        dcea50f4…
```

These are the dependency versions upstream builds and tests its own release binaries
against. If you upgrade Bitcoin Core, re-copy them from the new tree — don't guess, and
don't let a hash mismatch slide. `fetch_verify()` deletes the download and aborts on
mismatch, and that behaviour is not negotiable.

The two patches in `native/patches/` are likewise upstream's own, unmodified:

* `libevent/cmake_fixups.patch` — raises `cmake_minimum_required` to 3.5 so CMake 4.x
  will configure libevent at all.
* `boost/skip_compiled_targets.patch` — adds `BOOST_TEST_HEADERS_ONLY`, so no Boost.Test
  `.cpp` file ever needs cross-compiling.

---

## 5. What gets built, and what doesn't

`20-build-core.sh` turns most of Core off. Each is a decision, not an oversight:

| Flag | Why |
|---|---|
| `BUILD_GUI=OFF` | Qt on a handset is unusable and costs ~40 MB. Our GUI is Compose. |
| `ENABLE_IPC=OFF` | Would need Cap'n Proto + libmultiprocess cross-built. The multiprocess node/GUI split has no purpose on mobile. |
| `ENABLE_EXTERNAL_SIGNER=OFF` | Uses `boost::process` to spawn HWI. Android forbids exec'ing arbitrary binaries from the data dir, so this could never work. Hardware wallets go through PSBT instead. |
| `WITH_ZMQ=OFF` | Avoids cross-building libzmq. Notifications use `waitfornewblock` + the log tailer. |
| `BUILD_TESTS/BENCH/FUZZ/TX/UTIL=OFF` | Triples build time, ships nothing. |
| `BUILD_BITCOIN_BIN=OFF` | Core 30's `bitcoin` multiplexer wrapper. We exec `bitcoind` directly. |
| **`ENABLE_WALLET=ON`** | The entire hot-wallet feature set is Core's descriptor wallet. This is what SQLite is for. |

**API level 28**, not 26. Core's `random.cpp` wants `getrandom()`/`getentropy()`, which
bionic only exposes from API 28. The alternative is patching Core's entropy source, which
is the single worst file in the tree to be patching. 28+ is the right trade.

---

## 6. Failures you should expect, and what they mean

**`Boost not found` / `Could NOT find Boost (missing: boost_headers)`**
Core does `find_package(Boost 1.74 REQUIRED CONFIG)` — CONFIG mode needs a real
`BoostConfig.cmake`, which is why `10-build-deps.sh` builds Boost through its CMake
rather than just unpacking headers. Check `$BCN_BUILD_DIR/<abi>/deps/lib/cmake/`
actually contains a `Boost-1.88.0` directory.

**`Could NOT find Libevent`**
Confirm `deps/lib/cmake/libevent/` exists. If it doesn't, libevent installed as a bare
static lib and Core's `FindLibevent.cmake` fell through to its pkg-config path, which
needs `PKG_CONFIG_PATH` pointing at `deps/lib/pkgconfig`.

**`undefined reference to getrandom` / `getentropy`**
`ANDROID_API_LEVEL` got set below 28. Don't patch Core; raise the API level.

**`error: use of undeclared identifier 'fdatasync'`**
Bionic hides some POSIX symbols behind feature macros. Add `-D_GNU_SOURCE` via
`APPEND_CPPFLAGS` in step 20.

**Anything mentioning `std::filesystem`**
NDK older than r23. Upgrade.

**Alignment check fails in step 30**
NDK older than r27, or the linker flags got dropped. Do not ship it — Play will reject
the release, and on a 16 KB-page device it simply won't load.

---

## 7. Verifying what you built

```bash
$ ls -la app/src/main/jniLibs/arm64-v8a/
libbitcoind.so      ~11 MB
libbitcoincli.so    ~1.5 MB

$ file app/src/main/jniLibs/arm64-v8a/libbitcoind.so
ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked, stripped
```

Note **`pie executable`**, not `shared object`. That is correct and intended — see the
long comment at the top of `30-package-jnilibs.sh` for why an executable is named `.so`.

Each build also writes `app/src/main/jniLibs/BUILD-INFO-<abi>.txt` with the NDK version,
dependency versions and SHA256 of both binaries, so any APK you ship is traceable back to
the exact toolchain that produced it. Keep those files with your release artifacts.

Smoke-test on an emulator or device:

```bash
adb shell run-as com.solitech.bitcoincorenode \
  /data/app/*/com.solitech.bitcoincorenode*/lib/arm64/libbitcoind.so -version
```

---

## 8. Reproducibility

This build is **not** bit-for-bit reproducible the way Core's Guix releases are. It pins
dependency versions and hashes, which gets you provenance and a rejection of tampered
downloads, but not determinism across machines and NDK installs. If you need reproducible
release artifacts — and for a wallet that ships to strangers, you eventually will — the
path is a Docker image pinning the exact NDK and CMake. That's tracked in doc 11 as a
release-blocker for 1.0, not for internal builds.
