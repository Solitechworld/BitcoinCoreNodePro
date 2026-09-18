package com.solitech.bitcoincorenode.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wallet-side models, transcribed from Bitcoin Core 30.3:
 *   getwalletinfo  -> wallet/rpc/wallet.cpp
 *   getbalances    -> wallet/rpc/coins.cpp
 *   listunspent    -> wallet/rpc/coins.cpp
 *   gettransaction, listtransactions, TransactionDescriptionString
 *                  -> wallet/rpc/transactions.cpp
 *   getaddressinfo -> wallet/rpc/addresses.cpp
 *   listdescriptors-> wallet/rpc/backup.cpp
 *   analyzepsbt    -> rpc/rawtransaction.cpp
 *
 * Run `native/scripts/verify-rpc-models.py` after any Core upgrade -- it diffs
 * these declarations against the node source and tells you what moved.
 *
 * Note for anyone coming from an older Core: `getwalletinfo` no longer carries
 * `balance` / `unconfirmed_balance` / `immature_balance`. Balances come from
 * `getbalances` and nowhere else.
 */

@Serializable
data class WalletInfo(
    val walletname: String = "",
    val walletversion: Int = 0,
    val format: String = "",
    val txcount: Long = 0,
    val keypoolsize: Long = 0,
    @SerialName("keypoolsize_hd_internal") val keypoolsizeHdInternal: Long = 0,
    /** Unix time the passphrase lock expires; 0 = locked; absent = unencrypted. */
    @SerialName("unlocked_until") val unlockedUntil: Long? = null,
    val paytxfee: Double = 0.0,
    @SerialName("private_keys_enabled") val privateKeysEnabled: Boolean = true,
    @SerialName("avoid_reuse") val avoidReuse: Boolean = false,
    @Serializable(with = ScanProgressSerializer::class)
    val scanning: ScanProgress = ScanProgress.NotScanning,
    val descriptors: Boolean = true,
    @SerialName("external_signer") val externalSigner: Boolean = false,
    val blank: Boolean = false,
    val birthtime: Long? = null,
    val flags: List<String> = emptyList(),
) {
    /**
     * Three states, not two. `unlockedUntil == null` means the wallet has no
     * passphrase at all, which is a materially different situation from "has a
     * passphrase and is currently locked" -- and the UI must not show a
     * padlock for the first case, because that would imply a protection that
     * does not exist.
     */
    val encryption: EncryptionState get() = when {
        unlockedUntil == null -> EncryptionState.UNENCRYPTED
        unlockedUntil == 0L -> EncryptionState.LOCKED
        else -> EncryptionState.UNLOCKED
    }

    val isWatchOnly: Boolean get() = !privateKeysEnabled
}

enum class EncryptionState { UNENCRYPTED, LOCKED, UNLOCKED }

/** `scanning` is `false` when idle, or an object while a rescan runs. */
sealed interface ScanProgress {
    data object NotScanning : ScanProgress
    data class Scanning(val durationSeconds: Long, val progress: Double) : ScanProgress
}

object ScanProgressSerializer : KSerializer<ScanProgress> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ScanProgress", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ScanProgress {
        val input = decoder as? JsonDecoder ?: return ScanProgress.NotScanning
        return when (val el = input.decodeJsonElement()) {
            is JsonObject -> ScanProgress.Scanning(
                durationSeconds = el["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                progress = el["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
            is JsonPrimitive -> if (el.booleanOrNull == true) {
                ScanProgress.Scanning(0, 0.0)
            } else {
                ScanProgress.NotScanning
            }
            else -> ScanProgress.NotScanning
        }
    }

    override fun serialize(encoder: Encoder, value: ScanProgress) {
        encoder.encodeString(if (value is ScanProgress.Scanning) "scanning" else "idle")
    }
}

@Serializable
data class Balances(
    /**
     * Absent when the wallet has no private keys — Core omits `mine` entirely
     * for watch-only wallets instead of zeroing it. Treating absence as zero
     * is how a watch-only wallet ends up showing 0.00000000 forever.
     */
    val mine: BalanceGroup? = null,
    val watchonly: BalanceGroup? = null,
) {
    /**
     * The balance group this wallet actually reports. For a normal wallet
     * that is `mine`; for a watch-only wallet (including the extra wallets
     * `migratewallet` creates) it is `watchonly`.
     */
    val display: BalanceGroup get() = mine ?: watchonly ?: BalanceGroup()

    /** True when the only balances this wallet has are watch-only. */
    val isWatchOnlyBalance: Boolean get() = mine == null && watchonly != null

    /**
     * What the user can actually spend right now (or, for a watch-only
     * wallet, what is held at watched addresses).
     *
     * Deliberately NOT trusted + untrusted_pending. Untrusted pending is money
     * arriving from someone else that is still unconfirmed; presenting it as
     * spendable produces "insufficient funds" errors on a balance the app just
     * told the user they had. Immature coinbase is likewise unspendable for
     * 100 blocks. The dashboard shows all three separately for this reason.
     */
    val spendable: Sats get() = display.trusted

    val total: Sats get() = display.trusted + display.untrustedPending + display.immature
}

