# Publishing to Google Play

Everything Play will ask for, and the answers this app needs. Read §1 before
you write any of it — one of those points can stop a release dead.

> **Policy dates and thresholds change.** Everything below reflects Play's
> requirements as understood in 2026. Check the current policy pages before a
> release; where a number matters, it is flagged so you know what to re-verify.

---

## 1. Four things that will bite you

**1. A crypto wallet is allowed. Be precise about which kind.**
Play permits non-custodial wallets. It restricts *custodial* services and
exchanges, which in many regions require licensing and a Financial Services
declaration backed by real credentials. Bitcoin Core Node is **non-custodial** —
keys never leave the device, there is no service, no counterparty, no order
book. Say exactly that in the declaration. Vagueness here gets a reviewer
treating it as an exchange.

**2. Your foreground service needs a justification a human will read.**
`dataSync` is declared, and Play requires you to explain why. The honest answer
is short and good: the app synchronises the Bitcoin blockchain, a process that
takes hours, must survive the screen turning off, and cannot be interrupted
mid-write without risking data corruption. Include a screen recording of the
notification. Vague answers get rejected.

**3. 16 KB page alignment is not optional.**
Play requires 64-bit apps with native code to support 16 KB memory pages.
`native/scripts/30-package-jnilibs.sh` verifies this and **refuses to package a
build that fails**. Do not bypass that check — a misaligned binary will not load
at all on a 16 KB device.

**4. A new personal developer account has a testing requirement.**
Accounts created since late 2023 must run a closed test with a minimum number of
testers (12 at time of writing) continuously for 14 days before production
access opens. Budget three weeks, not three days, and re-check the current
threshold before you plan around it.

---

## 2. Signing

```bash
keytool -genkey -v -keystore bitcoin-core-node-release.jks \
  -alias bitcoincorenode -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12

cp keystore.properties.example keystore.properties   # then fill it in
```

Enrol in **Play App Signing**. Google holds the deployment key; the key above
becomes your upload key, and an upload key can be reset through support if it is
lost. Without App Signing, losing that file means you can never update this app
again under this package name — there is no recovery path, and every existing
install is orphaned.

Back the `.jks` up offline, in two places. It is gitignored for a reason.

---

## 3. Build the bundle

