# Porting to iOS

Read this before writing any Swift. One constraint in §2 shapes the entire
design, and it is better known now than after the UI is built.

---

## 1. What ports cleanly

The architecture was chosen with this in mind. Everything below `ui/` has no
Android imports:

| Layer | Portability |
|---|---|
| `core/rpc` | Pure Kotlin + OkHttp. Swap the HTTP engine, or move to Kotlin Multiplatform with Ktor. |
| `core/model` | Pure Kotlin + kotlinx.serialization. Moves as-is. |
| `core/node` config building | Pure string generation. Moves as-is. |
| `data/repo` | Suspend functions over `RpcClient`. Moves as-is. |
| `ui/` | Rewrite in SwiftUI. |

Two viable routes:

**A. Kotlin Multiplatform.** Move `core/` and `data/` into a shared KMP module,
consume it from SwiftUI. Business logic stays in one place, which matters most
for the send flow — the state machine that decides what gets signed is the last
code you want forked across two platforms.

**B. Rewrite in Swift.** Simpler tooling, no KMP build complexity, but you now
maintain two implementations of the same RPC surface and they will drift. The
drift will be discovered by a user.

Recommendation: **A**, for the send flow alone.

---

## 2. The constraint that changes everything

**iOS does not allow an app to fork and exec a second process.**

The entire Android design — `bitcoind` as a supervised child, talking JSON-RPC
over loopback, surviving its own crashes — is unavailable. There is no
equivalent, no entitlement, and no review-process exception.

### What that forces

Bitcoin Core must be **statically linked into the app binary** and run on a
thread:

1. Cross-compile Core for `arm64-apple-ios` (add an iOS toolchain alongside
   `native/scripts/`; the dependency set is the same three libraries).
2. Rename Core's `main()` — `-Dmain=bitcoind_main` at compile time, or a small
   patch — and expose it as a C entry point.
3. Start it on a dedicated pthread with a **large stack**. Core's validation
   code recurses; the default secondary-thread stack is not enough.
4. Talk to it over loopback exactly as on Android. That part is unchanged, which
   is the payoff for having made everything JSON-RPC in the first place.

### What that costs

* **A node crash takes the whole app down.** No supervisor, no restart, no
  crash-cause panel. The `NodeState.Crashed` machinery has no iOS analogue.
* **You cannot stop and restart the node without restarting the app.** Core's
  globals are not re-initialisable in-process.
* **Static linking makes the app binary very large.** Budget accordingly.

---

## 3. The other constraint

**iOS background execution is capped in a way Android's is not.**

Android's foreground service is an explicit contract: this work must finish, and
the OS honours it for hours. iOS has no equivalent. `BGProcessingTask` runs when
the system decides — typically when charging, on Wi-Fi, and for a bounded
window. There is no way to say "keep syncing for six hours".

The honest consequence: **an embedded node on iOS syncs only while the app is
open, plus opportunistic background windows.** Initial sync becomes something
the user does with the app in the foreground and the phone plugged in.

Do not paper over this. The iOS node screen should say what it is doing and why
it stops, in the same plain terms the assumeutxo panel uses on Android.

This makes **remote mode materially more important on iOS**. It may be worth
shipping remote-only first, with the embedded node as a second release — it is
the mode that works well within the platform's constraints, rather than the one
that fights them.

---

## 4. Platform equivalents

| Android | iOS |
|---|---|
| Android Keystore + StrongBox | Keychain with `kSecAttrTokenIDSecureEnclave` |
| `BiometricPrompt` | `LocalAuthentication` / Face ID |
| `FLAG_SECURE` | No true equivalent. Overlay a blur on `willResignActive`; screenshots cannot be blocked. **Weaker. Say so.** |
| Foreground service | `BGProcessingTask` — see §3 |
| `allowBackup=false` | `isExcludedFromBackup` on the datadir URL. Set it, or wallet.dat goes to iCloud. |
| SAF | `UIDocumentPicker` |
| `libbitcoind.so` exec | Static link, see §2 |

---

## 5. App Store review

Different from Play, and stricter in places:

* Non-custodial wallets are permitted; custodial and exchange functionality
  needs to be from a licensed entity in most regions.
* Apple has historically been unpredictable about node software specifically.
  Lead the review notes with what the app *is*: a Bitcoin Core node and
  self-custody wallet, no trading, no purchase, no in-app currency.
* Guideline 3.1.1 (in-app purchase) does not apply — no digital goods are sold.
* Expect questions about background execution. Have the answer from §3 ready
  and stated plainly.

---

## 6. Suggested order

1. Cross-compile Core for iOS, prove `bitcoind_main` runs on a thread and
   answers RPC. **Do this first.** It is the only genuinely unknown piece, and
   everything else is wasted effort if it doesn't work.
2. Extract `core/` and `data/` into a KMP module; verify Android still builds
   from it unchanged.
3. SwiftUI shell in remote-only mode against a node you already run.
4. Port the design system. The chamfer is a `Path`; the address grouping is an
   `AttributedString`; the segmented meter is an `HStack`. All of it is
   straightforward.
5. Wire in the embedded node, with the background-execution limits surfaced
   honestly in the UI.
6. Submit remote-only first if review feedback is slow — it is the smaller
   surface and the stronger fit for the platform.
