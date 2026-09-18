# Building on Linux

The native build is a bash + CMake + autotools pipeline, so Linux is the
path of least resistance — less friction than macOS, where Apple's BSD
userland occasionally disagrees with what the scripts expect.

Two stages, same as everywhere: cross-compile Bitcoin Core once (slow), then
build the app (fast, repeatable).

---

## 1. Toolchain

### Packages

Debian / Ubuntu:

```bash
sudo apt update
sudo apt install -y \
    build-essential cmake ninja-build pkg-config \
    autoconf automake libtool patch \
    curl unzip zip tar git python3 \
    openjdk-17-jdk
```

Fedora / RHEL:

```bash
sudo dnf install -y \
    gcc gcc-c++ make cmake ninja-build pkgconf-pkg-config \
    autoconf automake libtool patch \
    curl unzip zip tar git python3 \
    java-17-openjdk-devel
```

Arch:

```bash
sudo pacman -S --needed base-devel cmake ninja pkgconf \
    autoconf automake libtool patch curl unzip zip tar git python jdk17-openjdk
```

Check the JDK, because AGP is strict about it:

```bash
java -version      # want 17.x
```

If you have several JDKs, point Gradle at 17 explicitly rather than fighting
`update-alternatives`:

```bash
echo "org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64" >> ~/.gradle/gradle.properties
```

### Android SDK and NDK

You do not need Android Studio; the command-line tools are enough.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip
mv cmdline-tools latest          # the tools insist on living in a dir named 'latest'
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

sdkmanager --licenses             # accept them all
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018"
```

**NDK r27 or newer is not optional.** r27 is the first release that handles
16 KB page alignment properly, and `native/scripts/30-package-jnilibs.sh`
refuses to package a binary that fails the alignment check. An older NDK
produces a `libbitcoind.so` that will not load at all on a 16 KB device.

Make it permanent:

```bash
cat >> ~/.bashrc <<'EOF'
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
EOF
```

`local.properties` in the project root should point at the same SDK:

```properties
sdk.dir=/home/YOUR_USER/Android/Sdk
```

---

## 2. The native payload (once, 30–60 min)

```bash
export BITCOIN_SRC="$HOME/bitcoin-30.3"        # extracted Core source tree
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"

cd native/scripts
./build-all.sh
```

Phone only, roughly half the time:

```bash
ABIS=arm64-v8a ./build-all.sh
```

### No spaces in any build path

The scripts refuse to start if a build path contains a space. That check is
deliberate — Ninja does not survive them, and failing at second 1 beats failing
at minute 45 with an unreadable error. If your checkout lives somewhere with a
space in the name, redirect the intermediates instead of moving anything:

```bash
export BCN_BUILD_DIR="$HOME/bcn-build"
```

### Expected at the end

```
app/src/main/jniLibs/arm64-v8a/libbitcoind.so     ~13 MB
app/src/main/jniLibs/arm64-v8a/libbitcoincli.so   ~1.7 MB
16 KB alignment OK: libbitcoind.so
```

`file` should report **`pie executable`**, not `shared object`. That is correct
and intended — see the comment at the top of `30-package-jnilibs.sh` for why an
executable is named `.so`.

Every step is idempotent and resumable. If one fails, fix the cause and re-run
the same script; finished work is skipped.

### Memory

Cross-compiling Core is the memory-hungry part. On a machine with less than
about 8 GB, a single `clang` translation unit can push past what is available
and the kernel kills it — usually with no error message worth reading. Cap the
parallelism:

```bash
JOBS=2 ./build-all.sh
```

---

## 3. The app

```bash
./gradlew assembleDebug        # or bundleRelease, with signing configured
```

Gradle's own memory settings live in `gradle.properties`, and the note at the
bottom of that file covers what to change on a constrained machine. Kotlin forks
its own compile daemon; that JVM plus Gradle's plus KSP does not fit in ~4 GB,
and when it does not fit, the build stops with no error at all.

A release build refuses to start if `jniLibs` is empty, so it is not possible to
ship an APK with no node in it by accident.

---

## 4. Running it

On a physical device over USB:

```bash
adb devices                    # authorise the prompt on the phone
./gradlew installDebug
```

If `adb devices` shows nothing, it is almost always udev rather than the cable:

```bash
sudo usermod -aG plugdev "$USER"     # log out and back in
sudo apt install -y android-sdk-platform-tools-common
```

An `.aab` cannot be installed directly. Use bundletool:

```bash
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
    --output=app.apks --connected-device
bundletool install-apks --apks=app.apks
```

Test on a real arm64 device, not the emulator — the emulator runs the x86_64
payload through translation and is not what your users will run.

---

## 5. Verifying a release

```bash
# 16 KB alignment on every PT_LOAD segment
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
    -l app/src/main/jniLibs/arm64-v8a/libbitcoind.so | grep LOAD

# Bundle signature
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

The full release runbook is in `06-PLAY-RELEASE.md`.