```bash
cd native/scripts && ./build-all.sh          # once — the node itself
cd ../.. && ./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

`bundleRelease` fails fast if `jniLibs` is empty, so it is not possible to ship
an APK with no node in it by accident.

Keep `app/build/outputs/native-debug-symbols.zip` and upload it with the
bundle — without it, native crash reports come back as bare addresses.

---

## 4. Data Safety

Every answer here is **"no data collected"**, and unusually, that is literally
true. There is no analytics SDK, no crash reporter, no update check, no default
remote node and no default snapshot host. The app opens no socket the user did
not configure.

| Question | Answer |
|---|---|
| Does your app collect or share user data? | **No** |
| Is data encrypted in transit? | N/A — none is transmitted to us. Remote RPC is Tor or user-configured. |
| Can users request deletion? | N/A — nothing is held off device |
| Financial info collected | **None** |
| Location, contacts, identifiers, messages | **None** |

Reviewers are sceptical of a blanket "no", so make it easy to confirm: point at
`res/xml/network_security_config.xml` and the absence of any analytics
dependency in `gradle/libs.versions.toml`.

**On the block-explorer fallback.** It is off by default, ships no default
host, and only ever contacts a server the user typed in themselves. On that
basis "no data collected" remains accurate: the app transmits nothing to *you*,
and the third-party connection is one the user configured and can turn off.
Mention it in the review notes anyway rather than letting a reviewer discover an
outbound host you did not declare.

**If you ever add a crash reporter, or ship a default explorer host, this
section becomes false.** Update it in the same commit, not later.

---

## 5. Permissions

Have a one-line justification ready for each:

| Permission | Justification |
|---|---|
| `INTERNET` | Bitcoin P2P and JSON-RPC |
| `ACCESS_NETWORK_STATE` | Pause sync on metered connections |
| `FOREGROUND_SERVICE` + `_DATA_SYNC` | Blockchain sync must survive the screen turning off; interrupting it mid-write risks database corruption |
| `POST_NOTIFICATIONS` | The mandatory foreground-service notification |
| `WAKE_LOCK` | Optional, off by default, user-toggled, released at the tip |
| `CAMERA` | Scanning addresses and PSBTs. `required="false"` — the app is fully usable without it |
| `USE_BIOMETRIC` | Authenticate before signing |

Worth saying out loud in the review notes: the app requests **no** storage
permission (Storage Access Framework instead), no location, no contacts, no
phone state, and no advertising ID.

---

## 6. Store listing

`fastlane/metadata/android/en-US/` holds the copy. Two rules for the
description:

* **Do not promise price, yield, or returns.** It is a wallet, not an
  investment product, and financial-promise language attracts exactly the
  scrutiny you do not want.
* **Do not imply custody or insurance.** Say plainly that the user holds their
  own keys and that loss of the seed means loss of funds.

Assets needed: 512×512 icon, 1024×500 feature graphic, 2–8 phone screenshots.
The dark HUD screens photograph well; lead with the dashboard and the node
screen showing dual-chainstate progress — it is the most distinctive thing the
app does.

---

## 7. Content rating

Answer the IARC questionnaire honestly. No gambling, no user-generated content,
no social features, no ads. It will land at Everyone / PEGI 3, with the
"references to cryptocurrency" flag.

---

## 8. Release runbook

Work through this in order. Do not skip step 2 — it is the one that catches a
Core upgrade having silently changed a field name.

```
[ ] 1.  git status clean; version bumped in app/build.gradle.kts
[ ] 2.  python3 native/scripts/verify-rpc-models.py --core "$BITCOIN_SRC" --strict
[ ] 3.  python3 native/scripts/check-kotlin-imports.py app/src/main/java
[ ] 4.  ./gradlew testDebugUnitTest lintRelease
[ ] 5.  cd native/scripts && ./build-all.sh          (alignment check must pass)
[ ] 6.  Keep app/src/main/jniLibs/BUILD-INFO-*.txt with the release artifacts
[ ] 7.  ./gradlew bundleRelease
[ ] 8.  Install the AAB on a real arm64 device via bundletool; do not trust the emulator
[ ] 9.  Smoke test on SIGNET, in this order:
        [ ] node starts, reaches the tip
        [ ] create wallet, set a passphrase
        [ ] receive: QR scans from another phone
        [ ] send: build → review → sign → broadcast
        [ ] stop the node cleanly; restart; no reindex prompt
[ ] 10. Repeat 9 on MAINNET with a trivial amount you can afford to lose
[ ] 11. Upload AAB + native-debug-symbols.zip to a closed track
[ ] 12. Closed test for the required period (14 days at time of writing)
[ ] 13. Promote to production; staged rollout at 10%
[ ] 14. Watch ANRs and native crashes for 48 h before widening
```

Step 10 is not optional. Signet and mainnet differ in address prefixes, fee
levels, and mempool behaviour, and a bug that only shows on mainnet is a bug
that only shows with real money.

---

## 9. Things that will get this app rejected

- Downloading executable code at runtime. **Never** fetch a `bitcoind` binary
  from the network. It ships in the APK. This is a hard policy line and it is
  also the right engineering choice.
- Requesting `MANAGE_EXTERNAL_STORAGE` for wallet or PSBT files. Use SAF.
- A foreground service whose notification the user cannot dismiss by stopping
  the work. Ours has a Stop action.
- Implying the app is affiliated with, or endorsed by, Bitcoin Core or the
  Bitcoin project. It uses Core under the MIT licence. Say that, and nothing
  stronger.
- Shipping a debug build, or a release with `ALLOW_INSECURE_RPC` true.
