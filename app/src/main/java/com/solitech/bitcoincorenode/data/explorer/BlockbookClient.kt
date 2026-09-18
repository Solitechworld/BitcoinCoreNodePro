package com.solitech.bitcoincorenode.data.explorer

import com.solitech.bitcoincorenode.core.model.BitcoinJsonHelper
import com.solitech.bitcoincorenode.core.rpc.BitcoinJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * A client for a Blockbook v2 indexer.
 *
 * ## What this is for, and what it is not
 *
 * This is a **read-only data source plus a broadcast endpoint**. It exists so
 * the app can show balances and history when the embedded node is still
 * syncing, or when the user has no node at all.
 *
 * It is **not** a wallet backend, and it cannot become one. Building and
 * signing a transaction requires private keys, descriptor knowledge and a
 * signing implementation — all of which live in Bitcoin Core in this app, by
 * deliberate design (see `WalletRepository`'s class comment). An explorer can
 * tell you what you own and can put a finished transaction on the wire. It
 * cannot produce one.
 *
 * So the honest capability matrix is:
 *
 * | Operation            | Node | Blockbook |
 * |----------------------|------|-----------|
 * | Balance, history     | yes  | yes       |
 * | UTXO set             | yes  | yes       |
 * | Fee estimates        | yes  | yes       |
 * | Broadcast a signed tx| yes  | yes       |
 * | Build a transaction  | yes  | **no**    |
 * | Sign                 | yes  | **no**    |
 * | Validate consensus   | yes  | **no**    |
 *
 * ## The privacy cost, stated plainly
 *
 * Every request here hands one of the user's Bitcoin addresses to a third-party
 * server, from the user's IP address. That server learns which addresses belong
 * to one person, and can link them to a device and a rough location. On a
 * public ledger that linkage is permanent.
 *
 * This is the single biggest privacy regression available in this app, which is
 * why it is opt-in, off by default, disclosed in the UI in those words, and
 * routed over Tor when Tor is enabled.
 *
 * ## Trust
 *
 * A Blockbook server can lie. It can under-report a balance, omit a
 * transaction, or report a confirmation that never happened. Nothing here
 * validates anything against consensus rules — that is what the node is for.
 * Treat explorer-sourced figures as advisory, and never as the basis for
 * believing a payment was received. The UI labels them accordingly.
 */
class BlockbookClient(
    baseUrl: String,
    private val httpClient: OkHttpClient,
) {
    /**
     * Normalised to end at `/api/v2`, accepting whatever the user pasted.
     *
     * People paste the URL they were given, which in practice is any of
     * `https://host`, `https://host/`, `https://host/api/v2`, or the full
     * `https://host/api/v2/address/{address}` template from the docs. All four
     * should work rather than producing a 404 the user has to debug.
     */
    private val base: String = normalise(baseUrl)

    val host: String get() = base.toHttpUrlOrNull()?.host ?: base

    companion object {
        private val PLAIN_TEXT = "text/plain".toMediaType()

        fun normalise(input: String): String {
            var s = input.trim().removeSuffix("/")
            // Strip a pasted path template such as /address/{address}
            listOf("/address/{address}", "/address/", "/xpub/{xpub}", "/tx/{txid}").forEach {
                if (s.endsWith(it)) s = s.removeSuffix(it)
            }
            s = s.substringBefore("/address/").substringBefore("/xpub/").substringBefore("/tx/")
            s = s.removeSuffix("/")
            if (!s.endsWith("/api/v2")) {
                s = s.removeSuffix("/api").removeSuffix("/") + "/api/v2"
            }
            return s
        }
    }

    // -- endpoints -----------------------------------------------------------

    suspend fun status(): BlockbookStatus =
        get(base, BlockbookStatus.serializer(), "status")

    /**
     * @param details "basic" (balance only), "txids", or "txs" (full objects).
     *   Defaults to basic: fetching full transactions for an address with a
     *   long history is a large response, and most screens only need a balance.
     */
    suspend fun address(
        address: String,
        details: String = "basic",
        page: Int = 1,
        pageSize: Int = 100,
    ): BlockbookAddress = get(
        "$base/address/$address?details=$details&page=$page&pageSize=$pageSize",
        BlockbookAddress.serializer(),
        "address",
    )

    /**
     * Balance and history for an entire extended public key.
     *
     * This is what makes Blockbook genuinely useful to a wallet rather than an
     * address lookup toy: it derives and gap-limit-scans the whole key server
     * side. It is also the most privacy-destroying single request the app can
     * make — one xpub hands the server the user's *entire* wallet, past and
     * future, in one call. The UI requires a separate explicit confirmation for
     * this specific operation, over and above enabling the explorer at all.
     */
    suspend fun xpub(
        xpub: String,
        details: String = "basic",
        gap: Int = 20,
    ): BlockbookAddress = get(
        "$base/xpub/$xpub?details=$details&gap=$gap",
        BlockbookAddress.serializer(),
        "xpub",
    )

    suspend fun transaction(txid: String): BlockbookTx =
        get("$base/tx/$txid", BlockbookTx.serializer(), "tx")

    suspend fun utxos(addressOrXpub: String, confirmed: Boolean = false): List<BlockbookUtxo> =
        getList(
            "$base/utxo/$addressOrXpub?confirmed=$confirmed",
            BlockbookUtxo.serializer(),
            "utxo",
        )

    suspend fun estimateFee(blocks: Int): BlockbookFeeResult =
        get("$base/estimatefee/$blocks", BlockbookFeeResult.serializer(), "estimatefee")

    suspend fun block(hashOrHeight: String): BlockbookBlock =
        get("$base/block/$hashOrHeight", BlockbookBlock.serializer(), "block")

    /**
     * Broadcasts a signed raw transaction.
     *
     * Uses POST rather than the GET form. The GET variant puts the entire
     * transaction hex in the URL, where it lands in the server's access log and
     * in any proxy between here and there. For a transaction that is about to
     * be public anyway that is a small leak, but it also breaks outright on
     * large transactions: URLs have length limits and a multi-input spend does
     * not fit.
     */
    suspend fun broadcast(rawTxHex: String): BlockbookSendResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/sendtx/")
            .post(rawTxHex.toRequestBody(PLAIN_TEXT))
            .header("Content-Type", "text/plain")
            .build()
        val body = execute(request, "sendtx")
        BitcoinJsonHelper.decode(BlockbookSendResult.serializer(), BitcoinJson.parseToJsonElement(body), "sendtx")
    }

    // -- plumbing ------------------------------------------------------------

    private suspend fun <T> get(
        url: String,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        label: String,
    ): T = withContext(Dispatchers.IO) {
        val body = execute(Request.Builder().url(url).get().build(), label)
        BitcoinJsonHelper.decode(serializer, BitcoinJson.parseToJsonElement(body), label)
    }

    private suspend fun <T> getList(
        url: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        label: String,
    ): List<T> = withContext(Dispatchers.IO) {
        val body = execute(Request.Builder().url(url).get().build(), label)
        BitcoinJsonHelper.decode(ListSerializer(serializer), BitcoinJson.parseToJsonElement(body), label)
    }

    private fun execute(request: Request, label: String): String {
        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ExplorerError.Http(response.code, label, text.take(300))
                }
                if (text.isBlank()) throw ExplorerError.Malformed(label, "empty response body")
                return text
            }
        } catch (e: ExplorerError) {
            throw e
        } catch (e: IOException) {
            throw ExplorerError.Unreachable(host, e)
        }
    }

}

