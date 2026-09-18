package com.solitech.bitcoincorenode.data.repo

import com.solitech.bitcoincorenode.core.model.AddressInfo
import com.solitech.bitcoincorenode.core.model.Balances
import com.solitech.bitcoincorenode.core.model.BitcoinJsonHelper
import com.solitech.bitcoincorenode.core.model.DescriptorList
import com.solitech.bitcoincorenode.core.model.FundedPsbt
import com.solitech.bitcoincorenode.core.model.PsbtAnalysis
import com.solitech.bitcoincorenode.core.model.Sats
import com.solitech.bitcoincorenode.core.model.Utxo
import com.solitech.bitcoincorenode.core.model.WalletInfo
import com.solitech.bitcoincorenode.core.model.WalletTx
import com.solitech.bitcoincorenode.core.rpc.BitcoinJson
import com.solitech.bitcoincorenode.core.rpc.RpcClient
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.core.rpc.RpcProvider
import com.solitech.bitcoincorenode.core.rpc.params
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The wallet, expressed entirely as Bitcoin Core descriptor-wallet RPCs.
 *
 * Nothing in this app implements key derivation, script construction, sighash
 * computation, or signing. That is deliberate and it is the single most
 * important safety decision in the codebase: those are precisely the areas
 * where a subtle bug silently produces an invalid signature, an unspendable
 * output, or a leaked nonce. Core's implementations are the ones with a decade
 * of adversarial review and a very large bug bounty in the form of the coins
 * they secure.
 *
 * What this file contains is orchestration: which RPC, in what order, with what
 * parameters, and how to explain the result.
 */
