package com.solitech.bitcoincorenode.core.rpc

/**
 * Everything that can go wrong between a tap and a node.
 *
 * The taxonomy matters more than it looks. A wallet app that shows
 * "Error: -6" has failed the user; a wallet app that shows "Insufficient funds"
 * when the real problem was "your node is still 40 000 blocks behind" has
 * failed them worse. Every branch below exists because it needs a different
 * sentence in the UI and often a different recovery action.
 *
 * Numeric codes are transcribed from Bitcoin Core 30.3
 * `src/rpc/protocol.h :: enum RPCErrorCode`. Do not adjust them from memory.
 */
sealed class RpcError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The node isn't listening. Usually: not started yet, or crashed. */
    class NotReachable(val endpoint: String, cause: Throwable?) :
        RpcError("Cannot reach node at $endpoint", cause)

    /** Connected, but the RPC credentials were rejected (HTTP 401). */
    class Unauthorized(val endpoint: String) :
        RpcError("Node at $endpoint rejected our credentials")

    /**
     * RPC_IN_WARMUP (-28). Not an error at all — the node is loading the block
     * index. The correct response is to wait and retry, never to show a failure.
     */
    class Warmup(val detail: String) : RpcError("Node is starting: $detail")

    /** The request timed out. Separate from NotReachable: the node IS there. */
    class Timeout(val method: String, val seconds: Long) :
        RpcError("'$method' did not answer within ${seconds}s")

    /** Node returned a JSON-RPC error object. */
    class Rpc(val code: Int, val rpcMessage: String, val method: String) :
        RpcError("$method failed [$code]: $rpcMessage") {

        val kind: Kind get() = Kind.of(code)

        /** Groupings the UI actually branches on. */
        enum class Kind {
            WALLET_LOCKED,          // -13: prompt for passphrase, then retry
            WALLET_PASSPHRASE_BAD,  // -14: tell them it was wrong, allow retry
            INSUFFICIENT_FUNDS,     // -6
            WALLET_MISSING,         // -18, -19, -35, -36
            NOT_CONNECTED,          // -9:  no peers
            STILL_SYNCING,          // -10: mid-IBD, result would be misleading
            INVALID_ADDRESS_OR_KEY, // -5
            BAD_PARAMETER,          // -3, -8, -22, -32602
            REJECTED_BY_NETWORK,    // -25, -26: the interesting one, see below
            ALREADY_IN_CHAIN,       // -27: often means "it already worked"
            NODE_INTERNAL,          // -1, -7, -20, -32603
            DEPRECATED,             // -32
            OTHER;

            companion object {
                fun of(code: Int): Kind = when (code) {
                    -13 -> WALLET_LOCKED
                    -14 -> WALLET_PASSPHRASE_BAD
                    -6 -> INSUFFICIENT_FUNDS
                    -18, -19, -35, -36 -> WALLET_MISSING
                    -9 -> NOT_CONNECTED
                    -10 -> STILL_SYNCING
                    -5 -> INVALID_ADDRESS_OR_KEY
                    -3, -8, -22, -32602 -> BAD_PARAMETER
                    -25, -26 -> REJECTED_BY_NETWORK
                    -27 -> ALREADY_IN_CHAIN
                    -1, -7, -20, -32603 -> NODE_INTERNAL
                    -32 -> DEPRECATED
                    else -> OTHER
                }
            }
        }

        /**
         * A sentence to put in front of a human.
         *
         * Note what this does NOT do: it never invents reassurance. If a
         * broadcast was rejected by network rules we say so and pass through
         * the node's own reason, because the node's reason ("min relay fee not
         * met", "bad-txns-inputs-missingorspent") is the actual information and
         * paraphrasing it would destroy it.
         */
        override fun userMessage(): String = when (kind) {
            Kind.WALLET_LOCKED -> "This wallet is locked. Enter its passphrase to continue."
            Kind.WALLET_PASSPHRASE_BAD -> "That passphrase was not correct."
            Kind.INSUFFICIENT_FUNDS ->
                "Not enough spendable funds. Unconfirmed change and immature " +
                    "coinbase outputs don't count toward what you can spend right now."
            Kind.WALLET_MISSING -> "That wallet isn't loaded."
            Kind.NOT_CONNECTED -> "Your node has no peer connections yet."
            Kind.STILL_SYNCING ->
                "Your node is still syncing. Balances and history are incomplete " +
                    "until it reaches the tip, so this result would be misleading."
            Kind.INVALID_ADDRESS_OR_KEY -> "That address or key isn't valid: $rpcMessage"
            Kind.BAD_PARAMETER -> rpcMessage
            Kind.REJECTED_BY_NETWORK -> "The network rejected this transaction: $rpcMessage"
            Kind.ALREADY_IN_CHAIN -> "That transaction is already confirmed on the chain."
            Kind.NODE_INTERNAL -> "The node hit an internal error: $rpcMessage"
            Kind.DEPRECATED -> "This node has disabled that call: $rpcMessage"
            Kind.OTHER -> rpcMessage
        }
    }

    /**
     * The node answered, but not with the shape we expected. Almost always a
     * version skew: someone pointed the app at an older or patched node whose
     * RPC returns different fields.
     */
    class Malformed(val method: String, val detail: String, cause: Throwable? = null) :
        RpcError("Could not read the response to '$method': $detail", cause)

    /** Remote mode configured for Tor, but Tor isn't up. */
    class TorUnavailable(val detail: String) : RpcError("Tor is not ready: $detail")

    /** Retrying is likely to help. Drives automatic backoff. */
    val isTransient: Boolean
        get() = when (this) {
            is Warmup -> true
            is Timeout -> true
            is NotReachable -> true
            is Rpc -> kind == Rpc.Kind.NOT_CONNECTED || kind == Rpc.Kind.STILL_SYNCING
            else -> false
        }

    /** A sentence to show the user, for any error type. */
    open fun userMessage(): String = when (this) {
        // Rpc overrides this; the branch exists only for exhaustiveness.
        is Rpc -> rpcMessage
        is NotReachable -> "Can't reach the node. It may still be starting up."
        is Unauthorized -> "The node refused our credentials. Check the RPC username and password."
        is Warmup -> "Node is starting up — $detail"
        is Timeout -> "The node didn't answer in time."
        is Malformed -> "This node returned something unexpected. Version mismatch?"
        is TorUnavailable -> "Tor isn't connected yet."
    }
}
