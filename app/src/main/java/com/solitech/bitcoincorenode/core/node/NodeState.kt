package com.solitech.bitcoincorenode.core.node

import com.solitech.bitcoincorenode.core.model.BlockchainInfo
import com.solitech.bitcoincorenode.core.model.ChainStates
import com.solitech.bitcoincorenode.core.model.NetworkInfo

/**
 * The single source of truth the entire UI reacts to.
 *
 * Modelled as a sealed hierarchy rather than a bag of booleans because the
 * states are genuinely exclusive and each needs different UI. A boolean soup
 * (`isStarting && !isSynced && hasSnapshot`) is how you end up rendering a
 * progress bar and an error at the same time.
 */
sealed interface NodeState {

    data object Stopped : NodeState

    /** Process spawned, RPC not answering yet. [stage] drives the splash copy. */
    data class Starting(val stage: StartupStage, val detail: String = "") : NodeState

    /**
     * RPC is up and the node is loading indexes. This is Core's -28 warmup
     * window and it can last minutes on a phone. It is NOT an error, and the
     * UI must never render it as one.
     */
    data class Loading(val message: String, val progress: Float? = null) : NodeState

    /** An assumeutxo snapshot is being downloaded, verified or loaded. */
    data class SnapshotLoading(val phase: SnapshotPhase, val fraction: Float) : NodeState

    data class Syncing(
        val info: BlockchainInfo,
        val network: NetworkInfo?,
        val chainStates: ChainStates?,
        val blocksPerSecond: Double = 0.0,
    ) : NodeState {
        /**
         * Seconds to the tip, or null when we can't say honestly.
         *
         * Returning null is the point. An ETA computed from three seconds of
         * samples is a number-shaped guess, and a wrong "4 minutes remaining"
         * that becomes "6 hours remaining" costs more trust than showing
         * nothing. The UI renders "estimating…" until this is real.
         */
        val etaSeconds: Long?
            get() {
                if (blocksPerSecond <= 0.01) return null
                val remaining = info.blocksBehind
                if (remaining <= 0) return 0
                return (remaining / blocksPerSecond).toLong()
            }
    }

    data class Synced(
        val info: BlockchainInfo,
        val network: NetworkInfo?,
        val chainStates: ChainStates?,
    ) : NodeState

    data class Reindexing(val message: String, val progress: Float?) : NodeState

    data object Stopping : NodeState

    /**
     * The node process died. [logTail] holds the last lines of debug.log,
     * because "it crashed" without them is unactionable for the user and
     * unactionable for us in a bug report.
     */
    data class Crashed(
        val exitCode: Int,
        val logTail: List<String>,
        val likelyCause: CrashCause,
    ) : NodeState

    /** Remote mode: connected to someone else's node. */
    data class RemoteConnected(
        val info: BlockchainInfo,
        val network: NetworkInfo?,
        val label: String,
    ) : NodeState

    data class RemoteUnreachable(val label: String, val reason: String) : NodeState

    // --- convenience, so screens don't pattern-match everywhere ---

    val isRunning: Boolean
        get() = this is Starting || this is Loading || this is Syncing ||
            this is Synced || this is SnapshotLoading || this is Reindexing

    val isUsable: Boolean
        get() = this is Synced || this is RemoteConnected ||
            (this is Syncing && !info.initialblockdownload)

    val blockHeight: Long?
        get() = when (this) {
            is Syncing -> info.blocks
            is Synced -> info.blocks
            is RemoteConnected -> info.blocks
            else -> null
        }
}

enum class StartupStage(val label: String) {
    VERIFYING_BINARY("Verifying node binary"),
    PREPARING_DATADIR("Preparing data directory"),
    WRITING_CONFIG("Writing configuration"),
    SPAWNING("Starting Bitcoin Core"),
    AWAITING_RPC("Waiting for RPC"),
}

enum class SnapshotPhase(val label: String) {
    DOWNLOADING("Downloading UTXO snapshot"),
    VERIFYING("Verifying snapshot hash"),
    LOADING("Loading snapshot into the node"),
    BACKGROUND_VALIDATING("Validating history in the background"),
}

/**
 * Best guess at why the node died, from its exit code and log tail.
 *
 * Worth the effort: the difference between "out of disk" and "corrupt data
 * directory" is the difference between a fix the user can do in thirty seconds
 * and one that costs them a full resync. Guessing wrong in the reassuring
 * direction is the failure mode to avoid, so anything unrecognised stays
 * UNKNOWN and shows the raw log instead of a confident wrong explanation.
 */
enum class CrashCause(val summary: String, val suggestion: String) {
    OUT_OF_DISK(
        "The device ran out of storage",
        "Free up space, or lower the storage budget in Settings. The node needs headroom above the prune target.",
    ),
    CORRUPT_DATADIR(
        "The block database looks corrupted",
        "This usually follows an unclean shutdown. Try Reindex from the node menu.",
    ),
    PORT_IN_USE(
        "The RPC port is already in use",
        "Another copy of the node may still be shutting down. Wait a moment and start again.",
    ),
    KILLED_BY_SYSTEM(
        "Android stopped the node to reclaim memory",
        "Lower the database cache in Settings, and exclude the app from battery optimisation.",
    ),
    PERMISSION_DENIED(
        "The node binary could not be executed",
        "This build may be packaged incorrectly. Reinstall the app.",
    ),
    UNKNOWN(
        "The node stopped unexpectedly",
        "The log below is the most useful thing to attach to a bug report.",
    );

    companion object {
        fun infer(exitCode: Int, logTail: List<String>): CrashCause {
            val text = logTail.joinToString("\n").lowercase()
            return when {
                "no space left" in text || "disk space" in text || "insufficient space" in text ->
                    OUT_OF_DISK
                "corrupt" in text || "database" in text && "error" in text ->
                    CORRUPT_DATADIR
                "address already in use" in text || "unable to bind" in text ->
                    PORT_IN_USE
                "permission denied" in text || exitCode == 126 || exitCode == 13 ->
                    PERMISSION_DENIED
                // SIGKILL: 128+9. Android's LMK is the overwhelmingly likely killer.
                exitCode == 137 || exitCode == 9 ->
                    KILLED_BY_SYSTEM
                else -> UNKNOWN
            }
        }
    }
}
