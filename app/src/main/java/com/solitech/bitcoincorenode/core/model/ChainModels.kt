package com.solitech.bitcoincorenode.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain models for Bitcoin Core's chain and network RPCs.
 *
 * Field names are transcribed from Bitcoin Core 30.3's own RPCResult
 * declarations, not from documentation or memory:
 *   getblockchaininfo -> src/rpc/blockchain.cpp
 *   getchainstates    -> src/rpc/blockchain.cpp
 *   getmempoolinfo    -> src/rpc/mempool.cpp
 *   getnetworkinfo    -> src/rpc/net.cpp
 *   getpeerinfo       -> src/rpc/net.cpp
 *
 * If you upgrade Core, re-derive them rather than assume. A silently renamed
 * field becomes a default value in a data class and then a wrong number on a
 * screen -- which is worse than a crash, because nobody notices.
 *
 * Every non-essential field is nullable or defaulted. Core adds fields between
 * releases and omits others depending on configuration (pruning, signet,
 * deprecation flags), and this app must work against a range of node versions.
 */

@Serializable
data class BlockchainInfo(
    val chain: String,
    val blocks: Long,
    val headers: Long,
    val bestblockhash: String,
    val difficulty: Double = 0.0,
    val time: Long = 0,
    val mediantime: Long = 0,
    val verificationprogress: Double = 0.0,
    val initialblockdownload: Boolean = true,
    val chainwork: String = "",
    @SerialName("size_on_disk") val sizeOnDisk: Long = 0,
    val pruned: Boolean = false,
    val pruneheight: Long? = null,
    @SerialName("automatic_pruning") val automaticPruning: Boolean? = null,
    @SerialName("prune_target_size") val pruneTargetSize: Long? = null,
    // Added to getblockchaininfo in Core 29. Absent on 28.x, which is what this
    // app currently ships -- hence nullable, and hence listed in the verifier's
    // VERSION_CONDITIONAL table so it reports "expected absent" rather than a
    // defect. Do NOT make these non-nullable to silence anything: on 28.x they
    // genuinely never arrive.
    val bits: String? = null,
    val target: String? = null,
    @Serializable(with = WarningsSerializer::class)
    val warnings: List<String> = emptyList(),
) {
    val network: BitcoinNetwork get() = BitcoinNetwork.fromChainName(chain)

    /**
     * verificationprogress is exponential near the tip: it reads 0.9999 while
     * the node still has thousands of blocks to go, which parks a naive
     * progress bar at "100%" for a long, confusing stretch. The UI pairs this
     * with [blocksBehind] and prefers the block count once close.
     */
    val progressPercent: Double get() = (verificationprogress * 100.0).coerceIn(0.0, 100.0)

    val blocksBehind: Long get() = (headers - blocks).coerceAtLeast(0)

    val isEffectivelySynced: Boolean get() = !initialblockdownload && blocksBehind == 0L

    /**
     * Core GUI's header-presync percentage, reproduced (qt/modaloverlay.cpp:
     * `100.0 / (height + est_headers_left) * height` with
     * `est_headers_left = (now − tip_date) / 600`). The presync tip's date is
     * not visible over RPC, but on a young chain it sits at nominal spacing
     * from genesis, so headers-so-far over full-chain-right-now is the same
     * number to the tenth of a percent.
     */
    fun presyncPercent(presyncHeight: Long, nowEpochSeconds: Long): Double? {
        if (presyncHeight <= 0) return null
        val estTotalHeaders = (nowEpochSeconds - network.genesisTime) / 600 + 1
        if (estTotalHeaders <= 0) return null
        return presyncHeight.toDouble() / estTotalHeaders * 100.0
    }
}

enum class BitcoinNetwork(
    val chainName: String,
    val label: String,
    val defaultRpcPort: Int,
    /** Genesis block time — anchors the expected-chain-length estimate. */
    val genesisTime: Long,
) {
    MAIN("main", "mainnet", 8332, 1231006505),
    TEST("test", "testnet3", 18332, 1296688602),
    TEST4("testnet4", "testnet4", 48332, 1714777860),
    SIGNET("signet", "signet", 38332, 1598918400),
    REGTEST("regtest", "regtest", 18443, 1296688602);

    val isRealMoney: Boolean get() = this == MAIN

    companion object {
        fun fromChainName(name: String): BitcoinNetwork =
            entries.firstOrNull { it.chainName == name } ?: MAIN
    }
}

