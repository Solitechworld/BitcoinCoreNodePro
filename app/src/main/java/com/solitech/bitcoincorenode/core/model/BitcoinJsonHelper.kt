package com.solitech.bitcoincorenode.core.model

import com.solitech.bitcoincorenode.core.rpc.BitcoinJson
import com.solitech.bitcoincorenode.core.rpc.RpcError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement

/**
 * Turns an RPC result into a model, converting deserialization failures into
 * [RpcError.Malformed].
 *
 * Every decode goes through here so that a version-skew failure surfaces as
 * "this node returned something unexpected" — actionable — rather than as a
 * raw SerializationException from somewhere in a ViewModel, which reaches the
 * user as a crash dialog and reaches us as a stack trace with no context about
 * which call produced it.
 */
object BitcoinJsonHelper {

    fun <T> decode(strategy: DeserializationStrategy<T>, element: JsonElement, method: String = "?"): T =
        try {
            BitcoinJson.decodeFromJsonElement(strategy, element)
        } catch (e: Exception) {
            throw RpcError.Malformed(method, e.message ?: "unexpected response shape", e)
        }

    /**
      * Note the parameter is [KSerializer], not [DeserializationStrategy]:
      * ListSerializer needs a full serializer to build the list serializer from.
      */
     fun <T> decodeList(
        strategy: kotlinx.serialization.KSerializer<T>,
        element: JsonElement,
        method: String = "?",
    ): List<T> = decode(kotlinx.serialization.builtins.ListSerializer(strategy), element, method)
}
