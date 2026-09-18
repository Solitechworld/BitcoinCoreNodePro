package com.solitech.bitcoincorenode.data.explorer

import com.solitech.bitcoincorenode.core.model.Sats
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Models for the Blockbook v2 REST API (Trezor's indexer).
 *
 *   GET /api/v2                        server + backend status
 *   GET /api/v2/address/{addr}         balance, tx count, txids
 *   GET /api/v2/address/{addr}?details=txs   ... with full transactions
 *   GET /api/v2/tx/{txid}              one transaction
 *   GET /api/v2/utxo/{addr}            spendable outputs
 *   GET /api/v2/estimatefee/{blocks}   fee estimate
 *   GET /api/v2/sendtx/{rawhex}        broadcast
 *
 * ## The one genuinely nice thing about this API
 *
 * Blockbook returns every amount as a **decimal string of satoshis**
 * ("2091239"), not as a JSON float of BTC. That is exactly the representation
 * this app wants: an exact integer, parsed with `toLong()`, with no BigDecimal
 * round-trip and no opportunity for binary floating point to lose a satoshi.
 * See the long note in `core/model/Amount.kt` for why that matters.
 *
 * Blockbook's own transaction objects *also* carry a `valueOut`-style decimal
 * BTC field in some deployments; this app reads only the satoshi-string fields
 * and ignores the rest.
 *
 * ## Verify these before trusting them
 *
 * These were written against Blockbook's documented v2 schema. Unlike the
 * Bitcoin Core models -- which were transcribed from Core's own source and are
 * checked by `verify-rpc-models.py` -- this schema could not be verified
 * against a live response, because the session that wrote it had no network
 * route to the host.
 *
 * Run `native/scripts/verify-blockbook-schema.py` against your endpoint before
 * relying on any of it. It fetches real responses and reports every field the
 * server sends that these classes do not model, and every field these classes
 * expect that the server does not send.
 */

/** Parses Blockbook's satoshi-string amounts exactly. */
object SatsStringSerializer : KSerializer<Sats> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SatsString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Sats {
        val text = when (val d = decoder as? JsonDecoder) {
            null -> decoder.decodeString()
            // Accept a bare number too: some Blockbook deployments and proxies
            // helpfully "fix" the strings into JSON numbers, and an app that
            // hard-fails on that is an app that breaks against a mirror.
            else -> (d.decodeJsonElement() as? JsonPrimitive)?.content ?: "0"
        }
        return Sats(text.trim().toLongOrNull() ?: 0L)
    }

    override fun serialize(encoder: Encoder, value: Sats) {
        encoder.encodeString(value.value.toString())
    }
}

@Serializable
data class BlockbookStatus(
    val blockbook: BlockbookServerInfo? = null,
    val backend: BlockbookBackendInfo? = null,
) {
    /**
     * True only when the indexer has actually caught up.
     *
     * Worth checking before every use: a Blockbook that is behind returns a
     * stale balance with no error at all, which is the worst possible failure
     * for a wallet -- confidently wrong rather than visibly broken.
     */
    val isUsable: Boolean get() = blockbook?.inSync == true
}

@Serializable
data class BlockbookServerInfo(
    val coin: String = "",
    val host: String = "",
    val version: String = "",
    val bestHeight: Long = 0,
    val lastBlockTime: String = "",
    val inSync: Boolean = false,
    val inSyncMempool: Boolean = false,
    val mempoolSize: Long = 0,
    val decimals: Int = 8,
)

@Serializable
data class BlockbookBackendInfo(
    val chain: String = "",
    val blocks: Long = 0,
    val headers: Long = 0,
    val bestBlockHash: String = "",
    val version: String = "",
    val subversion: String = "",
    val difficulty: String = "",
    val sizeOnDisk: Long = 0,
) {
    /** Blockbook reports "main"/"test"; Core's own RPC says "main"/"test". */
    val isMainnet: Boolean get() = chain == "main"
}

@Serializable
data class BlockbookAddress(
    val page: Int = 1,
    val totalPages: Int = 1,
    val itemsOnPage: Int = 0,
    val address: String = "",
    @Serializable(with = SatsStringSerializer::class)
    val balance: Sats = Sats.ZERO,
    @Serializable(with = SatsStringSerializer::class)
    val totalReceived: Sats = Sats.ZERO,
    @Serializable(with = SatsStringSerializer::class)
    val totalSent: Sats = Sats.ZERO,
    @Serializable(with = SatsStringSerializer::class)
    val unconfirmedBalance: Sats = Sats.ZERO,
    val unconfirmedTxs: Int = 0,
    val txs: Int = 0,
    val txids: List<String> = emptyList(),
    /** Populated only with ?details=txs */
    val transactions: List<BlockbookTx> = emptyList(),
) {
    /**
     * Confirmed balance only.
     *
     * Blockbook's `balance` is the confirmed balance and `unconfirmedBalance`
     * is a signed delta. They are reported separately here for the same reason
     * Core's `getbalances` separates them: presenting incoming unconfirmed
     * money as spendable produces "insufficient funds" on a figure the app
     * just displayed.
     */
    val confirmed: Sats get() = balance
    val total: Sats get() = balance + unconfirmedBalance
}

