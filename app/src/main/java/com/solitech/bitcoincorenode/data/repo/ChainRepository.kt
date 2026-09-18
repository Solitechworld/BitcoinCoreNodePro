package com.solitech.bitcoincorenode.data.repo

import com.solitech.bitcoincorenode.core.model.BitcoinJsonHelper
import com.solitech.bitcoincorenode.core.model.BlockVerbose
import com.solitech.bitcoincorenode.core.model.BlockchainInfo
import com.solitech.bitcoincorenode.core.model.ChainStates
import com.solitech.bitcoincorenode.core.model.FeeEstimate
import com.solitech.bitcoincorenode.core.model.MempoolInfo
import com.solitech.bitcoincorenode.core.model.NetTotals
import com.solitech.bitcoincorenode.core.model.NetworkInfo
import com.solitech.bitcoincorenode.core.model.PeerInfo
import com.solitech.bitcoincorenode.core.rpc.RpcClient
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.core.rpc.RpcProvider
import com.solitech.bitcoincorenode.core.rpc.params
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the app knows about the chain, the mempool and the network.
 *
 * Two performance rules are load-bearing here and worth stating up front,
 * because breaking either turns a smooth dashboard into a device that gets
 * warm in your hand:
 *
 * 1. **One round trip per refresh.** The dashboard needs five separate facts.
 *    Five sequential RPC calls over loopback is five JSON round trips and five
 *    lock acquisitions inside the node; a batch is one of each.
 * 2. **Long-poll rather than spin.** `waitfornewblock` blocks server-side until
 *    the tip actually moves. One idle thread and zero wasted work, instead of a
 *    timer waking the CPU every two seconds to be told nothing happened. Blocks
 *    arrive every ten minutes on average — polling for them is almost entirely
 *    waste.
 */
