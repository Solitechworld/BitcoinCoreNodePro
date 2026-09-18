# Security model

What this app protects, how, and — more importantly — what it does not protect
against. A wallet that overstates its guarantees is more dangerous than one that
makes none, because people calibrate their behaviour to what they are told.

---

## 1. What an attacker would be after

| Asset | Where it lives | If it leaks |
|---|---|---|
| Wallet private keys | Core's `wallet.dat`, in app-private storage, encrypted by Core when a passphrase is set | Total, irreversible loss of funds |
| BIP39 seed / descriptors | Android Keystore-wrapped, in app-private storage | Same, plus every future address |
| Wallet passphrase | RAM only, for the duration of one signing operation | Unlocks the above |
| RPC cookie | `<datadir>/.cookie`, mode 0600 | Full control of the local node |
| Remote RPC credentials | Encrypted at rest, sent only over Tor | Full control of a node elsewhere |
| Address set / xpubs | Wallet DB, and the node's queries | Permanent, public deanonymisation of past and future payments |
| The user's IP ↔ transaction link | The P2P connection | Links their identity to their coins |

Note the last two. Losing privacy is not recoverable in the way losing a
password is: the chain is permanent and public, and a linkage made today is
still valid in twenty years.

---

## 2. What is defended, and how

### Keys at rest

* Core encrypts `wallet.dat` when a passphrase is set. That encryption is
  Core's, reviewed for a decade, and this app does not reimplement or wrap it.
* Any seed material the app stores itself is sealed with an **Android Keystore**
  AES-GCM key created with `setUserAuthenticationRequired(true)` and StrongBox
  where the device provides it. The key material never enters the app's address
  space; on a device with a secure element it never leaves that element.
* The wallet is unlocked for **60 seconds**, enough to sign one transaction, and
  re-locked immediately after a broadcast. There is no "keep unlocked" option.

### Keys in use

* The passphrase is held in a local variable for the duration of one signing
  call and is not retained, logged, or copied into state that outlives it.
* Signing happens inside Bitcoin Core. No sighash computation, nonce generation
  or key derivation exists in this codebase. This is the most important single
  line in this document: the code most likely to lose someone's money through a
  subtle bug is code we did not write.

### Data in transit

* Embedded node: loopback only, `rpcbind=127.0.0.1`, cookie auth. Never leaves
  the device.
* Remote node: **Tor by default**. Plaintext LAN is possible but the user must
  type a confirmation phrase to create such an endpoint, and the Security screen
  lists every one as a standing risk for as long as it exists.
* `network_security_config.xml` denies cleartext to everything except loopback.

### On screen

* `FLAG_SECURE` on the window: no screenshots, no screen recording, and — the
  reason it is window-wide rather than per-screen — no thumbnail in the recents
  switcher. The recents snapshot is taken when the app backgrounds, which can
  happen while a seed is visible, and there is no reliable hook to strip the
  flag first.
* Addresses are rendered grouped in fours with the ends emphasised, because
  address-substitution malware relies on people checking only the first and last
  few characters.
* Copied addresses are marked `IS_SENSITIVE` so Android 13+ omits them from the
  clipboard preview toast.

### At rest, off device

* `android:allowBackup="false"`, and every domain excluded from both cloud
  backup and device transfer. Wallet data must not travel through Google's
  backup infrastructure or an adb transfer channel. Users move between devices
  by restoring from their seed — the mechanism designed for it.

### Network behaviour

* **No analytics, no crash reporting, no update check, no default remote node,
  no default snapshot host, no default block explorer.** The app opens no socket
  the user did not configure. This is stated in the Play Data Safety declaration and it is true;
  if you audit the app and find otherwise, that is a serious bug.
* `-listen=0`: no inbound connections. A phone behind CGNAT helps no one and
  accepting inbound is battery spent for nothing.
* `peerbloomfilters=0`: BIP37 filters are a privacy liability for whoever asks
  and pure cost for us.

---

## 3. What is NOT defended against

This section matters more than the one above.

### A compromised device

If the phone is rooted by an attacker, or runs malware with root, **everything
here fails**. Keystore raises the cost and StrongBox raises it further, but a
root-level attacker can screenshot the unlocked app, read the passphrase from
input, or wait for the 60-second unlock window and sign their own transaction.
Nothing an app can do defeats an attacker who owns the OS.

