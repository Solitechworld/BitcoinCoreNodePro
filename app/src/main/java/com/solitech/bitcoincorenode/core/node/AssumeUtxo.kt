package com.solitech.bitcoincorenode.core.node

import com.solitech.bitcoincorenode.core.model.BitcoinNetwork
import com.solitech.bitcoincorenode.core.model.ChainStates
import com.solitech.bitcoincorenode.core.rpc.RpcClient
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.core.rpc.params
import com.solitech.bitcoincorenode.core.model.BitcoinJsonHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Snapshot commitments compiled into Bitcoin Core.
 *
 * Transcribed from `src/kernel/chainparams.cpp :: m_assumeutxo_data` in Core
 * 30.3. **Re-transcribe on every Core upgrade** — a snapshot height Core does
 * not recognise is rejected outright, and offering the user a download that
 * cannot possibly load is a cruel way to waste 11 GB of their mobile data.
 *
 * ## What these hashes are, and what they are not
 *
 * `hashSerialized` is a hash of the *deserialized UTXO set*, not of the
 * snapshot file's bytes. There is no file checksum here because Core does not
 * commit to one.
 *
 * That has a consequence worth being precise about: this app cannot verify a
 * downloaded snapshot before handing it to the node, and it does not pretend
 * to. Core recomputes the set hash while loading and refuses anything that
 * does not match its own commitment. The verification is real — it is simply
 * performed by Core, in C++, after the download, rather than by us beforehand.
 *
 * The practical upshot: a corrupt or hostile snapshot costs the user bandwidth
 * and time, but it cannot put a false UTXO set into their node.
 */
data class AssumeUtxoCommitment(
    val height: Long,
    val hashSerialized: String,
    val chainTxCount: Long,
    val blockHash: String,
) {
    /** The conventional filename these snapshots are published under. */
    val suggestedFileName: String get() = "utxo-$height.dat"

    /** Rough download size. Grows with the UTXO set, not the chain. */
    val approximateGigabytes: Double get() = when {
        height >= 900_000 -> 12.0
        height >= 880_000 -> 11.5
        else -> 10.5
    }
}

object AssumeUtxoCommitments {

    private val MAINNET = listOf(
        AssumeUtxoCommitment(
            height = 840_000,
            hashSerialized = "a2a5521b1b5ab65f67818e5e8eccabb7171a517f9e2382208f77687310768f96",
            chainTxCount = 991_032_194,
            blockHash = "0000000000000000000320283a032748cef8227873ff4872689bf23f1cda83a5",
        ),
        AssumeUtxoCommitment(
            height = 880_000,
            hashSerialized = "dbd190983eaf433ef7c15f78a278ae42c00ef52e0fd2a54953782175fbadcea9",
            chainTxCount = 1_145_604_538,
            blockHash = "000000000000000000010b17283c3c400507969a9c2afd1dcf2082ec5cca2880",
        ),
        AssumeUtxoCommitment(
            height = 910_000,
            hashSerialized = "4daf8a17b4902498c5787966a2b51c613acdab5df5db73f196fa59a4da2f1568",
            chainTxCount = 1_226_586_151,
            blockHash = "0000000000000000000108970acb9522ffd516eae17acddcb1bd16469194a821",
        ),
    )

    private val TESTNET3 = listOf(
        AssumeUtxoCommitment(
            height = 2_500_000,
            hashSerialized = "f841584909f68e47897952345234e37fcd9128cd818f41ee6c3ca68db8071be7",
            chainTxCount = 66_484_552,
            blockHash = "0000000000000093bcb68c03a9a168ae252572d348a2eaeba2cdf9231d73206f",
        ),
    )

    private val TESTNET4 = listOf(
        AssumeUtxoCommitment(
            height = 90_000,
            hashSerialized = "784fb5e98241de66fdd429f4392155c9e7db5c017148e66e8fdbc95746f8b9b5",
            chainTxCount = 11_347_043,
            blockHash = "0000000002ebe8bcda020e0dd6ccfbdfac531d2f6a81457191b99fc2df2dbe3b",
        ),
    )

    private val SIGNET = listOf(
        AssumeUtxoCommitment(
            height = 160_000,
            hashSerialized = "fe0a44309b74d6b5883d246cb419c6221bcccf0b308c9b59b7d70783dbdf928a",
            chainTxCount = 2_289_496,
            blockHash = "0000003ca3c99aff040f2563c2ad8f8ec88bd0fd6b8f0895cfaf1ef90353a62c",
        ),
    )

    fun forNetwork(network: BitcoinNetwork): List<AssumeUtxoCommitment> = when (network) {
        BitcoinNetwork.MAIN -> MAINNET
        BitcoinNetwork.TEST -> TESTNET3
        BitcoinNetwork.TEST4 -> TESTNET4
        BitcoinNetwork.SIGNET -> SIGNET
        // Regtest commitments in Core exist only for its own test suite.
        BitcoinNetwork.REGTEST -> emptyList()
    }

    fun latest(network: BitcoinNetwork): AssumeUtxoCommitment? =
        forNetwork(network).maxByOrNull { it.height }
}

