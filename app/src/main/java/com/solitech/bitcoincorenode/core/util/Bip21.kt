package com.solitech.bitcoincorenode.core.util

import android.net.Uri
import com.solitech.bitcoincorenode.core.model.Sats

/**
 * BIP21 payment URIs: `bitcoin:<address>?amount=0.001&label=...&message=...`
 *
 * Parsed defensively. This is an entry point for data from outside the app —
 * a QR code, a link in a message, an intent from another app — so nothing here
 * trusts its input. In particular the address is *not* validated by this code;
 * it is handed to the node's `validateaddress`, which is the only thing in the
 * stack qualified to say whether an address is real and on the right network.
 */
object Bip21 {

    data class Parsed(
        val address: String,
        val amount: Sats? = null,
        val label: String? = null,
        val message: String? = null,
        /**
         * BIP21 says a `req-` parameter the wallet does not understand must
         * cause it to reject the URI rather than pay anyway. Ignoring one could
         * mean ignoring a condition the payee considers essential.
         */
        val unsupportedRequired: List<String> = emptyList(),
    ) {
        val isPayable: Boolean get() = unsupportedRequired.isEmpty()
    }

    fun parse(input: String): Parsed? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        // A bare address, pasted or scanned, is the common case.
        if (!raw.contains(':')) {
            return if (looksLikeAddress(raw)) Parsed(address = raw) else null
        }

        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("bitcoin", ignoreCase = true)) return null

        // Uri.getSchemeSpecificPart handles the no-authority form
        // (bitcoin:bc1q...) that Uri.getHost does not.
        val address = uri.schemeSpecificPart?.substringBefore('?')?.trim().orEmpty()
        if (address.isEmpty()) return null

        val params = runCatching { uri.queryParameterNames }.getOrNull().orEmpty()
        val amount = runCatching { uri.getQueryParameter("amount") }.getOrNull()
            ?.let { Sats.parseBtc(it) }

        val unsupported = params.filter { it.startsWith("req-") }

        return Parsed(
            address = address,
            amount = amount,
            label = runCatching { uri.getQueryParameter("label") }.getOrNull(),
            message = runCatching { uri.getQueryParameter("message") }.getOrNull(),
            unsupportedRequired = unsupported,
        )
    }

    fun build(address: String, amount: Sats? = null, label: String? = null): String = buildString {
        append("bitcoin:").append(address)
        val q = buildList {
            amount?.takeIf { !it.isZero }?.let { add("amount=${it.toBtcStringTrimmed()}") }
            label?.takeIf { it.isNotBlank() }?.let { add("label=" + Uri.encode(it)) }
        }
        if (q.isNotEmpty()) append("?").append(q.joinToString("&"))
    }

    /** A shape check only — never a substitute for `validateaddress`. */
    private fun looksLikeAddress(s: String): Boolean {
        if (s.length !in 14..90) return false
        val lower = s.lowercase()
        return lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1") ||
            s.first() in charArrayOf('1', '3', '2', 'm', 'n')
    }
}
