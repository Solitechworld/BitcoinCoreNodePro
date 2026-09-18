# Bitcoin Core Node — user manual

For the person holding the phone.

---

## What this actually is

Most Bitcoin apps on a phone ask someone else's server what your balance is.
This one doesn't. It runs **Bitcoin Core** — the same software that runs on the
network's full nodes — on your device, and asks it directly.

That means:

* Nobody learns your addresses by serving you your own balance.
* You verify the rules yourself instead of taking a server's word for them.
* Your keys never leave the phone.

And it means real costs, stated plainly:

* It uses **storage** (about 16 GB), **battery**, and **mobile data**.
* The first sync takes hours, not seconds.
* If you lose your seed phrase, nobody can recover your money. Not us. There is
  no support line that can help.

If those costs don't suit you, you can point this app at a node you run
somewhere else instead — same interface, none of the local cost.

---

## Getting started

### 1. Choose where your node lives

**On this phone.** Needs ~25 GB free. Most private, most independent.

**Somewhere else.** A node at home, on a server, on a Raspberry Pi. Connect over
Tor. Fast, cheap on the phone, and still yours.

You can change your mind later; the app works identically either way.

### 2. If the node is on this phone: choose how it catches up

**Full sync from the beginning.** Verifies every block since 2009. Most
thorough. Takes days on a phone.

**Fast sync from a snapshot (assumeutxo).** Usable in minutes. Your node loads a
snapshot of who owns what, then quietly verifies all of history in the
background over the following hours.

The fast option involves a real trade, and the app doesn't hide it: until
background validation finishes, you are trusting a checkpoint built into Bitcoin
Core rather than something you verified yourself. The Node screen shows both
progress bars for exactly this reason — you can always see which state you're in.

You supply the snapshot's address. The app ships no default, because a default
would mean everyone who installs it fetching 11 GB from the same place and
telling that server they just installed a Bitcoin wallet.

### 3. Create a wallet

**Set a passphrase.** This encrypts your keys on the device. Without one,
anyone who unlocks your phone can spend your money — which is why the app shows
an unencrypted wallet in red.

**Write your seed phrase on paper.** Not a screenshot, not a password manager,
not a photo. Paper, somewhere safe. It is the only way to recover your money if
the phone is lost, stolen, or dropped in a river.

---

## Everyday use

### Receiving

Tap **Receive**. You get an address and a QR code.

**Use a fresh address every time.** The app gives you a new one on every visit,
and there's a button for another. This is not fussiness: if two people pay the
same address, each of them can see the other's payment, forever, on a public
ledger.

### Sending

1. Paste or scan the address. The app checks it with your node and shows it
   **grouped in fours** — compare those groups against the source. Malware that
   swaps a copied address relies on people checking only the start and end.
2. Enter the amount. The other unit is shown underneath — glance at it. It's the
   cheapest way to catch a misplaced decimal point.
3. Pick a fee. Faster costs more. "~30 minutes" is a sensible default.
4. **Review.** You see the exact fee, size, and change before anything is
   signed. Nothing has left yet.
5. Slide to broadcast.

Once broadcast, it cannot be recalled. It can only be replaced at a higher fee,
and only while unconfirmed.

### Coin control

On the Send screen, open **Coin control** to choose which coins to spend.

Worth doing when the app flags **⚠ address reused**. Spending that coin publicly
links it to an earlier payment to the same address. If those payments came from
different people, you've just connected them to each other.

### If a transaction is stuck

Unconfirmed for hours usually means the fee was too low for current demand. If
the app shows **Fee can be bumped**, you can replace it with a higher-fee
version. If it doesn't, your node can't tell whether replacement is possible,
and the app won't offer a button that would fail.

---

## Reading the Node screen

**Synced** — at the tip, verifying everything itself. This is the goal state.

**Syncing** — catching up. Balances and history are incomplete until it
finishes; the app won't let you send, because a fee computed from a partial
mempool is a fee that gets your transaction stuck.

**Snapshot chainstate + Background validation** — you fast-synced. Usable now,
still verifying history. The second bar is what you're waiting for.

**Peers** — other nodes you're talking to. 8–12 is healthy. Peers marked **v2**
use encrypted connections; peers marked **Tor** don't know your IP address.

**Mempool** — transactions waiting to confirm, network-wide. This is where fee
estimates come from. Empty usually means data-saver mode is on.

---

## What happens when you leave the app

**Minimising it does not stop the download.** Switch apps, lock the screen, put
the phone in your pocket — the node keeps syncing. You will see an ongoing
notification for as long as it is running; that notification is not an
advertisement, it is Android's requirement for any app allowed to keep working
off screen, and it carries a **Stop node** button so you can stop it without
opening the app.

**Closing the app does stop it.** Swipe it out of the recents list and the node
shuts down properly — which takes up to a minute while Bitcoin Core writes its
UTXO cache to disk. Don't force-stop the app during that minute; an interrupted
write is the one thing that can cost you a full resync.

**Reopening starts it again.** If the node was running when you closed the app,
it comes back on by itself and carries on from the block it reached. Nothing is
re-downloaded. If you stopped it deliberately with **Stop node**, it stays
stopped until you start it again.

One exception: a node that *crashed* does not restart itself. It waits on the
Node screen with the reason, because a binary that just failed will usually fail
again, and a silent restart loop hides the thing you need to read.

If you want the sync to go faster with the screen off, Settings has a
**Keep CPU awake while syncing** toggle. It is off by default and it does cost
battery — worth turning on overnight on a charger, not worth leaving on.

---

## Settings worth knowing about

**Data saver (blocks-only).** Cuts mobile data by roughly ten times. The cost:
your node stops seeing unconfirmed transactions, so fee estimates degrade and
the mempool screen goes empty.

**Storage budget.** How much block data to keep. Lower is fine — your node still
verifies everything, it just doesn't keep old blocks after checking them.

**Keep CPU awake while syncing.** Speeds up the first sync considerably. Uses
noticeably more battery. Off by default; turn it on overnight while charging.

**Block screenshots.** On by default. Also hides the app in the recent-apps
switcher, so a balance or seed can't be caught in that preview.

**Tor.** Routes your node's traffic through Tor. Slower, much more private.
Requires Orbot installed.

---

## Things that look wrong but aren't

**"It's been stuck at 99.99% for an hour."** Progress is measured by work done,
not blocks remaining, and the last stretch is disproportionately slow. Watch the
blocks-behind number instead — it moves.

**"It took 30 seconds to start."** Bitcoin Core loads its block index before it
can answer anything. The startup screen tells you what it's doing.

**"Fee estimates say 'no estimate'."** Your node hasn't watched the network long
enough to have an opinion. It will. The app says so instead of inventing a
number, because an invented fee is how transactions get stuck for a week.

**"My balance is lower than I expected."** Spendable excludes money that's
arrived but isn't confirmed yet. Both figures are shown separately.

---

## If something goes wrong

**Node won't start after a crash or force-close.** Usually an interrupted
shutdown. Node → Reindex rebuilds the database. It takes a while and it fixes it.

**"Not enough storage."** Free up space or lower the storage budget. The node
needs headroom above its target, not exactly its target.

**Phone gets hot during first sync.** Normal — it's verifying millions of
signatures. It stops once synced. Charging while syncing helps.

**Lost the phone.** Install the app elsewhere, restore from your seed phrase.
Your money is on the blockchain, not on the phone. This is exactly the situation
the seed exists for — which is why it belongs on paper, not in the phone that
just went missing.
