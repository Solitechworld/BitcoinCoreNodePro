# Privacy Policy — Bitcoin Core Node

**Last updated:** 2026-09-06

## Summary

Bitcoin Core Node does not collect, transmit, store, or share any personal data.

There is no account. There is no server operated by us. The app contains no
analytics, no advertising identifiers, no crash reporting service, and no
update check.

## What data the app handles, and where it stays

All of the following is created on your device and stays there:

| Data | Where it lives |
|---|---|
| Wallet private keys and seed | App-private storage, encrypted by Bitcoin Core with your passphrase and protected by the Android Keystore |
| Bitcoin addresses, transaction history, labels | App-private storage |
| Blockchain data (blocks, chainstate, indexes) | App-private storage |
| Settings and preferences | App-private storage |

None of it is uploaded anywhere. It is excluded from Android cloud backup and
from device-to-device transfer, deliberately, so it cannot travel through
infrastructure we do not control.

## Network connections the app makes

Only ones you configure:

1. **The Bitcoin peer-to-peer network** — if you run the node on this device.
   Your node connects to other Bitcoin nodes to download blocks and broadcast
   transactions. This is how Bitcoin works. Those peers see your IP address
   unless you enable Tor.
2. **A remote Bitcoin node** — only if you configure one. Over Tor by default.
3. **A UTXO snapshot host** — only if you choose fast sync and only to the
   address you supply. The app ships no default host.
4. **A block explorer (Blockbook)** — only if you turn on the explorer fallback
   in Settings and supply a server address. This is **off by default** and there
   is no default server.

   When it is on, the app sends your Bitcoin addresses to that server so it can
   return balances and transaction history. That server's operator — not us —
   learns those addresses and the IP they were requested from. You can route
   these requests through Tor, which hides your IP but not the addresses. The
   most private option is to point it at a Blockbook you run yourself.

The app makes no other outbound connection. It denies cleartext traffic to
everything except loopback.

## Permissions

| Permission | Used for |
|---|---|
| Internet, network state | Bitcoin network traffic |
| Foreground service, notifications | Keeping the node running and showing you that it is |
| Wake lock | Optional, off by default: faster sync with the screen off |
| Camera | Optional: scanning addresses and PSBTs. Images are processed on device and never stored or transmitted |
| Biometrics | Optional: authenticating before signing |

The app does **not** request location, contacts, phone state, storage, or an
advertising ID.

## Third parties

None. No SDKs that transmit data. No processors, because there is nothing to
process.

## Your data on the blockchain

Bitcoin transactions are public and permanent by design. Anything you send or
receive is recorded on a public ledger that we do not control and nobody can
delete. Using a fresh address for each payment, and using Tor, limits what can
be linked to you.

## Children

Not directed at children and collects no data from anyone.

## Deletion

Uninstalling removes everything the app stored. There is nothing held elsewhere
to request deletion of.

**Uninstalling also deletes your wallet.** If you have not written down your
seed phrase, your funds are gone permanently. Back it up before uninstalling.

## Changes

If this policy ever changes, the version shipped with the app is updated in the
same release. If a future version adds anything that transmits data, this
document will say so explicitly and before the fact.

## Contact

solihudeen@gmail.com
