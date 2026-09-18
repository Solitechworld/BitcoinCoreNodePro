package com.solitech.bitcoincorenode

import com.solitech.bitcoincorenode.core.model.BitcoinNetwork
import com.solitech.bitcoincorenode.core.model.BlockchainInfo
import com.solitech.bitcoincorenode.core.model.PeerInfo
import com.solitech.bitcoincorenode.core.rpc.BitcoinJson
import com.solitech.bitcoincorenode.data.repo.ChainSnapshot
import com.solitech.bitcoincorenode.data.repo.ChainRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header-presync phase, against Core's own numbers.
 *
 * A fresh node first runs a rate-limited low-work header sync before
 * committing headers and downloading blocks. During that phase
 * getblockchaininfo's `blocks` and `headers` both sit near zero (the presync
 * tree is deliberately separate from the block index), which is why a UI
 * keyed only on those fields reports "0% / January 2009" while Core's GUI
 * says "Unknown. Pre-syncing Headers (104000, 11.2%)…" — the screenshot
 * this app's behaviour is now matched against. The presync height is only
 * visible per-peer, as `presynced_headers` in getpeerinfo (28.3
 * rpc/net.cpp).
 */
class HeaderPresyncTest {

    /** getpeerinfo shape during presync — one peer mid-presync, one idle. */
    private val peersJson = """
        [
          {
            "id": 1, "addr": "91.198.113.10:8333", "network": "ipv4",
            "services": "0000000000000000000000000000000000000000000000000000000000001c0d",
            "servicesnames": ["NETWORK", "BLOOM", "WITNESS", "COMPACT_FILTERS", "NETWORK_LIMITED"],
            "lastsend": 1788889200, "lastrecv": 1788889200,
            "last_transaction": 0, "last_block": 1788889100,
            "bytessent": 1024, "bytesrecv": 204800, "conntime": 1788885000,
            "timeoffset": 0, "pingtime": 0.045, "minping": 0.041, "pingwait": 0,
            "version": 70016, "subver": "/Satoshi:28.3.0/", "inbound": false,
            "startingheight": 929777, "presynced_headers": 104000,
            "synced_headers": -1, "synced_blocks": -1,
            "minfeefilter": 0.00001000, "connection_type": "outbound-full-relay",
            "transport_protocol_type": "v1", "bip152_hb_to": false, "bip152_hb_from": false
          },
          {
            "id": 2, "addr": "10.0.0.2:8333", "network": "ipv4",
            "services": "0000000000000000000000000000000000000000000000000000000000001c0d",
            "servicesnames": ["NETWORK", "WITNESS"],
            "lastsend": 1788889200, "lastrecv": 1788889200,
            "last_transaction": 0, "last_block": 0,
            "bytessent": 512, "bytesrecv": 1024, "conntime": 1788886000,
            "timeoffset": 0, "pingtime": 0.120, "minping": 0.118,
            "version": 70016, "subver": "/Satoshi:28.3.0/", "inbound": true,
            "startingheight": 929700, "presynced_headers": -1,
            "synced_headers": -1, "synced_blocks": -1,
            "minfeefilter": 0.00001000, "connection_type": "inbound",
            "transport_protocol_type": "v1", "bip152_hb_to": false, "bip152_hb_from": false
          }
        ]
    """.trimIndent()

    @Test
    fun presyncHeightIsMaxAcrossPeers() {
        val peers = BitcoinJson.decodeFromJsonElement(
            ListSerializer(PeerInfo.serializer()), BitcoinJson.parseToJsonElement(peersJson),
        )
        // -1 means "not presyncing" for that peer; the max is the live number.
        assertEquals(104000L, peers.maxOf { it.presyncedHeaders })
        assertEquals(-1L, peers[1].presyncedHeaders)
    }

    @Test
    fun snapshotExposesPresyncHeight() {
        val peers = BitcoinJson.decodeFromJsonElement(
            ListSerializer(PeerInfo.serializer()), BitcoinJson.parseToJsonElement(peersJson),
        )
        val snapshot = ChainSnapshot(
            blockchain = presyncEraChain(),
            network = null, mempool = null, netTotals = null, chainStates = null,
            peers = peers,
        )
        assertEquals(104000L, snapshot.headerPresyncHeight)
        assertEquals(
            -1L,
            snapshot.copy(peers = emptyList()).headerPresyncHeight,
        )
    }

    /**
     * Core's percentage for the same moment reads 11.2% (screenshot,
     * 8 Sep 2026). The formula is presync_height / headers-a-full-chain-
     * has-now, nominal 10-minute spacing from genesis — qt/modaloverlay.cpp
     * computes it from the presync tip's date, which over RPC we only have
     * as nominal spacing; on a chain this young the two agree.
     */
    @Test
    fun presyncPercentMatchesCoresElevenPointTwo() {
        val info = presyncEraChain()
        val pct = info.presyncPercent(104000L, nowEpochSeconds = 1788900000L)!!
        assertEquals(11.2, Math.round(pct * 10.0) / 10.0, 0.05)
        assertTrue(pct in 11.0..11.4)
        assertNull(info.presyncPercent(-1, 1788900000L))
    }

    /** The lying numbers the old UI rendered during presync, documented. */
    @Test
    fun duringPresyncBlockchainInfoLooksSyncedAtZero() {
        val info = presyncEraChain()
        assertEquals(0L, info.blocks)
        assertEquals(0L, info.headers)
        assertEquals(0L, info.blocksBehind)
        assertEquals(0.0, info.progressPercent, 1e-9)
    }

    private fun presyncEraChain() = BlockchainInfo(
        chain = "main",
        blocks = 0,
        headers = 0,
        bestblockhash = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
        mediantime = 1231006505,
        initialblockdownload = true,
    )
}
