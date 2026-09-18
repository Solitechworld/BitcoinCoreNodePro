package com.solitech.bitcoincorenode.core.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/** JSON-RPC 1.0 request envelope — what bitcoind speaks. */
@Serializable
data class RpcRequest(
    val method: String,
    val params: JsonArray = JsonArray(emptyList()),
    val id: String,
    @SerialName("jsonrpc") val jsonRpc: String? = null,
)

@Serializable
data class RpcResponse(
    val result: JsonElement = JsonNull,
    val error: RpcErrorObject? = null,
    val id: String? = null,
)

@Serializable
data class RpcErrorObject(
    val code: Int,
    val message: String,
)

/**
 * One shared Json configuration.
 *
 * `ignoreUnknownKeys = true` is load-bearing, not laziness: Bitcoin Core adds
 * fields to RPC responses between minor releases. Without it, a node one
 * version newer than the models in this app would break every screen. The app
 * is expected to work against a range of Core versions, so it reads the fields
 * it knows and ignores the rest.
 *
 * `explicitNulls = false` keeps request payloads clean — Core is picky about
 * positional parameters and an explicit null in the wrong slot is not the same
 * as an omitted one.
 */
val BitcoinJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
    coerceInputValues = false
    encodeDefaults = false
}

/** Positional parameter list builder: `params { add("wallet"); add(true) }`. */
inline fun params(builder: JsonArrayBuilderScope.() -> Unit): JsonArray =
    buildJsonArray { JsonArrayBuilderScope(this).builder() }

/**
 * A thin typed wrapper over JsonArrayBuilder.
 *
 * Bitcoin Core's RPC is positional and weakly typed at the wire level; sending
 * a number where it wants a string produces an unhelpful -3. Funnelling every
 * parameter through explicit overloads here means the mistake is a compile
 * error in this app instead of a runtime error on the node.
 */
class JsonArrayBuilderScope(@PublishedApi internal val b: kotlinx.serialization.json.JsonArrayBuilder) {
    fun add(v: String) { b.add(JsonPrimitive(v)) }
    fun add(v: Int) { b.add(JsonPrimitive(v)) }
    fun add(v: Long) { b.add(JsonPrimitive(v)) }
    fun add(v: Double) { b.add(JsonPrimitive(v)) }
    fun add(v: Boolean) { b.add(JsonPrimitive(v)) }
    fun add(v: JsonElement) { b.add(v) }
    fun addNull() { b.add(JsonNull) }
    fun addObject(vararg pairs: Pair<String, JsonElement>) {
        b.add(JsonObject(pairs.toMap()))
    }
    /** Skips the value entirely when null, rather than sending an explicit null. */
    fun addIfNotNull(v: String?) { if (v != null) add(v) }
    fun addIfNotNull(v: Int?) { if (v != null) add(v) }
    fun addIfNotNull(v: Boolean?) { if (v != null) add(v) }
}

/** Where an RpcClient is pointed. */
sealed interface RpcEndpoint {
    val displayName: String

    /** The bitcoind we spawned ourselves, on loopback, authed by cookie file. */
    data class Embedded(
        val port: Int,
        val cookiePath: String,
    ) : RpcEndpoint {
        val url: String get() = "http://127.0.0.1:$port/"
        override val displayName: String get() = "Embedded node"
    }

    /** Someone else's node, reached over Tor. The private option. */
    data class Onion(
        val onionHost: String,
        val port: Int,
        val user: String,
        val password: String,
        val label: String,
    ) : RpcEndpoint {
        val url: String get() = "http://$onionHost:$port/"
        override val displayName: String get() = label.ifBlank { onionHost.take(16) + "…" }
    }

    /**
     * A node on the local network, plaintext.
     *
     * This exists because people genuinely do run a node on a box in the next
     * room, and forcing Tor for a 3-metre hop is silly. But RPC credentials
     * cross the wire in clear here, so the UI makes the user type a confirmation
     * phrase before this can be created, and the Security screen lists every
     * such endpoint as a standing risk.
     */
    data class Lan(
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        val label: String,
    ) : RpcEndpoint {
        val url: String get() = "http://$host:$port/"
        override val displayName: String get() = label.ifBlank { "$host:$port" }
    }
}