/**
 * `getchainstates` -- the assumeutxo dual-chainstate view.
 *
 * While a snapshot is loaded there are two chainstates: the snapshot one,
 * already at the tip and usable, and the background one validating from
 * genesis. The UI shows both, because "usable now, still verifying history"
 * is the honest description of that state, and collapsing it into one bar
 * would misrepresent the trust model to the user.
 */
@Serializable
data class ChainStates(
    val headers: Long = 0,
    val chainstates: List<ChainStateEntry> = emptyList(),
) {
    val snapshot: ChainStateEntry? get() = chainstates.firstOrNull { it.snapshotBlockhash != null }
    val background: ChainStateEntry? get() = chainstates.firstOrNull { it.snapshotBlockhash == null }
    val isDualChainstate: Boolean get() = chainstates.size > 1
}

@Serializable
data class ChainStateEntry(
    val blocks: Long = 0,
    val bestblockhash: String = "",
    val difficulty: Double = 0.0,
    val verificationprogress: Double = 0.0,
    @SerialName("snapshot_blockhash") val snapshotBlockhash: String? = null,
    @SerialName("coins_db_cache_bytes") val coinsDbCacheBytes: Long? = null,
    @SerialName("coins_tip_cache_bytes") val coinsTipCacheBytes: Long? = null,
    val validated: Boolean? = null,
)

@Serializable
data class MempoolInfo(
    val loaded: Boolean = false,
    val size: Long = 0,
    val bytes: Long = 0,
    val usage: Long = 0,
    @SerialName("total_fee") val totalFee: Double = 0.0,
    val maxmempool: Long = 0,
    val mempoolminfee: Double = 0.0,
    val minrelaytxfee: Double = 0.0,
    val incrementalrelayfee: Double = 0.0,
    val unbroadcastcount: Long = 0,
    val fullrbf: Boolean? = null,
) {
    val usageFraction: Float
        get() = if (maxmempool > 0) (usage.toFloat() / maxmempool.toFloat()).coerceIn(0f, 1f) else 0f

    /** sat/vB floor for anything this node will relay. */
    val minRelaySatPerVb: Double get() = mempoolminfee * 100_000_000.0 / 1000.0
}

@Serializable
data class NetworkInfo(
    val version: Int = 0,
    val subversion: String = "",
    val protocolversion: Int = 0,
    val localrelay: Boolean = false,
    val timeoffset: Long = 0,
    val connections: Int = 0,
    @SerialName("connections_in") val connectionsIn: Int = 0,
    @SerialName("connections_out") val connectionsOut: Int = 0,
    val networkactive: Boolean = true,
    val networks: List<NetworkEntry> = emptyList(),
    val relayfee: Double = 0.0,
    val incrementalfee: Double = 0.0,
    val localaddresses: List<LocalAddress> = emptyList(),
    @Serializable(with = WarningsSerializer::class)
    val warnings: List<String> = emptyList(),
) {
    /** Core's subversion looks like "/Satoshi:30.3.0/". Pull the number out. */
    val coreVersionName: String
        get() = Regex("""Satoshi:([0-9.]+)""").find(subversion)?.groupValues?.get(1) ?: "unknown"

    val torReachable: Boolean get() = networks.any { it.name == "onion" && it.reachable }
}

@Serializable
data class NetworkEntry(
    val name: String = "",
    val limited: Boolean = false,
    val reachable: Boolean = false,
    val proxy: String = "",
    @SerialName("proxy_randomize_credentials") val proxyRandomizeCredentials: Boolean = false,
)

@Serializable
data class LocalAddress(
    val address: String = "",
    val port: Int = 0,
    val score: Int = 0,
)

