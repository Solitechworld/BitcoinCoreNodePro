package com.solitech.bitcoincorenode.wallet

import android.content.Context
import android.net.Uri
import com.solitech.bitcoincorenode.core.model.BitcoinNetwork
import com.solitech.bitcoincorenode.core.node.NodePaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Places a user-selected `wallet.dat` where Bitcoin Core can migrate it.
 *
 * ## The shape of this operation
 *
 * Core 30 cannot open a legacy Berkeley-DB wallet — the implementation is gone.
 * It can only *migrate* one, via `migratewallet`, using the read-only BDB
 * reader in `src/wallet/migrate.cpp`. And `migratewallet` takes a wallet
 * *name*, not a path: the file has to already be sitting at
 * `<datadir>/wallets/<name>/wallet.dat` before the RPC is called.
 *
 * So the flow is: copy the file into place here, then call
 * `WalletRepository.migrateLegacyWallet(name, passphrase)`.
 *
 * ## Why the file is copied and never moved
 *
 * The user picked it through the Storage Access Framework, so it may live in
 * Drive, on a USB stick, or in another app's sandbox. We take a copy and leave
 * the original completely untouched. A wallet file may be the only copy of
 * somebody's money that exists; this code does not get to move it, rename it,
 * or delete it.
 */
@Singleton
class WalletImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paths: NodePaths,
) {

    sealed interface Result {
        data class Ready(val walletName: String, val bytes: Long) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * @param name the wallet name to create. Must be a plain directory name —
     *   it becomes a path component, so anything containing a separator is
     *   refused rather than sanitised. Silently renaming a user's wallet is
     *   worse than making them pick again.
     */
    suspend fun stageLegacyWallet(
        uri: Uri,
        name: String,
        network: BitcoinNetwork,
    ): Result = withContext(Dispatchers.IO) {
        if (!isSafeWalletName(name)) {
            return@withContext Result.Rejected(
                "Use a simple name with no slashes or spaces — it becomes a folder " +
                    "inside the node's data directory."
            )
        }

        val walletsDir = File(paths.dataDir(network), "wallets")
        val target = File(walletsDir, name)
        if (target.exists()) {
            return@withContext Result.Rejected(
                "A wallet called \"$name\" already exists. Pick another name — " +
                    "importing over it would destroy the existing one."
            )
        }

        try {
            if (!target.mkdirs()) {
                return@withContext Result.Rejected("Could not create the wallet directory.")
            }
            val dest = File(target, "wallet.dat")

            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                target.deleteRecursively()
                return@withContext Result.Rejected("Could not read the file you selected.")
            }

            // A Berkeley DB file starts with a page header carrying one of BDB's
            // magic numbers. Checking it here turns "you picked the wrong file"
            // into a clear message now, rather than an opaque migration failure
            // several minutes later.
            if (!looksLikeBerkeleyDb(dest)) {
                target.deleteRecursively()
                return@withContext Result.Rejected(
                    "That file is not a Berkeley DB wallet. If it came from Bitcoin Core 29 " +
                        "or newer it is already a descriptor wallet — use Restore, not Import."
                )
            }

            Result.Ready(walletName = name, bytes = copied)
        } catch (e: IOException) {
            target.deleteRecursively()
            Result.Rejected(e.message ?: "Could not copy the wallet file.")
        }
    }

    private fun isSafeWalletName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        // Letters, digits, dash and underscore only. Anything else could be a
        // path component, a shell surprise, or simply unreadable in a log.
        return name.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /**
     * Berkeley DB magic, checked at both byte orders and at the two offsets BDB
     * uses depending on page size.
     *
     * Deliberately a loose sanity check rather than a validator: Core does the
     * real parsing, and being over-strict here would reject wallets Core could
     * actually migrate.
     */
    private fun looksLikeBerkeleyDb(file: File): Boolean = try {
        if (file.length() < 512) {
            false
        } else {
            val head = ByteArray(32)
            file.inputStream().use { it.read(head) }

            fun littleEndian(o: Int): Int =
                (head[o].toInt() and 0xFF) or
                    ((head[o + 1].toInt() and 0xFF) shl 8) or
                    ((head[o + 2].toInt() and 0xFF) shl 16) or
                    ((head[o + 3].toInt() and 0xFF) shl 24)

            fun bigEndian(o: Int): Int =
                (head[o + 3].toInt() and 0xFF) or
                    ((head[o + 2].toInt() and 0xFF) shl 8) or
                    ((head[o + 1].toInt() and 0xFF) shl 16) or
                    ((head[o].toInt() and 0xFF) shl 24)

            val candidates = listOf(
                littleEndian(12), bigEndian(12),
                littleEndian(0), bigEndian(0),
            )
            // 0x00053162 = BDB B-tree magic, 0x00061561 = BDB hash magic.
            candidates.any { it == BDB_BTREE_MAGIC || it == BDB_HASH_MAGIC }
        }
    } catch (_: IOException) {
        false
    }

    /** Where a descriptor-wallet backup should be written for [name]. */
    fun backupDestination(name: String): File =
        File(paths.exportDir(), "$name-${System.currentTimeMillis()}.backup.dat")

    /** Where dumpwallet/importwallet text dumps are staged. */
    fun dumpDestination(name: String): File =
        File(paths.exportDir(), "$name-${System.currentTimeMillis()}.dump")

    /**
     * Copies a file the node wrote to a location the user picked (Documents,
     * Drive, anywhere SAF offers). The node can only write inside its own
     * data directory, so this hop is what makes "backup to Documents" real.
     */
    suspend fun copyToUri(source: File, uri: Uri): Unit = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Could not open the destination you picked.")
    }

    /** Copies picked content into the node-visible export dir, for importwallet. */
    suspend fun stageDumpFile(uri: Uri, suggestedName: String): File = withContext(Dispatchers.IO) {
        val dest = File(paths.exportDir(), suggestedName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("Could not read the file you selected.")
        dest
    }

    /** Writes text (e.g. a descriptor dump) to a user-picked location. */
    suspend fun writeTextToUri(text: String, uri: Uri): Unit = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray()) }
            ?: throw IOException("Could not open the destination you picked.")
    }

    companion object {
        private const val BDB_BTREE_MAGIC = 0x00053162
        private const val BDB_HASH_MAGIC = 0x00061561
    }
}