There is no root detection, deliberately. Root detection is trivially bypassed,
punishes legitimate power users, and its main effect is to give everyone else
false confidence.

### A compromised Bitcoin Core build

The app runs whatever `libbitcoind.so` was packaged into the APK. Whoever builds
the APK controls what that binary is. The build is **not** currently
reproducible (see `docs/02-BUILD-NATIVE.md` §8), which means you cannot yet
independently verify that a shipped APK contains an honest Core build. That is
tracked as a release-blocker for a public 1.0 and it is a real gap, not a
formality.

### Physical access to an unlocked phone

If someone holds your unlocked phone and the wallet has no passphrase, they can
spend. This is why an unencrypted wallet is shown in **red**, not neutral grey —
it is not a configuration preference, it is money with no lock on it.

### Traffic analysis, without Tor

Running the embedded node over clearnet tells every peer your IP, and tells
your ISP that you run a Bitcoin node. Broadcasting a transaction from your own
node over clearnet is a strong signal that the transaction is yours. Tor
mitigates this; nothing else in the app does.

### The assumeutxo trust window

Loading a UTXO snapshot means trusting the commitment compiled into Bitcoin
Core until background validation finishes — hours to days. It is a **weaker
security assumption than a full sync**, and it is not hidden: the Node screen
states it in those words, and the dual-progress display exists precisely so the
window is visible rather than glossed over.

Note what is *not* a weakness here: a corrupt or malicious snapshot file. Core
recomputes the UTXO set hash while loading and rejects anything that does not
match its own commitment. The download can waste your bandwidth. It cannot
poison your chainstate.

### The block-explorer fallback

If the user enables the Blockbook fallback, **every lookup hands one of their
addresses to a third-party server, from their IP**. That operator learns which
addresses belong to one person and can link them to a device. Because the ledger
is public and permanent, a linkage made today is still valid in twenty years.
This is the single largest privacy regression available in the app.

It is therefore off by default, has no default host, is disclosed in the
Settings copy in those words, and can be routed over Tor (which hides who is
asking, but not what is being asked).

An xpub lookup is categorically worse than an address lookup: one request hands
the server the user's entire wallet, past and future. It has its own consent
switch rather than riding on the first one.

Two further limits, both stated in the UI:

* **An explorer asserts; it does not validate.** A Blockbook can under-report a
  balance, omit a transaction, or claim a confirmation that never happened.
  Nothing checks its answers against consensus rules -- that is what the node is
  for. Explorer-sourced figures are advisory and must never be the basis for
  believing a payment arrived.
* **An explorer that has fallen behind returns stale data with HTTP 200 and no
  warning.** `ExplorerRepository.checkStatus()` treats `inSync == false` as a
  hard error for exactly this reason: a confidently wrong balance is worse than
  a visible failure.

The explorer can never sign. Building and signing always uses Core and the
user's keys; the explorer only reads, and broadcasts what is already signed.

### Someone else's remote node

In remote mode you are trusting that node's operator for everything: balances,
history, fee estimates, and whether your transaction was really broadcast. It is
the right mode when the node is *yours*. Pointing it at a stranger's node is a
custody-adjacent decision, and the app says so before you save such an endpoint.

### Supply chain

Gradle pulls signed artifacts from Maven Central and Google's repository.
`FAIL_ON_PROJECT_REPOS` means there is exactly one answer to "where did this
dependency come from", and the version catalog pins every version. That reduces
the surface; it does not eliminate it.

---

## 4. Deliberate omissions

| Not implemented | Why |
|---|---|
| Root detection | Bypassable, punishes power users, manufactures false confidence |
| Custom PIN over the OS lock screen | Weaker than the platform's, and users reuse the same digits |
| "Remember passphrase" | The 60-second window exists for a reason |
| Cloud backup of any kind | See §2 |
| Screenshot allowance for "just the balance" | Partial `FLAG_SECURE` does not exist; the recents thumbnail leaks regardless |
| Custom crypto anywhere | Every primitive comes from Core or the platform |

---

## 5. Reporting something

If you find a vulnerability, do not open a public issue. Contact the maintainer
directly, allow reasonable time for a fix, and please include the version and
the `BUILD-INFO-<abi>.txt` from the build you tested — it identifies the exact
NDK and dependency versions the native payload was produced with.