@Serializable
data class BalanceGroup(
    val trusted: Sats = Sats.ZERO,
    @SerialName("untrusted_pending") val untrustedPending: Sats = Sats.ZERO,
    val immature: Sats = Sats.ZERO,
    /** Only present when the wallet has avoid_reuse set. */
    val used: Sats? = null,
)

@Serializable
data class Utxo(
    val txid: String,
    val vout: Int,
    val address: String? = null,
    val label: String? = null,
    val scriptPubKey: String = "",
    val amount: Sats = Sats.ZERO,
    val confirmations: Long = 0,
    val ancestorcount: Long? = null,
    val ancestorsize: Long? = null,
    val ancestorfees: Sats? = null,
    val redeemScript: String? = null,
    val witnessScript: String? = null,
    val spendable: Boolean = false,
    val solvable: Boolean = false,
    val reused: Boolean? = null,
    val desc: String? = null,
    val safe: Boolean = true,
) {
    val outpoint: String get() = "$txid:$vout"
    val isConfirmed: Boolean get() = confirmations > 0

    /**
     * A UTXO paying to an address this wallet has already spent from. Spending
     * it links the two on-chain, which is the single most common way people
     * accidentally destroy their own privacy. Surfaced in coin control.
     */
    val isAddressReused: Boolean get() = reused == true
}

/** Direction of a wallet transaction, as Core's `category` reports it. */
enum class TxCategory(val rpcName: String) {
    SEND("send"),
    RECEIVE("receive"),
    GENERATE("generate"),
    IMMATURE("immature"),
    ORPHAN("orphan"),
    UNKNOWN("");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.rpcName == name } ?: UNKNOWN
    }
}

@Serializable
data class WalletTx(
    val txid: String = "",
    val wtxid: String? = null,
    val address: String? = null,
    val category: String? = null,
    val amount: Sats = Sats.ZERO,
    /** Core sets this on entries touching watch-only scripts. */
    @SerialName("involveswatchonly") val involvesWatchonly: Boolean = false,
    val label: String? = null,
    val vout: Int? = null,
    val fee: Sats? = null,
    val confirmations: Long = 0,
    val generated: Boolean? = null,
    val trusted: Boolean? = null,
    val blockhash: String? = null,
    val blockheight: Long? = null,
    val blockindex: Long? = null,
    val blocktime: Long? = null,
    val walletconflicts: List<String> = emptyList(),
    val mempoolconflicts: List<String> = emptyList(),
    @SerialName("replaced_by_txid") val replacedByTxid: String? = null,
    @SerialName("replaces_txid") val replacesTxid: String? = null,
    val time: Long = 0,
    val timereceived: Long = 0,
    val comment: String? = null,
    @SerialName("bip125-replaceable") val bip125Replaceable: String? = null,
    val abandoned: Boolean? = null,
    val hex: String? = null,
) {
    val kind: TxCategory get() = TxCategory.of(category)
    // "immature" is how modern Core reports coinbase that has not yet matured
    // (verified live against a regtest node). Leaving it out made those entries
    // render as "Sent".
    val isIncoming: Boolean get() = kind == TxCategory.RECEIVE ||
        kind == TxCategory.GENERATE || kind == TxCategory.IMMATURE
    val isPending: Boolean get() = confirmations == 0L
    val isConflicted: Boolean get() = confirmations < 0 || mempoolconflicts.isNotEmpty()

    /**
     * Whether this transaction can still be fee-bumped.
     *
     * Core answers "yes" / "no" / "unknown" here, and "unknown" is real: for a
     * transaction not in our mempool the node genuinely cannot tell. The UI
     * must not turn that into a confident "no" and hide the bump button, nor
     * into a confident "yes" and offer a bump that will fail.
     */
    val replaceability: Replaceability get() = when (bip125Replaceable) {
        "yes" -> Replaceability.YES
        "no" -> Replaceability.NO
        else -> Replaceability.UNKNOWN
    }

    val effectiveTime: Long get() = blocktime ?: time
}

enum class Replaceability { YES, NO, UNKNOWN }

