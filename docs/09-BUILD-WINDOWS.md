# Building on Windows

Read this first, because it saves a wasted afternoon:

**The app builds natively on Windows. The Bitcoin Core payload does not.**

Those are two separate stages and only the second one is a problem. If someone
hands you a prebuilt `jniLibs/`, you never need WSL at all — `gradlew.bat` works
fine on plain Windows and you can stop after section 1.

---

## Why the native payload needs WSL

Not stubbornness, and not a bug to be worked around:

- Every script under `native/scripts/` is bash, using POSIX paths, symlinks and
  process substitution throughout.
- `config.sh`'s `host_tag()` maps a host OS to the NDK's prebuilt toolchain
  directory. The NDK does ship a `windows-x86_64` toolchain, but nothing else in
  the pipeline follows: libevent and sqlite are built with GNU autotools, and
  Bitcoin Core's own depends system does not support a Windows host either.
- Ninja and CMake fall over on paths containing spaces, which `C:\Users\Your
  Name\` very often has.

On Windows the scripts now stop immediately with a pointer here, rather than
failing later as a missing-compiler mystery.

WSL2 is not a compromise. It is a real Linux kernel, the build runs at native
speed, and it is the same environment as `docs/08-BUILD-LINUX.md`.

---

## 1. App only, native Windows

If `app/src/main/jniLibs/arm64-v8a/libbitcoind.so` already exists:

```powershell
# JDK 17 and the Android SDK, via Android Studio or winget
winget install EclipseAdoptium.Temurin.17.JDK
```

`local.properties` in the project root, with **forward slashes or escaped
backslashes** — a bare `C:\Users\...` is read as escape sequences and fails:

```properties
sdk.dir=C:/Users/YOUR_USER/AppData/Local/Android/Sdk
```

Then:

```powershell
.\gradlew.bat assembleDebug
```

Two Windows-specific notes:

- **Long paths.** Kotlin, KSP and R8 generate deeply nested output and can pass
  260 characters. Keep the checkout near the root — `C:\dev\BitcoinMobile`,
  not `C:\Users\Name\Documents\Projects\...` — and enable long paths:
  ```powershell
  # elevated PowerShell
  New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" `
      -Name LongPathsEnabled -Value 1 -PropertyType DWORD -Force
  git config --system core.longpaths true
  ```
- **Defender.** Real-time scanning of Gradle's caches costs a large fraction of
  every build. Exclude `%USERPROFILE%\.gradle` and your checkout.

---

## 2. WSL2, for the native payload

### Install

```powershell
wsl --install -d Ubuntu-24.04      # reboot when asked
```

Confirm it is version 2 — version 1 has filesystem semantics that will bite you:

```powershell
wsl -l -v      # VERSION must read 2
```

### Where the checkout lives — this one matters

Work inside the WSL filesystem (`~/`), **not** `/mnt/c/`. The Windows drive is
exposed to Linux over a 9P network protocol, and a build that does hundreds of
thousands of small file operations runs roughly an order of magnitude slower
there. It is the single biggest performance mistake in a WSL setup.

```bash
cd ~                                   # NOT /mnt/c/Users/...
git clone <your-repo-url> BitcoinMobile
cd BitcoinMobile
```

You can still edit the files from Windows — `\\wsl$\Ubuntu-24.04\home\you\...`
in Explorer, or VS Code with the WSL extension, which is the sane way to do it.

### Memory

WSL2 defaults to a fraction of system RAM, and cross-compiling Core wants more
than the default usually allows. Create `C:\Users\YOUR_USER\.wslconfig`:

```ini
[wsl2]
memory=8GB
processors=4
swap=8GB
```

Then `wsl --shutdown` and reopen. Without this, a `clang` process gets killed
mid-translation-unit and the build stops with nothing useful printed.

### Then follow the Linux guide

Everything from here is `docs/08-BUILD-LINUX.md`, unchanged: the apt packages,
the SDK/NDK install, `./build-all.sh`. NDK r27+ is required for 16 KB page
alignment.

---

## 3. Getting the payload back to a Windows build

Only needed if you want to build the app with `gradlew.bat` rather than inside
WSL. Copy the four files across:

```bash
# from WSL
cp -r ~/BitcoinMobile/app/src/main/jniLibs \
      /mnt/c/dev/BitcoinMobile/app/src/main/
```

Copy `BUILD-INFO-arm64-v8a.txt` with them — it records the NDK version, the Core
source and the SHA-256 of both binaries, and a payload without its provenance is
not traceable later.

Simpler, and what I would do: run the Gradle build in WSL too. One environment,
one JDK, no copying.

---

## 4. Devices from WSL

`adb` inside WSL2 cannot see USB devices directly — WSL2 is a VM and the USB bus
is not passed through by default. Two options:

**Run adb on Windows, connect over the network.** Simplest.

```powershell
adb devices                            # on Windows, with the phone plugged in
adb tcpip 5555
adb shell ip addr show wlan0           # note the phone's IP
```

```bash
# in WSL
adb connect 192.168.1.42:5555
./gradlew installDebug
```

**Or use `usbipd-win`** to pass the device through properly:

```powershell
winget install usbipd
usbipd list
usbipd bind --busid <BUSID>
usbipd attach --wsl --busid <BUSID>
```

---

## 5. Things that go wrong on Windows specifically

| Symptom | Cause |
|---|---|
| `sdk.dir` not found, path looks mangled | Backslashes in `local.properties` read as escapes. Use `/`. |
| Build fails with a path over 260 chars | Long paths not enabled, or checkout nested too deep |
| `bash: ./gradlew: Permission denied` in WSL | Clone lost the exec bit: `chmod +x gradlew` |
| `$'\r': command not found` in WSL | CRLF line endings. `git config --global core.autocrlf input`, re-clone |
| Native build crawls | Checkout is on `/mnt/c/`. Move it into `~/`. |
| `clang` killed, no error | WSL memory cap. Set `.wslconfig`, or `JOBS=2 ./build-all.sh` |