@Singleton
class ChainRepository @Inject constructor(
    private val rpcProvider: RpcProvider,
) {
    private val rpc: RpcClient
        get() = rpcProvider.active.value ?: throw RpcError.NotReachable("no node selected", null)

    /** A dashboard's worth of state, in one round trip. */
    suspend fun snapshot(): ChainSnapshot {
        val results = rpc.batch(
            listOf(
                "getblockchaininfo" to JsonArray(emptyList()),
                "getnetworkinfo" to JsonArray(emptyList()),
                "getmempoolinfo" to JsonArray(emptyList()),
                "getnettotals" to JsonArray(emptyList()),
                "getchainstates" to JsonArray(emptyList()),
                // Peers ride along because header presync is only observable
                // here: during presync getblockchaininfo's `headers` stays at
                // the last committed value (the presync tree is deliberately
                // separate), so without this the sync screen reports 0% and a
                // 2009 date while Core's GUI shows "Pre-syncing Headers".
                "getpeerinfo" to JsonArray(emptyList()),
            )
        )

        // A failure in any one call must not lose the other four. A node with
        // the mempool disabled still has a perfectly good chain tip to show.
        fun <T> at(i: Int, s: kotlinx.serialization.DeserializationStrategy<T>, m: String): T? =
            results.getOrNull(i)?.getOrNull()?.let {
                runCatching { BitcoinJsonHelper.decode(s, it, m) }.getOrNull()
            }

        val chain = at(0, BlockchainInfo.serializer(), "getblockchaininfo")
            ?: throw results[0].exceptionOrNull() as? RpcError
                ?: RpcError.Malformed("getblockchaininfo", "no result")

        return ChainSnapshot(
            blockchain = chain,
            network = at(1, NetworkInfo.serializer(), "getnetworkinfo"),
            mempool = at(2, MempoolInfo.serializer(), "getmempoolinfo"),
            netTotals = at(3, NetTotals.serializer(), "getnettotals"),
            chainStates = at(4, ChainStates.serializer(), "getchainstates"),
            peers = at(5, ListSerializer(PeerInfo.serializer()), "getpeerinfo"),
        )
    }

    suspend fun peers(): List<PeerInfo> =
        BitcoinJsonHelper.decodeList(PeerInfo.serializer(), rpc.call("getpeerinfo"), "getpeerinfo")

    suspend fun block(hash: String): BlockVerbose =
        BitcoinJsonHelper.decode(
            BlockVerbose.serializer(),
            rpc.call("getblock", params { add(hash); add(1) }),
            "getblock",
        )

    suspend fun blockHashAt(height: Long): String =
        rpc.call("getblockhash", params { add(height) }).toString().trim('"')

    /**
     * Fee estimates for the horizons the send screen offers.
     *
     * `estimatesmartfee` legitimately returns *no* estimate on a node that has
     * not seen enough mempool history — a fresh node, or one running
     * `blocksonly`. The UI has to handle that honestly by saying it cannot
     * estimate, rather than substituting a plausible-looking number, because a
     * made-up fee rate is how a transaction gets stuck for a week.
     */
    suspend fun feeEstimates(targets: List<Int> = listOf(1, 3, 6, 24)): Map<Int, FeeEstimate> {
        val results = rpc.batch(targets.map { t -> "estimatesmartfee" to params { add(t) } })
        return targets.zip(results).mapNotNull { (target, result) ->
            result.getOrNull()
                ?.let { runCatching { BitcoinJsonHelper.decode(FeeEstimate.serializer(), it) }.getOrNull() }
                ?.let { target to it }
        }.toMap()
    }

    suspend fun rawMempoolTxids(): List<String> =
        BitcoinJsonHelper.decodeList(
            String.serializer(),
            rpc.call("getrawmempool", params { add(false) }),
            "getrawmempool",
        )

    /**
     * Emits once per new block.
     *
     * `waitfornewblock` is a hidden RPC (present in 30.3 at
     * rpc/blockchain.cpp:265) and a remote node may have it disabled or be
     * running something that never had it. So this degrades to adaptive
     * polling rather than failing — a slower path, but never a dead screen.
     */
    fun newBlocks(): Flow<Long> = flow {
        var pollInterval = 2_000L
        var supportsWait = true
        var lastHeight = -1L

        while (true) {
            try {
                if (supportsWait) {
                    val result = rpc.call(
                        "waitfornewblock",
                        params { add(60_000) },       // 60s server-side timeout
                        timeout = 90.seconds,
                    )
                    val height = runCatching {
                        result.toString().substringAfter("\"height\":").takeWhile { it.isDigit() }.toLong()
                    }.getOrNull()
                    if (height != null && height != lastHeight) {
                        lastHeight = height
                        emit(height)
                    }
                } else {
                    val info = BitcoinJsonHelper.decode(
                        BlockchainInfo.serializer(), rpc.call("getblockchaininfo")
                    )
                    if (info.blocks != lastHeight) {
                        lastHeight = info.blocks
                        emit(info.blocks)
                        pollInterval = 2_000L
                    } else {
                        // Back off toward 30s while nothing is happening.
                        pollInterval = (pollInterval * 2).coerceAtMost(30_000L)
                    }
                    delay(pollInterval)
                }
            } catch (e: RpcError.Rpc) {
                if (e.kind == RpcError.Rpc.Kind.OTHER || e.code == -32601) {
                    supportsWait = false        // method not found; fall back
                } else {
                    delay(5_000)
                }
            } catch (e: RpcError) {
                delay(if (e.isTransient) 3_000 else 10_000)
            }
        }
    }
}

data class ChainSnapshot(
    val blockchain: BlockchainInfo,
    val network: NetworkInfo?,
    val mempool: MempoolInfo?,
    val netTotals: NetTotals?,
    val chainStates: ChainStates?,
    val peers: List<PeerInfo>? = null,
) {
    /**
     * The header-presync height Core is working on right now, or -1 when no
     * low-work header sync is running. Peers not participating report -1, so
     * the max across peers is the number to show.
     */
    val headerPresyncHeight: Long
        get() = peers?.maxOfOrNull { it.presyncedHeaders } ?: -1L
}