@Serializable
data class BlockbookTx(
    val txid: String = "",
    val version: Int = 0,
    val vin: List<BlockbookVin> = emptyList(),
    val vout: List<BlockbookVout> = emptyList(),
    val blockHash: String = "",
    val blockHeight: Long = -1,
    val confirmations: Long = 0,
    val blockTime: Long = 0,
    val size: Long = 0,
    val vsize: Long = 0,
    @Serializable(with = SatsStringSerializer::class)
    val value: Sats = Sats.ZERO,
    @Serializable(with = SatsStringSerializer::class)
    val valueIn: Sats = Sats.ZERO,
    @Serializable(with = SatsStringSerializer::class)
    val fees: Sats = Sats.ZERO,
    val hex: String? = null,
    val rbf: Boolean? = null,
) {
    val isConfirmed: Boolean get() = confirmations > 0

    /** Effective fee rate. Uses vsize, which is what the network charges on. */
    val feeRateSatPerVb: Double?
        get() = if (vsize > 0) fees.value.toDouble() / vsize else null

    /**
     * Net effect on a set of addresses: what arrived minus what left.
     *
     * Blockbook has no concept of "our wallet", so direction has to be derived.
     * This is the one place where using an explorer instead of a node costs
     * real fidelity: Core knows which outputs are ours because it holds the
     * descriptors, whereas here we can only match on the address strings we
     * happen to know about. Change outputs to addresses the app has not seen
     * will be misread as outgoing.
     */
    fun netFor(addresses: Set<String>): Sats {
        val received = vout
            .filter { it.addresses.any { a -> a in addresses } }
            .fold(Sats.ZERO) { acc, o -> acc + o.value }
        val spent = vin
            .filter { it.addresses.any { a -> a in addresses } }
            .fold(Sats.ZERO) { acc, i -> acc + i.value }
        return received - spent
    }
}

@Serializable
data class BlockbookVin(
    val txid: String = "",
    val vout: Int = 0,
    val sequence: Long = 0,
    val n: Int = 0,
    val addresses: List<String> = emptyList(),
    val isAddress: Boolean = false,
    @Serializable(with = SatsStringSerializer::class)
    val value: Sats = Sats.ZERO,
    val hex: String? = null,
    val isOwn: Boolean? = null,
)

@Serializable
data class BlockbookVout(
    @Serializable(with = SatsStringSerializer::class)
    val value: Sats = Sats.ZERO,
    val n: Int = 0,
    val hex: String? = null,
    val addresses: List<String> = emptyList(),
    val isAddress: Boolean = false,
    val spent: Boolean? = null,
    val spentTxId: String? = null,
    val isOwn: Boolean? = null,
)

@Serializable
data class BlockbookUtxo(
    val txid: String = "",
    val vout: Int = 0,
    @Serializable(with = SatsStringSerializer::class)
    val value: Sats = Sats.ZERO,
    val height: Long = -1,
    val confirmations: Long = 0,
    val lockTime: Long? = null,
    val coinbase: Boolean? = null,
    val address: String? = null,
    val path: String? = null,
) {
    val outpoint: String get() = "$txid:$vout"
    val isConfirmed: Boolean get() = confirmations > 0

    /**
     * Coinbase outputs are unspendable for 100 blocks. Blockbook reports the
     * flag but not the maturity; treating an immature coinbase as spendable
     * builds a transaction the network rejects.
     */
    val isMatureEnough: Boolean
        get() = coinbase != true || confirmations >= 100
}

/** `/api/v2/estimatefee/{blocks}` -> {"result": "0.00012000"} in BTC/kvB. */
@Serializable
data class BlockbookFeeResult(
    val result: String = "",
) {
    /**
     * Converted to sat/vB, the unit every fee UI actually uses.
     *
     * Note this one field is BTC-denominated, unlike every amount elsewhere in
     * the API. Parsed through BigDecimal rather than toDouble() to keep the
     * conversion exact.
     */
    val satPerVb: Double?
        get() = result.toBigDecimalOrNull()
            ?.multiply(java.math.BigDecimal(100_000_000))
            ?.divide(java.math.BigDecimal(1000))
            ?.toDouble()
            ?.takeIf { it > 0 }
}

/** `/api/v2/sendtx/{hex}` -> {"result": "<txid>"} or {"error": "..."}. */
@Serializable
data class BlockbookSendResult(
    val result: String? = null,
    val error: String? = null,
)

@Serializable
data class BlockbookBlock(
    val hash: String = "",
    val height: Long = 0,
    val confirmations: Long = 0,
    val size: Long = 0,
    val time: Long = 0,
    val version: Int = 0,
    val merkleRoot: String = "",
    val nonce: String = "",
    val bits: String = "",
    val difficulty: String = "",
    val txCount: Long = 0,
    val previousBlockHash: String? = null,
    val nextBlockHash: String? = null,
    val txs: List<BlockbookTx> = emptyList(),
)
