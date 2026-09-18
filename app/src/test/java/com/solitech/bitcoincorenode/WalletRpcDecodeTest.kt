package com.solitech.bitcoincorenode

import com.solitech.bitcoincorenode.core.model.Balances
import com.solitech.bitcoincorenode.core.model.WalletInfo
import com.solitech.bitcoincorenode.core.model.WalletTx
import com.solitech.bitcoincorenode.core.rpc.BitcoinJson
import com.solitech.bitcoincorenode.data.repo.WalletRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding against REAL Bitcoin Core JSON.
 *
 * Every payload below was captured live from a bitcoind regtest node
 * (created wallet, mined coinbase, made a spend, then invalidated blocks to
 * reproduce a node whose chain is behind the wallet's records — the exact
 * situation of a phone importing a wallet.dat from a synced desktop node).
 * If a future model change breaks one of these, the app would show empty
 * history or zero balances on real nodes; the test exists to catch that
 * before any device does.
 */
class WalletRpcDecodeTest {

    private fun decodeWalletTx(json: String): WalletTx =
        BitcoinJson.decodeFromJsonElement(WalletTx.serializer(), JsonPrimitive(json).let {
            // parse the raw text as a JSON element tree first
            BitcoinJson.parseToJsonElement(json)
        })

    /** Coinbase/immature entry, as captured from listtransactions. */
    private val generateEntry = """
        {
          "address": "bcrt1qx3tjx5yfghjra09n7dx8ackw0nzp39nzh73afr",
          "parent_descs": ["wpkh([3d87d24e/84h/1h/0h]tpub.../0/*)#q62jlrd8"],
          "category": "immature",
          "amount": 50.00000000,
          "label": "",
          "vout": 0,
          "abandoned": false,
          "confirmations": 10,
          "generated": true,
          "blockhash": "6817c29b34de7aab420dc10240ede6d0b2e3c1b561803f5f7e5d5513e22aab53",
          "blockheight": 94,
          "blockindex": 0,
          "blocktime": 1788879395,
          "txid": "21bad41192fe307f0e4fa5c38fc96268a15ce17a05029a097f14b237ebd95be7",
          "wtxid": "87518190d740025a5e2acbf4565eda4ce626a3307d711b5284fe510f39481802",
          "walletconflicts": [],
          "mempoolconflicts": [],
          "time": 1788879379,
          "timereceived": 1788879379,
          "bip125-replaceable": "no"
        }
    """.trimIndent()

    /** Send entry with fee — captured after invalidateblock, chain behind wallet. */
    private val sendEntry = """
        {
          "address": "bcrt1qanhazu7zjv9lmwc2a6ffzu26fzqyfe5qecwmva",
          "category": "send",
          "amount": -3.21000000,
          "label": "spend",
          "vout": 0,
          "fee": -0.00002820,
          "confirmations": 0,
          "trusted": false,
          "txid": "2517e3a16defa6463d53ec21417aa03d30bc3af475c02ddf2485c75db64a7a62",
          "wtxid": "2fe722eea056a74002ac94b3036c38c15c0e7bd1f8b2e4ade152607deeeae54",
          "walletconflicts": [],
          "mempoolconflicts": [],
          "time": 1788879456,
          "timereceived": 1788879456,
          "bip125-replaceable": "yes",
          "abandoned": false
        }
    """.trimIndent()

    @Test
    fun decodesGenerateEntryExactly() {
        val tx = decodeWalletTx(generateEntry)
        assertEquals("21bad41192fe307f0e4fa5c38fc96268a15ce17a05029a097f14b237ebd95be7", tx.txid)
        assertEquals(5_000_000_000L, tx.amount.value)
        assertEquals(10L, tx.confirmations)
        assertEquals(94L, tx.blockheight)
        assertTrue(tx.isIncoming)
        assertEquals(0, tx.involvesWatchonly.let { if (it) 1 else 0 })
    }

    @Test
    fun decodesSendEntryWithNegativeFeeAndAmount() {
        val tx = decodeWalletTx(sendEntry)
        assertEquals(-321_000_000L, tx.amount.value)
        assertEquals(-2_820L, tx.fee!!.value)
        assertTrue(tx.isPending)
        assertEquals("yes", tx.bip125Replaceable)
    }

    @Test
    fun decodesScientificNotationFee() {
        // Some JSON re-serializers (and wallet tooling passing values through
        // floats) emit fees in exponent form. BigDecimal handles both.
        val tx = decodeWalletTx(sendEntry.replace("-0.00002820", "-2.82e-05"))
        assertEquals(-2_820L, tx.fee!!.value)
    }

    @Test
    fun decodesFullListtransactionsArray() {
        val array = "[$generateEntry, $sendEntry]"
        val list = BitcoinJson.decodeFromJsonElement(
            ListSerializer(WalletTx.serializer()),
            BitcoinJson.parseToJsonElement(array),
        )
        assertEquals(2, list.size)
    }