/** Failure modes of a third-party explorer, kept distinct from RpcError. */
sealed class ExplorerError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class Unreachable(val host: String, cause: Throwable?) :
        ExplorerError("Cannot reach $host", cause)

    class Http(val code: Int, val endpoint: String, val body: String) :
        ExplorerError("$endpoint returned HTTP $code")

    class Malformed(val endpoint: String, val detail: String, cause: Throwable? = null) :
        ExplorerError("$endpoint returned something unexpected: $detail", cause)

    /**
     * The indexer is behind the chain.
     *
     * Deliberately its own case. A lagging Blockbook returns a stale balance
     * with HTTP 200 and no warning, and a wallet that shows a confidently wrong
     * balance is worse than one that shows an error.
     */
    class OutOfSync(val behindBlocks: Long) :
        ExplorerError("The explorer is $behindBlocks blocks behind and its data is stale")

    fun userMessage(): String = when (this) {
        is Unreachable -> "Can't reach the block explorer. Check your connection, or switch back to your node."
        is Http -> when (code) {
            400 -> "The explorer rejected that request."
            404 -> "The explorer has no record of that."
            429 -> "The explorer is rate-limiting this device. Wait a moment, or use your own node."
            in 500..599 -> "The explorer is having problems. This is on their end, not yours."
            else -> "The explorer returned an error ($code)."
        }
        is Malformed -> "The explorer returned data this app doesn't recognise. It may be running a different version."
        is OutOfSync -> "The explorer is $behindBlocks blocks behind, so these figures are out of date."
    }
}
