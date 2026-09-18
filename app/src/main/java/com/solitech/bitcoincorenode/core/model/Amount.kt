package com.solitech.bitcoincorenode.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.absoluteValue

/**
 * An amount of bitcoin, in satoshis.
 *
 * ## Why this type exists
 *
 * Bitcoin Core's RPC reports amounts as JSON numbers in BTC: `0.00010000`.
 * The obvious thing to do is deserialize that into a `Double` and multiply by
 * 1e8. Do not do that.
 *
 * IEEE-754 binary doubles cannot represent most decimal fractions exactly.
 * `0.1 + 0.2 != 0.3`, and by the same mechanism a chain of parse -> arithmetic
 * -> re-serialize can land you a satoshi off. One satoshi of drift in a fee
 * calculation is a rounding curiosity; one satoshi of drift in a change output
 * is a transaction the network will not accept, or worse, silently more fee
 * than the user agreed to pay.
 *
 * So amounts are integers here, always. [BtcAmountSerializer] reads the *text*
 * of the JSON number rather than its parsed double value and converts through
 * BigDecimal, which is exact. Nothing in this app multiplies money by a
 * floating-point number without going through this type.
 *
 * A JVM `Long` holds 9.2e18; the entire 21-million-BTC supply is 2.1e15 sats.
 * Four orders of magnitude of headroom. Overflow is not a concern.
 */
@JvmInline
@Serializable(with = BtcAmountSerializer::class)
value class Sats(val value: Long) : Comparable<Sats> {

    operator fun plus(other: Sats) = Sats(value + other.value)
    operator fun minus(other: Sats) = Sats(value - other.value)
    operator fun times(n: Int) = Sats(value * n)
    operator fun unaryMinus() = Sats(-value)
    override fun compareTo(other: Sats): Int = value.compareTo(other.value)

    val isZero: Boolean get() = value == 0L
    val isNegative: Boolean get() = value < 0L
    val absolute: Sats get() = Sats(value.absoluteValue)

    /** Exact BTC representation. Never used for arithmetic, only display. */
    fun toBtcString(): String =
        BigDecimal(value).divide(SATS_PER_BTC).setScale(8, RoundingMode.UNNECESSARY).toPlainString()

    /** "0.00010000" trimmed to "0.0001", with a bare "0" for zero. */
    fun toBtcStringTrimmed(): String {
        val s = toBtcString()
        if (!s.contains('.')) return s
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    fun toSatString(): String = value.toString()

    override fun toString(): String = "${value}sat"

    companion object {
        private val SATS_PER_BTC: BigDecimal = BigDecimal(100_000_000)

        val ZERO = Sats(0)
        /** 21e6 BTC. Used to sanity-check anything a user types. */
        val MAX_MONEY = Sats(2_100_000_000_000_000L)

        fun ofBtc(decimal: BigDecimal): Sats =
            Sats(decimal.multiply(SATS_PER_BTC).setScale(0, RoundingMode.HALF_UP).toLong())

        /**
         * Parses a decimal BTC string exactly. Returns null on anything that
         * isn't a clean amount -- this is what user input goes through, so it
         * has to reject rather than guess.
         */
        fun parseBtc(text: String): Sats? = try {
            val cleaned = text.trim().replace(",", "")
            if (cleaned.isEmpty()) null
            else {
                val bd = BigDecimal(cleaned)
                if (bd.scale() > 8) null       // finer than a satoshi: not a real amount
                else ofBtc(bd).takeIf { it.value in 0..MAX_MONEY.value }
            }
        } catch (_: NumberFormatException) {
            null
        }

        fun parseSats(text: String): Sats? =
            text.trim().replace(",", "").replace(" ", "").toLongOrNull()
                ?.takeIf { it in 0..MAX_MONEY.value }?.let { Sats(it) }
    }
}

/**
 * Reads a Core JSON amount without ever going through a Double.
 *
 * The trick is [JsonPrimitive.content], which hands back the literal text the
 * node sent ("0.00010000") rather than a parsed numeric value. BigDecimal then
 * converts it exactly.
 */
object BtcAmountSerializer : KSerializer<Sats> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BtcAmount", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Sats {
        val input = decoder as? JsonDecoder
            ?: return Sats.parseBtc(decoder.decodeString()) ?: Sats.ZERO
        val element = input.decodeJsonElement()
        val text = (element as? JsonPrimitive)?.content ?: return Sats.ZERO
        return try {
            Sats.ofBtc(BigDecimal(text))
        } catch (_: NumberFormatException) {
            Sats.ZERO
        }
    }

    override fun serialize(encoder: Encoder, value: Sats) {
        val text = value.toBtcString()
        // Emit an unquoted numeric literal: Core rejects amounts sent as
        // JSON strings on several RPCs.
        if (encoder is JsonEncoder) {
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            encoder.encodeJsonElement(JsonUnquotedLiteral(text))
        } else {
            encoder.encodeString(text)
        }
    }
}

/** How the user wants amounts rendered. Purely presentational. */
enum class DisplayUnit(val label: String, val symbol: String) {
    BTC("BTC", "₿"),
    MBTC("mBTC", "m₿"),
    BITS("bits", "bits"),
    SATS("sats", "sat");

    fun format(amount: Sats, withSymbol: Boolean = true): String {
        val body = when (this) {
            BTC -> amount.toBtcString()
            MBTC -> BigDecimal(amount.value).divide(BigDecimal(100_000))
                .setScale(5, RoundingMode.HALF_UP).toPlainString()
            BITS -> BigDecimal(amount.value).divide(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP).toPlainString()
            SATS -> groupDigits(amount.value)
        }
        return if (withSymbol) "$body $label" else body
    }

    private fun groupDigits(v: Long): String {
        val neg = v < 0
        val digits = v.absoluteValue.toString().reversed().chunked(3).joinToString(",").reversed()
        return if (neg) "-$digits" else digits
    }
}
