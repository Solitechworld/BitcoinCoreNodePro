package com.solitech.bitcoincorenode.data.explorer

import com.solitech.bitcoincorenode.core.model.Sats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-facing layer over a Blockbook explorer.
 *
 * Sits alongside `ChainRepository` and `WalletRepository` rather than behind a
 * shared interface with them, and that is a deliberate choice worth explaining,
 * because the obvious design is to hide both behind one `DataSource` interface
 * and let callers stay ignorant.
 *
 * They must not stay ignorant. A node and an explorer are not
 * interchangeable:
 *
 *  - The node **validates**. The explorer **asserts**. A balance from a node is
 *    a fact the device verified; a balance from an explorer is a claim by a
 *    server that could be wrong, stale, or lying.
 *  - The node knows which outputs are *ours* because it holds the descriptors.
 *    The explorer only matches address strings, so change to an address the app
 *    has not enumerated is misread.
 *  - The node can build and sign. The explorer cannot, ever.
 *
 * Hiding that behind one interface would let a screen render explorer data with
 * the same confidence as node data, and the difference is exactly what the user
 * needs to know. So callers choose explicitly, and the UI labels the source.
 */
@Singleton
class ExplorerRepository @Inject constructor(
    private val httpClientProvider: ExplorerHttpClientProvider,
) {
    private val _client = MutableStateFlow<BlockbookClient?>(null)
    val client: StateFlow<BlockbookClient?> = _client.asStateFlow()

    private val _lastStatus = MutableStateFlow<BlockbookStatus?>(null)
    val lastStatus: StateFlow<BlockbookStatus?> = _lastStatus.asStateFlow()

    val isConfigured: Boolean get() = _client.value != null

    fun configure(baseUrl: String, overTor: Boolean) {
        _client.value = if (baseUrl.isBlank()) null
        else BlockbookClient(baseUrl, httpClientProvider.client(overTor))
    }

    fun disable() {
        _client.value = null
        _lastStatus.value = null
    }

    private fun require(): BlockbookClient =
        _client.value ?: throw ExplorerError.Unreachable("no explorer configured", null)

    /**
     * Checks the server before trusting anything it says.
     *
     * Call this before a session of queries, not once at setup. A Blockbook can
     * fall behind at any time, and `inSync` going false is the only warning the
     * API gives — after that it keeps answering with stale data and HTTP 200.
     */
    suspend fun checkStatus(nodeHeightForComparison: Long? = null): BlockbookStatus {
        val status = require().status()
        _lastStatus.value = status

        if (!status.isUsable) {
            val behind = nodeHeightForComparison
                ?.minus(status.blockbook?.bestHeight ?: 0)
                ?.coerceAtLeast(0) ?: 0
            throw ExplorerError.OutOfSync(behind)
        }
        return status
    }

    suspend fun balance(address: String): AddressBalance {
        val r = require().address(address, details = "basic")
        return AddressBalance(
            address = r.address.ifBlank { address },
            confirmed = r.confirmed,
            unconfirmed = r.unconfirmedBalance,
            totalReceived = r.totalReceived,
            totalSent = r.totalSent,
            transactionCount = r.txs,
        )
    }

    /**
     * Balance and history for a whole extended public key.
     *
     * One request hands the server the user's entire wallet — every address it
     * has ever used and every one it ever will. The UI gates this behind its
     * own confirmation, separate from enabling the explorer at all, because
     * the privacy cost is categorically larger than a single address lookup.
     */
    suspend fun xpubSummary(xpub: String, includeTransactions: Boolean = false): AddressBalance {
        val r = require().xpub(xpub, details = if (includeTransactions) "txs" else "basic")
        return AddressBalance(
            address = xpub,
            confirmed = r.confirmed,
            unconfirmed = r.unconfirmedBalance,
            totalReceived = r.totalReceived,
            totalSent = r.totalSent,
            transactionCount = r.txs,
            transactions = r.transactions,
        )
    }

    suspend fun transactions(address: String, page: Int = 1, pageSize: Int = 50): List<BlockbookTx> =
        require().address(address, details = "txs", page = page, pageSize = pageSize).transactions

    suspend fun transaction(txid: String): BlockbookTx = require().transaction(txid)

    suspend fun utxos(addressOrXpub: String): List<BlockbookUtxo> =
        require().utxos(addressOrXpub)
            // Immature coinbase outputs are reported like any other UTXO.
            // Offering one as spendable builds a transaction the network
            // rejects with a confusing error.
            .filter { it.isMatureEnough }

    suspend fun feeEstimates(targets: List<Int> = listOf(1, 3, 6, 24)): Map<Int, Double> =
        buildMap {
            targets.forEach { blocks ->
                runCatching { require().estimateFee(blocks) }
                    .getOrNull()?.satPerVb?.let { put(blocks, it) }
            }
        }

    /**
     * Broadcasts a transaction that was built and signed by the node.
     *
     * Useful when the node is reachable enough to sign but has no peers yet —
     * during initial sync, or behind a hostile network. The explorer becomes a
     * relay of last resort.
     *
     * It also tells that server that this device originated the transaction,
     * which is a stronger privacy leak than any lookup: it links an IP to a
     * specific spend. The UI says so before offering it.
     */
    suspend fun broadcast(rawTxHex: String): String {
        val result = require().broadcast(rawTxHex)
        result.error?.let { throw ExplorerError.Http(400, "sendtx", it) }
        return result.result
            ?: throw ExplorerError.Malformed("sendtx", "no txid in response")
    }
}

data class AddressBalance(
    val address: String,
    val confirmed: Sats,
    val unconfirmed: Sats,
    val totalReceived: Sats,
    val totalSent: Sats,
    val transactionCount: Int,
    val transactions: List<BlockbookTx> = emptyList(),
) {
    val total: Sats get() = confirmed + unconfirmed
}

/** Supplies an HTTP client, optionally routed through Tor. */
interface ExplorerHttpClientProvider {
    fun client(overTor: Boolean): OkHttpClient
}