/** What the user sees while a snapshot is being brought in. */
sealed interface SnapshotProgress {
    data class Downloading(val bytesRead: Long, val totalBytes: Long, val bytesPerSecond: Long) :
        SnapshotProgress {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
        val etaSeconds: Long?
            get() = if (bytesPerSecond > 0 && totalBytes > bytesRead)
                (totalBytes - bytesRead) / bytesPerSecond else null
    }

    /** Core is deserializing and hashing. This is the step that actually verifies. */
    data object Loading : SnapshotProgress

    data class BackgroundValidating(val blocks: Long, val target: Long) : SnapshotProgress {
        val fraction: Float
            get() = if (target > 0) (blocks.toFloat() / target).coerceIn(0f, 1f) else 0f
    }

    data class Done(val tipHeight: Long) : SnapshotProgress
    data class Failed(val reason: String, val recoverable: Boolean) : SnapshotProgress
}

/**
 * Drives the assumeutxo bootstrap.
 *
 * Deliberately takes the snapshot URL as a parameter rather than knowing one.
 * Shipping a default host would mean every user of this app fetching 11 GB
 * from an endpoint the app author chose, which is both a bandwidth burden on
 * that host and a privacy signal ("this IP just installed this wallet") the
 * user never agreed to emit.
 */
class AssumeUtxoManager(
    private val httpClient: OkHttpClient,
    private val snapshotDir: File,
) {

    fun bootstrap(
        rpc: RpcClient,
        commitment: AssumeUtxoCommitment,
        sourceUrl: String,
        deleteAfterLoad: Boolean = true,
    ): Flow<SnapshotProgress> = flow {
        val target = File(snapshotDir, commitment.suggestedFileName)

        try {
            if (!isAlreadyDownloaded(target)) {
                downloadWithProgress(sourceUrl, target) { emit(it) }
            }

            emit(SnapshotProgress.Loading)

            // No timeout: this reads and hashes ~11 GB on phone storage and
            // legitimately takes many minutes. RpcClient.LONG_RUNNING already
            // exempts loadtxoutset, but being explicit here documents why.
            try {
                rpc.call(
                    method = "loadtxoutset",
                    params = params { add(target.absolutePath) },
                    timeout = RpcClient.NO_TIMEOUT,
                )
            } catch (e: RpcError.Rpc) {
                // The most important failure path in this class. Core rejecting
                // the snapshot means its UTXO set hash did not match the
                // commitment compiled into it -- a corrupt download, or a
                // hostile one. Either way the node is unharmed.
                target.delete()
                emit(
                    SnapshotProgress.Failed(
                        reason = "The node rejected this snapshot: ${e.rpcMessage}\n\n" +
                            "Its UTXO set does not match the commitment built into " +
                            "Bitcoin Core. The download was corrupt or tampered with. " +
                            "Nothing was written to your chainstate.",
                        recoverable = true,
                    )
                )
                return@flow
            }

            if (deleteAfterLoad) target.delete()

            // Snapshot chainstate is live from here. The background chainstate
            // keeps validating from genesis, and we report that honestly rather
            // than declaring victory.
            while (true) {
                val states = fetchChainStates(rpc) ?: break
                if (!states.isDualChainstate) {
                    emit(SnapshotProgress.Done(states.chainstates.firstOrNull()?.blocks ?: 0))
                    break
                }
                val bg = states.background
                val snap = states.snapshot
                emit(
                    SnapshotProgress.BackgroundValidating(
                        blocks = bg?.blocks ?: 0,
                        target = snap?.blocks ?: states.headers,
                    )
                )
                delay(5_000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(SnapshotProgress.Failed(e.message ?: "Snapshot bootstrap failed", recoverable = true))
        }
    }.flowOn(Dispatchers.IO)

    private fun isAlreadyDownloaded(target: File): Boolean =
        target.exists() && target.length() > 1_000_000_000L

    private suspend inline fun downloadWithProgress(
        url: String,
        target: File,
        emit: (SnapshotProgress) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        // Resume a partial download rather than restarting 11 GB from zero.
        val existing = if (target.exists()) target.length() else 0L
        val req = if (existing > 0) {
            request.newBuilder().header("Range", "bytes=$existing-").build()
        } else {
            request
        }

        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Snapshot host returned HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response from snapshot host")
            val contentLength = body.contentLength()
            val total = if (contentLength > 0) contentLength + existing else -1L

            val append = response.code == 206 && existing > 0
            target.parentFile?.mkdirs()

            body.byteStream().use { input ->
                java.io.FileOutputStream(target, append).use { output ->
                    val buf = ByteArray(1 shl 16)
                    var written = if (append) existing else 0L
                    var lastEmit = System.currentTimeMillis()
                    var lastWritten = written
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 500) {
                            val elapsed = (now - lastEmit).coerceAtLeast(1)
                            val rate = (written - lastWritten) * 1000 / elapsed
                            emit(SnapshotProgress.Downloading(written, total, rate))
                            lastEmit = now
                            lastWritten = written
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    private suspend fun fetchChainStates(rpc: RpcClient): ChainStates? = try {
        BitcoinJsonHelper.decode(ChainStates.serializer(), rpc.call("getchainstates"))
    } catch (_: RpcError) {
        null
    }
}