    /**
     * The behind-chain case, captured live: node chain below the wallet's
     * records — trusted collapses to zero while records remain. This is
     * CORRECT Core behavior and the app must decode it, not hide it.
     */
    @Test
    fun decodesBehindChainBalancesWithZeroTrusted() {
        val json = """
            {
              "mine": { "trusted": 0.00000000, "untrusted_pending": 0.00000000, "immature": 4900.00000000 },
              "lastprocessedblock": { "hash": "x", "height": 99 }
            }
        """.trimIndent()
        val b = BitcoinJson.decodeFromJsonElement(
            Balances.serializer(), BitcoinJson.parseToJsonElement(json),
        )
        assertEquals(0L, b.spendable.value)
        assertEquals(490_000_000_000L, b.display.immature.value)
        assertNull(b.mine?.used)
    }

    /** Watch-only wallet: `mine` is ABSENT entirely, only watchonly present. */
    @Test
    fun decodesWatchOnlyBalancesWithoutMineGroup() {
        val json = """
            { "watchonly": { "trusted": 1.50000000, "untrusted_pending": 0.00000000, "immature": 0.00000000 } }
        """.trimIndent()
        val b = BitcoinJson.decodeFromJsonElement(
            Balances.serializer(), BitcoinJson.parseToJsonElement(json),
        )
        assertNull(b.mine)
        assertTrue(b.isWatchOnlyBalance)
        assertEquals(150_000_000L, b.spendable.value)
    }

    /** getwalletinfo from a newer Core, with unknown lastprocessedblock key. */
    @Test
    fun decodesWalletInfoWithUnknownFutureFields() {
        val json = """
            {
              "walletname": "probe", "walletversion": 169900, "format": "sqlite",
              "txcount": 104, "keypoolsize": 4000, "keypoolsize_hd_internal": 4000,
              "private_keys_enabled": true, "avoid_reuse": false, "scanning": false,
              "descriptors": true, "external_signer": false, "blank": false,
              "birthtime": 1788879378,
              "flags": ["last_hardened_xpub_cached", "descriptor_wallet"],
              "lastprocessedblock": { "hash": "x", "height": 103 }
            }
        """.trimIndent()
        val info = BitcoinJson.decodeFromJsonElement(
            WalletInfo.serializer(), BitcoinJson.parseToJsonElement(json),
        )
        assertEquals("probe", info.walletname)
        assertEquals("sqlite", info.format)
        assertEquals(104L, info.txcount)
        assertEquals(1788879378L, info.birthtime)
    }

    /**
     * The full listtransactions capture of a REAL 2011 wallet.dat (2.4 BTC,
     * 12 entries, 9 unique transactions) loaded on Core 28.3 regtest with the
     * chain missing every one of its blocks — the exact state of a phone
     * importing an old wallet mid-IBD. getbalances answered all zeros for
     * this wallet; these records are the only on-device truth, so the sum
     * the app displays as "recorded on file" has to come out exact, to the
     * satoshi, including the fee-deduplication rule.
     */
    @Test
    fun real2011WalletRecordsNetToExactlyTwoPointFourBtc() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("wallet_2011_listtransactions.json")!!
            .readBytes().decodeToString()
        val entries = buildList {
            for (element in BitcoinJson.parseToJsonElement(raw).jsonArray) {
                add(BitcoinJson.decodeFromJsonElement(WalletTx.serializer(), element))
            }
        }
        assertEquals(12, entries.size)
        val net = WalletRepository.OnFileFacts.netRecorded(entries)
        assertEquals(240_000_000L, net.value)
    }

    /**
     * Fee dedup: a multi-output send repeats its fee on every entry. Counting
     * it per entry would overstate the spend by (outputs − 1) × fee. Built
     * from the send shape Core actually emits (same txid, same fee, two
     * `send` rows) with a fee added to the real capture's shape.
     */
    @Test
    fun multiOutputSendFeeIsCountedOnce() {
        fun send(amount: Double) = """
            {
              "address": "moNQHW6Rr1N17GJ4XwSqMoB86ni4jw6qqh",
              "category": "send", "amount": $amount, "vout": 1, "fee": -0.00100000,
              "confirmations": 5, "trusted": true, "abandoned": false,
              "txid": "4a9445a98611b6513f2a4a25782985c29d7d9e2a43e5a49f0d0455fe3eaa5e82",
              "wtxid": "4a9445a98611b6513f2a4a25782985c29d7d9e2a43e5a49f0d0455fe3eaa5e82",
              "walletconflicts": [], "mempoolconflicts": [], "time": 1308933154,
              "timereceived": 1308933154, "bip125-replaceable": "unknown"
            }
        """.trimIndent()
        val receive = """
            {
              "address": "mpms8nsYFx5oAw3n8QFUKM6CpRqrU3TjK2",
              "category": "receive", "amount": 5.00000000, "vout": 1,
              "abandoned": false, "confirmations": 5, "trusted": true,
              "txid": "7823302477ba3cc77c9834defe55cd6677ee356cdcd0db3d56e6093dbb60b500",
              "walletconflicts": [], "mempoolconflicts": [], "time": 1308589067,
              "timereceived": 1308589067, "bip125-replaceable": "unknown"
            }
        """.trimIndent()
        val entries = listOf(receive, send(-4.0), send(-0.999)).map {
            BitcoinJson.decodeFromJsonElement(
                WalletTx.serializer(), BitcoinJson.parseToJsonElement(it),
            )
        }
        // 5 in − 4.999 out − 0.001 fee once (not three times).
        assertEquals(0L, WalletRepository.OnFileFacts.netRecorded(entries).value)
    }
}
