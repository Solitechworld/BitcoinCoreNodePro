package com.solitech.bitcoincorenode

import com.solitech.bitcoincorenode.core.util.Donation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Verifies the hardcoded donation address really is a valid Bitcoin address.
 *
 * This is not ceremony. A donation address is typed once, by a human, into a
 * source file, and then never looked at again. If a character is wrong, the
 * base58check checksum fails and every wallet rejects it -- best case. Worst
 * case the typo happens to produce a *valid* address for a key nobody holds,
 * and the money is burned silently, forever, with no error anywhere.
 *
 * A checksum test costs nothing and turns that into a failing build.
 */
class DonationTest {

    private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun base58Decode(input: String): ByteArray {
        var num = java.math.BigInteger.ZERO
        for (ch in input) {
            val idx = alphabet.indexOf(ch)
            require(idx >= 0) { "'$ch' is not a base58 character" }
            num = num.multiply(java.math.BigInteger.valueOf(58))
                .add(java.math.BigInteger.valueOf(idx.toLong()))
        }
        val body = num.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val leadingZeros = input.takeWhile { it == '1' }.length
        return ByteArray(leadingZeros) + body
    }

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    @Test
    fun `donation address has a valid base58check checksum`() {
        val decoded = base58Decode(Donation.ADDRESS)

        assertEquals(
            "a base58check address decodes to 25 bytes (1 version + 20 hash + 4 checksum)",
            25, decoded.size,
        )

        val payload = decoded.copyOfRange(0, 21)
        val checksum = decoded.copyOfRange(21, 25)
        val expected = sha256(sha256(payload)).copyOfRange(0, 4)

        assertTrue(
            "checksum mismatch -- the donation address in Donation.kt is mistyped. " +
                "Do not ship this build; funds sent to it would be unrecoverable.",
            expected.contentEquals(checksum),
        )
    }

    @Test
    fun `donation address is on mainnet`() {
        val version = base58Decode(Donation.ADDRESS)[0].toInt() and 0xFF
        // 0x00 = P2PKH mainnet, 0x05 = P2SH mainnet. Anything else means the
        // address belongs to a test network and would never receive real
        // donations.
        assertTrue(
            "donation address must be a mainnet address, got version byte 0x%02x".format(version),
            version == 0x00 || version == 0x05,
        )
    }

    @Test
    fun `donation uri is well formed bip21`() {
        assertTrue(Donation.URI.startsWith("bitcoin:${Donation.ADDRESS}"))
    }

    @Test
    fun `pitch copy makes no financial promises`() {
        // Play's financial-products policy, and basic honesty. A wallet that
        // promises returns is the shape of a scam; this stops the copy drifting
        // there during a future edit.
        val forbidden = listOf(
            "guarantee", "guaranteed", "profit", " returns", "roi",
            "investment", "risk-free", "moon",
        )
        val copy = (Donation.SHORT_PITCH + " " + Donation.LONG_PITCH).lowercase()
        forbidden.forEach { word ->
            assertTrue(
                "donation copy must not contain '$word' -- it turns a request for " +
                    "support into a financial promise",
                !copy.contains(word),
            )
        }
    }
}