@Singleton
class WalletRepository @Inject constructor(
    private val rpcProvider: RpcProvider,
) {
    private val rpc: RpcClient
        get() = rpcProvider.active.value ?: throw RpcError.NotReachable("no node selected", null)

    // -- wallet lifecycle ----------------------------------------------------

    suspend fun listWalletFiles(): List<String> =
        BitcoinJsonHelper.decodeList(String.serializer(), rpc.call("listwalletdir")
            .jsonObject["wallets"]?.jsonArray?.let { arr ->
                kotlinx.serialization.json.JsonArray(
                    arr.map { it.jsonObject["name"] ?: JsonPrimitive("") }
                )
            } ?: JsonArray(emptyList()), "listwalletdir")

    suspend fun listLoadedWallets(): List<String> =
        BitcoinJsonHelper.decodeList(String.serializer(), rpc.call("listwallets"), "listwallets")

    /**
     * `load_on_startup=true` is not optional here. Without it the wallet is
     * loaded for this node run only, and the next restart silently drops it.
     * The user experience of that is exactly "my imported wallet and its
     * balance disappeared": the app shows whichever wallet still loads, with
     * no hint the other one exists. Persistence is the whole point of loading.
     */
    suspend fun loadWallet(name: String) {
        rpc.call("loadwallet", params { add(name); add(true) },
            timeout = RpcClient.NO_TIMEOUT)
    }

    suspend fun unloadWallet(name: String) {
        rpc.call("unloadwallet", params { add(name) })
    }

    /**
     * Creates a descriptor wallet.
     *
     * The parameter order matters and is easy to get wrong:
     *   createwallet(wallet_name, disable_private_keys, blank, passphrase,
     *                avoid_reuse, descriptors, load_on_startup, external_signer)
     *
     * `avoidReuse` defaults to true here, against Core's own default of false.
     * It makes the wallet refuse to spend from an address it has already spent
     * from unless explicitly told to. Address reuse is the most common way
     * people destroy their own on-chain privacy, and a mobile wallet's users
     * are the least likely to be tracking it manually.
     */
    suspend fun createWallet(
        name: String,
        passphrase: String?,
        watchOnly: Boolean = false,
        blank: Boolean = false,
        avoidReuse: Boolean = true,
        legacy: Boolean = false,
    ) {
        rpc.call(
            "createwallet",
            params {
                add(name)
                add(watchOnly)          // disable_private_keys
                add(blank)              // blank
                add(passphrase ?: "")   // passphrase
                add(avoidReuse)         // avoid_reuse
                add(!legacy)            // descriptors — false creates a legacy BDB wallet
                add(true)               // load_on_startup
            },
        )
    }

    suspend fun info(wallet: String): WalletInfo =
        BitcoinJsonHelper.decode(
            WalletInfo.serializer(), rpc.call("getwalletinfo", wallet = wallet), "getwalletinfo"
        )

    suspend fun balances(wallet: String): Balances =
        BitcoinJsonHelper.decode(
            Balances.serializer(), rpc.call("getbalances", wallet = wallet), "getbalances"
        )

    // -- locking -------------------------------------------------------------

    /**
     * Unlocks for [seconds], then Core re-locks itself.
     *
     * The timeout is short by default and that is the point. An indefinitely
     * unlocked wallet on a device that lives in a pocket is a wallet anyone
     * holding the phone can spend from. Sixty seconds covers signing one
     * transaction, which is what unlocking is for.
     */
    suspend fun unlock(wallet: String, passphrase: String, seconds: Int = 60) {
        rpc.call("walletpassphrase", params { add(passphrase); add(seconds) }, wallet = wallet)
    }

    suspend fun lock(wallet: String) {
        rpc.call("walletlock", wallet = wallet)
    }

    // -- addresses -----------------------------------------------------------

    /**
     * @param addressType "bech32m" (Taproot), "bech32" (native SegWit v0),
     *   "p2sh-segwit", or "legacy". Defaults to bech32m: it is the cheapest to
     *   spend, the most private for script paths, and by now broadly accepted.
     *   The receive screen lets the user drop to bech32 for a sender whose
     *   software is old enough not to understand it.
     */
    suspend fun newAddress(
        wallet: String,
        label: String = "",
        addressType: String = "bech32m",
    ): String = rpc.call(
        "getnewaddress",
        params { add(label); add(addressType) },
        wallet = wallet,
    ).jsonPrimitive.content

    suspend fun addressInfo(wallet: String, address: String): AddressInfo =
        BitcoinJsonHelper.decode(
            AddressInfo.serializer(),
            rpc.call("getaddressinfo", params { add(address) }, wallet = wallet),
            "getaddressinfo",
        )

    /**
     * Addresses this wallet has actually used (listaddressgroupings),
     * most-funded first.
     *
     * The receive path for an old imported wallet: when the wallet is
     * encrypted-and-locked with an empty keypool, no NEW address can be
     * generated without the passphrase — but the wallet's existing addresses
     * are right there in its own records and are perfectly good to receive
     * on. Old Bitcoin Core did exactly this before the keypool existed.
     */
    suspend fun usedAddresses(wallet: String): List<Pair<String, Sats>> {
        val raw = rpc.call("listaddressgroupings", wallet = wallet).jsonArray
        val out = mutableListOf<Pair<String, Sats>>()
        for (group in raw) {
            for (entry in group.jsonArray) {
                val o = entry.jsonObject
                val addr = o["address"]?.jsonPrimitive?.content ?: continue
                val amount = o["amount"]?.jsonPrimitive?.content
                    ?.let { runCatching { Sats.ofBtc(java.math.BigDecimal(it)) }.getOrNull() }
                    ?: Sats.ZERO
                out += addr to amount
            }
        }
        return out.sortedByDescending { it.second.value }
    }

    suspend fun descriptors(wallet: String, includePrivate: Boolean = false): DescriptorList =
        BitcoinJsonHelper.decode(
            DescriptorList.serializer(),
            rpc.call("listdescriptors", params { add(includePrivate) }, wallet = wallet),
            "listdescriptors",
        )

    // -- history and coins ---------------------------------------------------

    data class TransactionPage(val entries: List<WalletTx>, val skipped: Int)

    /**
     * History with per-entry resilient decoding.
     *
     * A wallet.dat carries its own transaction records — an imported wallet
     * shows its history the moment it loads, blocks or no blocks. If the
     * screen shows nothing for a wallet that has records, the failure is
     * HERE, in the decode. Decoding the whole array atomically meant one
     * entry Core's older wallets shape differently wiped the entire list
     * into a silent empty state. Each entry is decoded on its own now; a bad
     * one is skipped and counted, never allowed to hide its neighbours.
     */
    suspend fun transactionsResilient(
        wallet: String,
        count: Int = 100,
        skip: Int = 0,
    ): TransactionPage {
        val raw = rpc.call(
            "listtransactions", params { add("*"); add(count); add(skip) }, wallet = wallet
        ).jsonArray
        val entries = mutableListOf<WalletTx>()
        var skipped = 0
        for (element in raw) {
            try {
                entries += BitcoinJson.decodeFromJsonElement(WalletTx.serializer(), element)
            } catch (e: Exception) {
                skipped++
            }
        }
        return TransactionPage(entries, skipped)
    }

    suspend fun transactions(wallet: String, count: Int = 100, skip: Int = 0): List<WalletTx> =
        transactionsResilient(wallet, count, skip).entries

    /**
     * What a wallet's own records say, independent of the node's chain
     * position.
     *
     * Verified live against a real 2011 wallet.dat on Core 28.3 with the chain
     * deliberately missing every one of its blocks: getbalances reported zero
     * across the board — trusted, untrusted_pending and immature all
     * 0.00000000 — while getwalletinfo counted 9 transactions and
     * listtransactions listed every record with its full amount. The recorded
     * amounts are the only on-device truth about such a wallet until the
     * chain verifies its blocks, and a balance screen with no mention of them
     * reads as "the coins are gone".
     */
    data class OnFileFacts(
        val txCount: Long,
        /** Net the records sum to: receives − sends − fees, conflicts excluded. */
        val recorded: Sats,
    ) {
        companion object {
            /**
             * Sums a wallet's recorded history straight from listtransactions.
             *
             * listtransactions repeats the same fee on every entry of a
             * multi-output send, so fees are counted once per txid; receives
             * are deduped per (txid, vout). Core reports fees negative — a
             * cost — so they are summed by absolute value. Conflicted and
             * abandoned entries are skipped — that is money that did not
             * happen.
             */
            fun netRecorded(entries: List<WalletTx>): Sats {
                var net = Sats.ZERO
                val seenOutputs = HashSet<String>()
                val fees = HashMap<String, Sats>()
                for (tx in entries) {
                    if (tx.isConflicted || tx.abandoned == true) continue
                    if (tx.isIncoming) {
                        if (seenOutputs.add("${tx.txid}:${tx.vout}")) net += tx.amount
                    } else {
                        net += tx.amount
                        tx.fee?.let { fees[tx.txid] = it.absolute }
                    }
                }
                fees.values.forEach { net -= it }
                return net
            }
        }
    }

    suspend fun onFileFacts(wallet: String): OnFileFacts {
        val info = info(wallet)
        val entries = transactionsResilient(wallet, count = 1000).entries
        return OnFileFacts(info.txcount, OnFileFacts.netRecorded(entries))
    }

    suspend fun transaction(wallet: String, txid: String): WalletTx =
        BitcoinJsonHelper.decode(
            WalletTx.serializer(),
            rpc.call("gettransaction", params { add(txid); add(false); add(true) }, wallet = wallet),
            "gettransaction",
        )

    suspend fun utxos(wallet: String, minConf: Int = 0): List<Utxo> =
        BitcoinJsonHelper.decodeList(
            Utxo.serializer(),
            rpc.call("listunspent", params { add(minConf) }, wallet = wallet),
            "listunspent",
        )

    // -- sending -------------------------------------------------------------

    /**
     * Builds an unsigned transaction.
     *
     * Note what this is *not*: `sendtoaddress`. That RPC builds, signs and
     * broadcasts in one step, which means the user's first sight of the actual
     * fee, the change output and the input set is after the money has already
     * left. Splitting it into fund → review → sign → broadcast means the review
     * screen shows exactly what will be signed, and the user can still walk
     * away. For a wallet, that is not a nicety.
     *
     * @param selectedInputs coin control. When non-empty, only these outpoints
     *   are spent and Core will not add others.
     * @param subtractFeeFrom pay the fee out of the recipient's amount rather
     *   than adding it — what "send max" actually means.
     */
    suspend fun buildTransaction(
        wallet: String,
        recipients: List<Pair<String, Sats>>,
        feeRateSatPerVb: Double,
        selectedInputs: List<Pair<String, Int>> = emptyList(),
        subtractFeeFrom: List<Int> = emptyList(),
        replaceable: Boolean = true,
        changeAddress: String? = null,
    ): FundedPsbt {
        require(recipients.isNotEmpty()) { "a transaction needs at least one recipient" }

        val inputsJson = JsonArray(
            selectedInputs.map { (txid, vout) ->
                JsonObject(
                    mapOf(
                        "txid" to JsonPrimitive(txid),
                        "vout" to JsonPrimitive(vout),
                        // Signal BIP125 per input. Core also has a top-level
                        // "replaceable" option; setting both is harmless and
                        // makes the intent explicit in the PSBT itself.
                        "sequence" to JsonPrimitive(if (replaceable) 0xfffffffdL else 0xffffffffL),
                    )
                )
            }
        )

        // Core wants outputs as [{address: amount}, ...] with amounts in BTC.
        // Sats.toBtcString() is exact — see the long note in Amount.kt about
        // why nothing here goes through a Double.
        val outputsJson = JsonArray(
            recipients.map { (address, amount) ->
                JsonObject(mapOf(address to JsonPrimitive(amount.toBtcString())))
            }
        )

        val options = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("fee_rate", JsonPrimitive(feeRateSatPerVb))   // sat/vB in Core 24+
            put("replaceable", JsonPrimitive(replaceable))
            if (selectedInputs.isNotEmpty()) put("add_inputs", JsonPrimitive(false))
            if (subtractFeeFrom.isNotEmpty()) {
                put("subtractFeeFromOutputs", JsonArray(subtractFeeFrom.map { JsonPrimitive(it) }))
            }
            if (changeAddress != null) put("changeAddress", JsonPrimitive(changeAddress))
        }

        val result = rpc.call(
            "walletcreatefundedpsbt",
            params {
                add(inputsJson)
                add(outputsJson)
                add(0)                       // locktime
                add(JsonObject(options))
                add(true)                    // bip32derivs — needed for PSBT interop
            },
            wallet = wallet,
        )
        return BitcoinJsonHelper.decode(FundedPsbt.serializer(), result, "walletcreatefundedpsbt")
    }

    /** Signs with this wallet's keys. Requires the wallet to be unlocked. */
    suspend fun signPsbt(wallet: String, psbt: String): SignResult {
        val result = rpc.call(
            "walletprocesspsbt",
            params { add(psbt); add(true); add("ALL"); add(true) },
            wallet = wallet,
        ).jsonObject
        return SignResult(
            psbt = result["psbt"]?.jsonPrimitive?.content ?: psbt,
            complete = result["complete"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /** Turns a fully-signed PSBT into a raw transaction. */
    suspend fun finalizePsbt(psbt: String): FinalizeResult {
        val result = rpc.call("finalizepsbt", params { add(psbt); add(true) }).jsonObject
        return FinalizeResult(
            hex = result["hex"]?.jsonPrimitive?.content,
            psbt = result["psbt"]?.jsonPrimitive?.content,
            complete = result["complete"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /**
     * The point of no return.
     *
     * `maxfeerate` is left at Core's default rather than raised. It is a
     * backstop that refuses to broadcast a transaction paying an absurd fee,
     * and it has saved people from fat-fingered fee rates more than once.
     * Raising it to "make the error go away" defeats a safety check that exists
     * for exactly the mistake a phone keyboard makes easy.
     */
    suspend fun broadcast(rawTxHex: String): String =
        rpc.call("sendrawtransaction", params { add(rawTxHex) }).jsonPrimitive.content

    suspend fun analyzePsbt(psbt: String): PsbtAnalysis =
        BitcoinJsonHelper.decode(
            PsbtAnalysis.serializer(), rpc.call("analyzepsbt", params { add(psbt) }), "analyzepsbt"
        )

    /**
     * Fee-bumps a stuck transaction (BIP125 RBF).
     *
     * Returns the new PSBT for review and signing rather than broadcasting.
     * `psbtbumpfee` is used instead of `bumpfee` for exactly the same reason as
     * above: the user sees the replacement before it exists on the network.
     */
    suspend fun bumpFee(wallet: String, txid: String, newFeeRateSatPerVb: Double?): String {
        val options = buildMap<String, kotlinx.serialization.json.JsonElement> {
            if (newFeeRateSatPerVb != null) put("fee_rate", JsonPrimitive(newFeeRateSatPerVb))
        }
        val result = rpc.call(
            "psbtbumpfee",
            params { add(txid); if (options.isNotEmpty()) add(JsonObject(options)) },
            wallet = wallet,
        ).jsonObject
        return result["psbt"]?.jsonPrimitive?.content
            ?: throw RpcError.Malformed("psbtbumpfee", "no psbt in response")
    }

    /** Rescans for history. Bounded by the prune horizon on a pruned node. */
    suspend fun rescan(wallet: String, startHeight: Long = 0) {
        rpc.call(
            "rescanblockchain",
            params { add(startHeight) },
            wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT,
        )
    }

    /** Imports descriptors, e.g. when restoring a watch-only wallet. */
    suspend fun importDescriptors(wallet: String, descriptors: List<JsonObject>) {
        rpc.call(
            "importdescriptors",
            params { add(JsonArray(descriptors)) },
            wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT,
        )
    }

    // -- wallet tools --------------------------------------------------------
    //
    // Import, encryption, backup, messages, maintenance. As with the send flow,
    // all orchestration: no key handling or crypto is reimplemented here.

    /**
     * Migrates a legacy Berkeley-DB `wallet.dat` into a descriptor wallet.
     *
     * ## Why this is a migration and not an "import"
     *
     * Core 30 does not link Berkeley DB at all -- the legacy wallet
     * implementation is gone. What remains is `src/wallet/migrate.cpp`, a
     * read-only BDB reader whose only purpose is converting an old wallet.dat
     * into a descriptor wallet, once. (Core 28.x additionally has `bdb.cpp`
     * and can keep operating a legacy wallet; this call works on both.)
     *
     * The file must already sit at `<datadir>/wallets/<name>/wallet.dat`
     * before this is called -- `migratewallet` takes a wallet NAME, not a path.
     * See `WalletImporter` for the staging step.
     *
     * Core writes a `<name>-<timestamp>.legacy.bak` beside it first; the
     * returned [MigrationResult.backupPath] is where that went.
     *
     * @param passphrase required if the legacy wallet was encrypted. Without
     *   it Core cannot read the keys, and the migration fails rather than
     *   silently producing a watch-only wallet.
     */
    suspend fun migrateLegacyWallet(name: String, passphrase: String?): MigrationResult {
        val result = rpc.call(
            "migratewallet",
            params { add(name); passphrase?.let { add(it) } },
            timeout = RpcClient.NO_TIMEOUT,
        ).jsonObject
        return MigrationResult(
            walletName = result["wallet_name"]?.jsonPrimitive?.content ?: name,
            watchonlyName = result["watchonly_name"]?.jsonPrimitive?.content,
            solvablesName = result["solvables_name"]?.jsonPrimitive?.content,
            backupPath = result["backup_path"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    /**
     * Outcome of importing a legacy wallet.dat.
     *
     * Two genuinely different things can happen, and the UI must not conflate
     * them: the wallet was opened as-is (Core 28.x, `bdb.cpp` present), or it
     * had to be converted (Core 29+, migration only).
     */
    sealed interface LegacyImport {
        /** Opened in place. Still a legacy BDB wallet; nothing was converted. */
        data class Loaded(val walletName: String) : LegacyImport
        /** Converted to a descriptor wallet. The original was backed up. */
        data class Migrated(val result: MigrationResult) : LegacyImport
        /** Encrypted, and migration is the only route on this node. */
        data class NeedsPassphrase(val walletName: String) : LegacyImport
    }

    /**
     * Imports a legacy wallet.dat, doing the least destructive thing that works.
     *
     * ## Order matters, and it is not the obvious one
     *
     * Try `loadwallet` FIRST. On Core 28.x — which this app ships — `bdb.cpp`
     * is linked and a legacy wallet opens directly, in place, unconverted, and
     * **without a passphrase**. A passphrase protects spending, not opening.
     * Asking for one up front is simply wrong there, and it turns a two-tap
     * import into a dead end for anyone who does not have the passphrase to
     * hand (or whose wallet never had one).
     *
     * Only if that fails do we fall back to `migratewallet`, which is the sole
     * option on Core 29+ where the legacy backend was removed. Migration is
     * irreversible, so it is the fallback and never the default.
     *
     * The passphrase is therefore optional and only consulted on the migration
     * path, for a wallet that is actually encrypted.
     */
    suspend fun importLegacyWallet(name: String, passphrase: String?): LegacyImport {
        // 1. Open it as-is.
        try {
            loadWallet(name)
            return LegacyImport.Loaded(name)
        } catch (e: RpcError.Rpc) {
            val msg = e.rpcMessage.lowercase()
            val alreadyLoaded = e.code == -35 || "already loaded" in msg
            if (alreadyLoaded) return LegacyImport.Loaded(name)

            val needsMigration = "descriptor" in msg || "legacy" in msg ||
                "berkeley" in msg || "bdb" in msg || "not supported" in msg
            if (!needsMigration) throw e
        }

        // 2. This node cannot open legacy wallets. Convert.
        return try {
            LegacyImport.Migrated(migrateLegacyWallet(name, passphrase))
        } catch (e: RpcError.Rpc) {
            if ("passphrase" in e.rpcMessage.lowercase() && passphrase.isNullOrBlank()) {
                LegacyImport.NeedsPassphrase(name)
            } else {
                throw e
            }
        }
    }

    /**
     * Kicks off a background rescan of an imported wallet.
     *
     * ## Why this exists — the "imported wallet has no balance" bug
     *
     * A wallet.dat carries its own transaction records, so a straight load
     * *usually* shows history immediately. But two very common situations
     * leave the wallet showing zero until a rescan runs:
     *
     *  - The wallet was migrated, and the migration rescan could only cover
     *    blocks the pruned node still has. Anything older is invisible.
     *  - The wallet file came from a watch-only or partially-synced source,
     *    or keys were imported fresh (importwallet/dump) with no history
     *    records in the file at all.
     *
     * `rescanblockchain` from height 0 (bounded by the prune horizon on a
     * pruned node) rebuilds the wallet's view of the chain. It is safe to run
     * on an already-complete wallet — Core just re-confirms what it knows.
     *
     * This is fire-and-forget by design: rescans take minutes, and the import
     * flow must not block on one. The wallet screen surfaces scan progress
     * separately via `scanning` in getwalletinfo.
     */
    sealed interface RescanOutcome {
        data object Started : RescanOutcome
        /** A rescan is already running; nothing to do. */
        data object AlreadyRunning : RescanOutcome
        /**
         * The wallet is encrypted and locked. Core's rescanblockchain refuses
         * to run on a locked wallet (EnsureWalletIsUnlocked, -13) — the scan
         * needs walletpassphrase first.
         */
        data object NeedsUnlock : RescanOutcome
        /**
         * The wallet's history predates the blocks this pruned node still
         * has. Balances for those coins cannot be recovered by scanning —
         * the blocks are simply not on disk.
         */
        data class BlockedByPrune(val detail: String) : RescanOutcome
        /** Something else refused the scan; shown verbatim. */
        data class Failed(val detail: String) : RescanOutcome
    }

    /**
     * Kicks off a background rescan of an imported wallet.
     *
     * ## Why the start height is the prune height — the actual "0 balance" bug
     *
     * Core's rescanblockchain (src/wallet/rpc/transactions.cpp) validates the
     * requested range with `hasBlocks()` and throws "Can't rescan beyond
     * pruned data" if ANY of it dips below the prune horizon. Asking for
     * height 0 on a pruned node — the app's default configuration — makes
     * Core reject the WHOLE scan. The wallet then never looks at even the
     * blocks it still has, and the balance stays 0.00000000 forever.
     *
     * Starting at `pruneheight` scans everything on disk, which is the best a
     * pruned node can do.
     *
     * ## Locked wallets
     *
     * The RPC also requires the wallet to be unlocked (EnsureWalletIsUnlocked,
     * error -13). An encrypted wallet.dat that loaded fine but is locked will
     * fail the scan with "Please enter the wallet passphrase" — surfaced as
     * [RescanOutcome.NeedsUnlock] so the caller can ask for it or explain.
     */
    suspend fun rescanImportedWallet(name: String): RescanOutcome {
        val chain = rpc.call("getblockchaininfo").jsonObject
        val pruned = chain["pruned"]?.jsonPrimitive?.booleanOrNull ?: false
        val pruneHeight = chain["pruneheight"]?.jsonPrimitive?.intOrNull ?: 0
        val tip = chain["blocks"]?.jsonPrimitive?.longOrNull ?: 0

        val start = if (pruned) pruneHeight.coerceAtLeast(0) else 0
        if (start > tip) {
            // Nothing on disk to scan yet (early IBD). The wallet scans new
            // blocks automatically as they connect, so nothing is lost.
            return RescanOutcome.Started
        }

        return try {
            rpc.call(
                "rescanblockchain",
                params { add(start) },
                wallet = name,
                timeout = RpcClient.NO_TIMEOUT,
            )
            RescanOutcome.Started
        } catch (e: RpcError.Rpc) {
            val msg = e.rpcMessage.lowercase()
            when {
                "passphrase" in msg || e.code == -13 -> RescanOutcome.NeedsUnlock
                "prune" in msg -> RescanOutcome.BlockedByPrune(e.rpcMessage)
                "rescanning" in msg -> RescanOutcome.AlreadyRunning
                else -> RescanOutcome.Failed(e.rpcMessage)
            }
        } catch (e: RpcError) {
            RescanOutcome.Failed(e.userMessage())
        }
    }

    /**
     * Encrypts a wallet that has no passphrase yet.
     *
     * One-way: Core has no decrypt operation. It also replaces the keypool, so
     * any backup taken before this will NOT open the wallet afterwards. The UI
     * says so before running it.
     */
    suspend fun encryptWallet(wallet: String, passphrase: String) {
        rpc.call("encryptwallet", params { add(passphrase) }, wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT)
    }

    /** Changes an existing passphrase. Requires the old one; there is no reset. */
    suspend fun changePassphrase(wallet: String, oldPassphrase: String, newPassphrase: String) {
        rpc.call(
            "walletpassphrasechange",
            params { add(oldPassphrase); add(newPassphrase) },
            wallet = wallet,
        )
    }

    /**
     * Writes a wallet backup to [destination].
     *
     * A descriptor-wallet backup is a copy of the SQLite wallet file: the
     * descriptors and, for a hot wallet, the encrypted private keys. It is not
     * a substitute for the seed phrase, and a backup taken before a passphrase
     * change still opens only with the old passphrase.
     */
    suspend fun backupWallet(wallet: String, destination: String) {
        rpc.call("backupwallet", params { add(destination) }, wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT)
    }

    suspend fun restoreWallet(name: String, backupPath: String, loadOnStartup: Boolean = true) {
        rpc.call(
            "restorewallet",
            params { add(name); add(backupPath); add(loadOnStartup) },
            timeout = RpcClient.NO_TIMEOUT,
        )
    }

    /**
     * Dumps a legacy wallet's keys to a human-readable text file, like
     * `bitcoin-cli dumpwallet`. Returns the absolute path Core wrote.
     *
     * Descriptor wallets (which is what this app creates) do not support
     * dumpwallet — for those, use [dumpDescriptors] or [backupWallet].
     */
    suspend fun dumpWallet(wallet: String, destination: String): String =
        rpc.call(
            "dumpwallet",
            params { add(destination) },
            wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT,
        ).jsonObject["filename"]?.jsonPrimitive?.content ?: destination

    /**
     * The descriptor-wallet equivalent of dumpwallet: the wallet's descriptors,
     * private ones included, as text the user can copy or file away.
     */
    suspend fun dumpDescriptorsPrivate(wallet: String): String =
        rpc.call("listdescriptors", params { add(true) }, wallet = wallet).toString()

    /**
     * Imports a dumpwallet-produced text file, like `bitcoin-cli importwallet`.
     * Legacy wallets only. Long-running; triggers a rescan afterwards.
     */
    suspend fun importWallet(wallet: String, filename: String) {
        rpc.call(
            "importwallet",
            params { add(filename) },
            wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT,
        )
    }

    /** Imports a single private key (legacy wallets only). Rescans by default. */
    suspend fun importPrivKey(wallet: String, key: String, label: String = "", rescan: Boolean = true) {
        rpc.call(
            "importprivkey",
            params { add(key); add(label); add(rescan) },
            wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT,
        )
    }

    /** Imports an address as watch-only (works on descriptor wallets via importaddress too). */
    suspend fun importAddress(
        wallet: String,
        address: String,
        label: String = "",
        rescan: Boolean = true,
        p2sh: Boolean = false,
    ) {
        rpc.call(
            "importaddress",
            params { add(address); add(label); add(rescan); add(p2sh) },
            wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT,
        )
    }

    /** Imports a public key as watch-only. */
    suspend fun importPubKey(wallet: String, pubkey: String, label: String = "", rescan: Boolean = true) {
        rpc.call(
            "importpubkey",
            params { add(pubkey); add(label); add(rescan) },
            wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT,
        )
    }

    /**
     * Signs a message with the private key of [address].
     *
     * Legacy (P2PKH) addresses only. Core refuses to sign messages with SegWit
     * or Taproot addresses through this RPC -- a limitation of the message
     * signing standard, not of this app. The UI checks the address shape first
     * and explains, rather than surfacing a bare -5.
     */
    suspend fun signMessage(wallet: String, address: String, message: String): String =
        rpc.call("signmessage", params { add(address); add(message) }, wallet = wallet)
            .jsonPrimitive.content

    /** Verifies a signature. Node-level: needs no wallet and no keys. */
    suspend fun verifyMessage(address: String, signature: String, message: String): Boolean =
        rpc.call("verifymessage", params { add(address); add(signature); add(message) })
            .jsonPrimitive.content.toBoolean()

    suspend fun setLabel(wallet: String, address: String, label: String) {
        rpc.call("setlabel", params { add(address); add(label) }, wallet = wallet)
    }

    /**
     * Marks an unconfirmed transaction as abandoned so its inputs can be respent.
     *
     * This does not cancel anything -- nothing can. It updates this wallet's own
     * bookkeeping for a transaction that is genuinely not in the mempool. If the
     * transaction is still live on the network it will confirm regardless.
     */
    suspend fun abandonTransaction(wallet: String, txid: String) {
        rpc.call("abandontransaction", params { add(txid) }, wallet = wallet)
    }

    suspend fun keypoolRefill(wallet: String, size: Int = 1000) {
        rpc.call("keypoolrefill", params { add(size) }, wallet = wallet,
            timeout = RpcClient.NO_TIMEOUT)
    }
}

data class SignResult(val psbt: String, val complete: Boolean)
data class FinalizeResult(val hex: String?, val psbt: String?, val complete: Boolean)

/**
 * Result of migrating a legacy wallet.dat.
 *
 * Migration can produce up to THREE wallets, which surprises people:
 *  - the primary descriptor wallet (keys you control)
 *  - a watch-only wallet, if the legacy file tracked scripts without keys
 *  - a "solvables" wallet, for scripts solvable but not watched
 *
 * The UI shows all three, or the user concludes funds went missing.
 */
data class MigrationResult(
    val walletName: String,
    val watchonlyName: String?,
    val solvablesName: String?,
    val backupPath: String,
)