@Serializable
data class PeerInfo(
    val id: Long = 0,
    val addr: String = "",
    val addrlocal: String? = null,
    val network: String = "",
    @SerialName("mapped_as") val mappedAs: Long? = null,
    val services: String = "",
    val servicesnames: List<String> = emptyList(),
    val relaytxes: Boolean? = null,
    val lastsend: Long = 0,
    val lastrecv: Long = 0,
    @SerialName("last_transaction") val lastTransaction: Long = 0,
    @SerialName("last_block") val lastBlock: Long = 0,
    val bytessent: Long = 0,
    val bytesrecv: Long = 0,
    val conntime: Long = 0,
    val timeoffset: Long = 0,
    val pingtime: Double? = null,
    val minping: Double? = null,
    val pingwait: Double? = null,
    val version: Int = 0,
    val subver: String = "",
    val inbound: Boolean = false,
    val startingheight: Long = 0,
    /**
     * Height of header pre-synchronization with this peer, or -1 when no
     * low-work header sync is in progress (rpc/net.cpp: "The current height
     * of header pre-synchronization with this peer"). The max across peers
     * is the number Core's GUI prints as "Pre-syncing Headers (N, P%)".
     */
    @SerialName("presynced_headers") val presyncedHeaders: Long = -1,
    @SerialName("synced_headers") val syncedHeaders: Long = -1,
    @SerialName("synced_blocks") val syncedBlocks: Long = -1,
    val permissions: List<String> = emptyList(),
    val minfeefilter: Double = 0.0,
    @SerialName("connection_type") val connectionType: String = "",
    @SerialName("transport_protocol_type") val transportProtocolType: String = "",
    @SerialName("bip152_hb_to") val bip152HbTo: Boolean = false,
    @SerialName("bip152_hb_from") val bip152HbFrom: Boolean = false,
) {
    fun ageSeconds(nowEpochSeconds: Long): Long = (nowEpochSeconds - conntime).coerceAtLeast(0)

    val pingMillis: Long? get() = pingtime?.let { (it * 1000).toLong() }

    /** BIP324 v2 encrypted transport. Worth surfacing -- it is still the minority. */
    val isEncryptedTransport: Boolean get() = transportProtocolType.equals("v2", ignoreCase = true)

    val networkKind: PeerNetwork get() = PeerNetwork.fromName(network)

    /** Core's connection_type, as something a human can read. */
    val roleLabel: String get() = when (connectionType) {
        "outbound-full-relay" -> "Full relay"
        "block-relay-only" -> "Block relay"
        "manual" -> "Manual"
        "feeler" -> "Feeler"
        "addr-fetch" -> "Addr fetch"
        "inbound" -> "Inbound"
        else -> connectionType.ifBlank { "-" }
    }
}

enum class PeerNetwork(val rpcName: String, val label: String) {
    IPV4("ipv4", "IPv4"),
    IPV6("ipv6", "IPv6"),
    ONION("onion", "Tor"),
    I2P("i2p", "I2P"),
    CJDNS("cjdns", "CJDNS"),
    NOT_PUBLICLY_ROUTABLE("not_publicly_routable", "Local"),
    UNKNOWN("", "Unknown");

    companion object {
        fun fromName(n: String) = entries.firstOrNull { it.rpcName == n } ?: UNKNOWN
    }
}

@Serializable
data class NetTotals(
    val totalbytesrecv: Long = 0,
    val totalbytessent: Long = 0,
    val timemillis: Long = 0,
)

@Serializable
data class BlockHeader(
    val hash: String,
    val confirmations: Long = 0,
    val height: Long = 0,
    val version: Int = 0,
    val merkleroot: String = "",
    val time: Long = 0,
    val mediantime: Long = 0,
    val nonce: Long = 0,
    val bits: String = "",
    val difficulty: Double = 0.0,
    val chainwork: String = "",
    @SerialName("nTx") val txCount: Long = 0,
    val previousblockhash: String? = null,
    val nextblockhash: String? = null,
)

@Serializable
data class BlockVerbose(
    val hash: String,
    val confirmations: Long = 0,
    val size: Long = 0,
    val strippedsize: Long = 0,
    val weight: Long = 0,
    val height: Long = 0,
    val version: Int = 0,
    val merkleroot: String = "",
    val tx: List<String> = emptyList(),
    val time: Long = 0,
    val mediantime: Long = 0,
    val nonce: Long = 0,
    val bits: String = "",
    val difficulty: Double = 0.0,
    val chainwork: String = "",
    @SerialName("nTx") val txCount: Long = 0,
    val previousblockhash: String? = null,
    val nextblockhash: String? = null,
) {
    /** Weight as a fraction of the 4 000 000 WU consensus limit. */
    val fullness: Float get() = (weight / 4_000_000f).coerceIn(0f, 1f)
}

@Serializable
data class FeeEstimate(
    val feerate: Double? = null,
    val errors: List<String> = emptyList(),
    val blocks: Int = 0,
) {
    /** Core reports BTC/kvB. Humans, and every fee UI ever built, think sat/vB. */
    val satPerVb: Double? get() = feerate?.let { it * 100_000_000.0 / 1000.0 }
}