@Serializable
data class AddressInfo(
    val address: String = "",
    val scriptPubKey: String = "",
    val ismine: Boolean = false,
    val iswatchonly: Boolean = false,
    val solvable: Boolean = false,
    val desc: String? = null,
    @SerialName("parent_desc") val parentDesc: String? = null,
    val isscript: Boolean = false,
    val ischange: Boolean = false,
    val iswitness: Boolean = false,
    @SerialName("witness_version") val witnessVersion: Int? = null,
    @SerialName("witness_program") val witnessProgram: String? = null,
    val script: String? = null,
    val hex: String? = null,
    val pubkey: String? = null,
    val iscompressed: Boolean? = null,
    val timestamp: Long? = null,
    val hdkeypath: String? = null,
    val hdseedid: String? = null,
    @SerialName("hdmasterfingerprint") val hdMasterFingerprint: String? = null,
    val labels: List<String> = emptyList(),
) {
    val scriptType: ScriptType get() = ScriptType.infer(address, iswitness, witnessVersion, isscript)
}

/** Address flavour, for display and for fee estimation. */
enum class ScriptType(val label: String, val short: String) {
    P2PKH("Legacy", "P2PKH"),
    P2SH("Nested SegWit", "P2SH"),
    P2WPKH("Native SegWit", "P2WPKH"),
    P2WSH("SegWit script", "P2WSH"),
    P2TR("Taproot", "P2TR"),
    UNKNOWN("Unknown", "?");

    companion object {
        fun infer(address: String, isWitness: Boolean, witnessVersion: Int?, isScript: Boolean): ScriptType {
            val a = address.lowercase()
            return when {
                a.startsWith("bc1p") || a.startsWith("tb1p") || witnessVersion == 1 -> P2TR
                a.startsWith("bc1q") || a.startsWith("tb1q") ->
                    if (a.length > 44) P2WSH else P2WPKH
                isWitness && witnessVersion == 0 -> if (isScript) P2WSH else P2WPKH
                address.startsWith("3") || address.startsWith("2") -> P2SH
                address.startsWith("1") || address.startsWith("m") || address.startsWith("n") -> P2PKH
                else -> UNKNOWN
            }
        }
    }
}

@Serializable
data class DescriptorList(
    @SerialName("wallet_name") val walletName: String = "",
    val descriptors: List<DescriptorEntry> = emptyList(),
)

@Serializable
data class DescriptorEntry(
    val desc: String = "",
    val timestamp: Long = 0,
    val active: Boolean = false,
    val internal: Boolean? = null,
    val range: List<Long>? = null,
    val next: Long? = null,
    @SerialName("next_index") val nextIndex: Long? = null,
) {
    /** "wpkh", "tr", "sh(wsh(multi", ... -- the outermost function. */
    val kind: String get() = desc.substringBefore('(').ifBlank { "?" }

    /** True when the descriptor carries private key material (xprv/WIF). */
    val hasPrivateKeys: Boolean get() = desc.contains("xprv") || desc.contains("tprv")
}

/** `walletcreatefundedpsbt` / `createpsbt` result. */
@Serializable
data class FundedPsbt(
    val psbt: String = "",
    val fee: Sats = Sats.ZERO,
    val changepos: Int = -1,
) {
    val hasChange: Boolean get() = changepos >= 0
}

@Serializable
data class PsbtAnalysis(
    val inputs: List<PsbtInputAnalysis> = emptyList(),
    @SerialName("estimated_vsize") val estimatedVsize: Long? = null,
    @SerialName("estimated_feerate") val estimatedFeerate: Double? = null,
    val fee: Sats? = null,
    val next: String? = null,
    val error: String? = null,
) {
    val isComplete: Boolean get() = next == "extractor"
    val needsSignatures: Boolean get() = next == "signer"
    val needsUpdating: Boolean get() = next == "updater"

    val feerateSatPerVb: Double? get() = estimatedFeerate?.let { it * 100_000_000.0 / 1000.0 }

    /** A sentence describing what has to happen next to this PSBT. */
    fun nextStepDescription(): String = when (next) {
        "updater" -> "Needs UTXO data or scripts added before it can be signed."
        "signer" -> "Ready to sign."
        "finalizer" -> "All signatures present. Needs finalizing."
        "extractor" -> "Fully signed and ready to broadcast."
        null -> error ?: "Cannot be completed."
        else -> next
    }
}

@Serializable
data class PsbtInputAnalysis(
    @SerialName("has_utxo") val hasUtxo: Boolean = false,
    @SerialName("is_final") val isFinal: Boolean = false,
    val next: String? = null,
    val missing: PsbtMissing? = null,
)

@Serializable
data class PsbtMissing(
    val pubkeys: List<String> = emptyList(),
    val signatures: List<String> = emptyList(),
    val redeemscript: String? = null,
    val witnessscript: String? = null,
)
