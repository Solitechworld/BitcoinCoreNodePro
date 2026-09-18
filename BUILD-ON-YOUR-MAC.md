# Build this on your Mac — exact commands

I could not run the build from this session. Not a permissions problem on your
side: the two shells available to me (a cloud container, and an isolated Linux
VM on your machine) have no Android SDK and are firewalled from `dl.google.com`,
Maven Central and `services.gradle.org`; and macOS app access for terminals and
IDEs is granted in **click-only** mode, so I can see VS Code but cannot type a
command into it.

So here is the whole sequence, in order, with what each step should print.

---

## 0. Prerequisites (once)

```bash
java -version          # want 17.x; if missing:  brew install --cask temurin@17

# Android SDK + NDK. Easiest path is Android Studio's SDK Manager:
#   SDK Tools -> "NDK (Side by side)" -> r27 or newer   (16 KB pages need r27+)
#   SDK Tools -> "Android SDK Command-line Tools"
export ANDROID_HOME="$HOME/Library/Android/sdk"
ls "$ANDROID_HOME/ndk"           # should list e.g. 27.2.12479018
```

VS Code alone is not enough — the SDK and NDK are what actually build this.
VS Code is fine as the editor; the build runs in its terminal.

---

## 1. Native payload — Bitcoin Core -> arm64 (~30-60 min, once)

The scripts refuse to start if any build path contains a space, and your project
folder is named `bitccoin-core Project`. That check is deliberate: Ninja does not
survive spaces, and failing at minute 45 with an unreadable error is worse than
failing at second 1. Redirect the intermediates — nothing moves:

```bash
cd "$HOME/bitccoin-core Project/BitcoinCoreNode/native/scripts"

export BCN_BUILD_DIR="$HOME/bcn-build"
cp -R "$HOME/bitccoin-core Project/src_extract/bitcoin-30.3" "$HOME/bitcoin-30.3"
export BITCOIN_SRC="$HOME/bitcoin-30.3"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"   # your version

./build-all.sh
```

Faster, phone only (skips the emulator ABI):

```bash
ABIS=arm64-v8a ./build-all.sh
```

**Expected at the end:**

```
app/src/main/jniLibs/arm64-v8a/libbitcoind.so     ~11 MB
app/src/main/jniLibs/arm64-v8a/libbitcoincli.so   ~1.5 MB
16 KB alignment OK: libbitcoind.so
```

`file` should say **`pie executable`**, not `shared object`. That is correct —
see the comment at the top of `30-package-jnilibs.sh` for why an executable is
named `.so`.

Every step is resumable. If one fails, fix the cause and re-run the same script;
finished work is skipped. Section 6 of `docs/02-BUILD-NATIVE.md` lists the
failures most likely on a first run and what each one means.

---

## 2. The app

```bash
cd "$HOME/bitccoin-core Project/BitcoinCoreNode"
./gradlew assembleDebug
```

**This has never been compiled.** It was written without an Android SDK
reachable, so expect the first run to surface import and signature fixes. That
is the expected state, not a surprise — the architecture and the Core
integration were the parts worth getting right before a compiler was available.

Send me the first ~50 lines of any error output and I will work through it.

---

## 3. Verify the Blockbook models against your real API

Do this before trusting the explorer fallback. The Bitcoin Core models were
transcribed from Core's own source and are machine-checked; the Blockbook models
were written from the documented v2 schema because I had no network route to the
host, so they are the one part of this codebase not verified against reality.

```bash
cd "$HOME/bitccoin-core Project/BitcoinCoreNode"
python3 native/scripts/verify-blockbook-schema.py \
  --url https://bitcoin.atomicwallet.io/api/v2 \
  --address 1Be6LLAEndprdWKiH6YM62setFQRXJzfha
```

Stdlib only — no pip install.

**Read the output like this:**

* `OK` — the model matches what that server sends.
* `not modelled` — the server sends extra fields we ignore. Usually fine.
* `MISSING` — **act on this.** We expect a field the server does not send, so
  the Kotlin default fills in silently: a balance reads zero, a list comes back
  empty, and nothing raises an error anywhere. Paste the output to me and I will
  correct `BlockbookModels.kt`.
* `TYPE` — an amount arrived as a JSON number instead of a satoshi string. The
  serializer copes, but the model comments would then be wrong.

---

## 4. The other checks

```bash
python3 native/scripts/verify-rpc-models.py --core "$BITCOIN_SRC"   # currently 11/11 clean
python3 native/scripts/check-kotlin-imports.py app/src/main/java
./gradlew testDebugUnitTest        # includes the donation-address checksum test
```

---

## If you would rather I drove it

Two things would let me run the build myself:

1. **Allowlist the toolchain hosts** for this session's shells —
   `dl.google.com`, `services.gradle.org`, `repo1.maven.org`,
   `dl.google.com/dl/android/maven2`. Then I can install the SDK in the cloud
   container and build there directly, which is far more reliable than driving a
   GUI.
2. Or just paste me the build output. Honestly the fastest loop: you run one
   command, I read the errors and fix the source.
