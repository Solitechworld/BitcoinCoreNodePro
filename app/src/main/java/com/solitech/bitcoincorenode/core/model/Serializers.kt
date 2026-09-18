package com.solitech.bitcoincorenode.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads Core's `warnings` field whether it arrives as a string or an array.
 *
 * This is not defensive coding for its own sake. Bitcoin Core changed
 * `warnings` in getblockchaininfo and getnetworkinfo from a single string to
 * an array of strings, and 30.3 still documents *both* shapes because the old
 * one survives behind `-deprecatedrpc=warnings`. An app that expects one shape
 * crashes against a node configured for the other -- and a node raising a
 * warning is exactly the moment you least want the app to fall over.
 */
object WarningsSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Warnings", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String> {
        val input = decoder as? JsonDecoder
            ?: return listOf(decoder.decodeString()).filter { it.isNotBlank() }
        return when (val el = input.decodeJsonElement()) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content }.filter { it.isNotBlank() }
            is JsonPrimitive -> listOf(el.content).filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeString(value.joinToString("; "))
    }
}
