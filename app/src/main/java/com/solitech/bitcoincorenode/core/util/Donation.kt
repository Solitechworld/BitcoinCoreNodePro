package com.solitech.bitcoincorenode.core.util

/**
 * The project's donation address and the copy that goes with it.
 *
 * Kept in one place so the address appears exactly once in the source. An
 * address duplicated across a dozen screens is an address that gets partially
 * updated, and a partially updated donation address sends money to a key
 * nobody holds.
 *
 * The address below is a mainnet P2PKH address. Its base58check checksum was
 * verified before it was committed; if you ever change it, verify the new one
 * the same way. `DonationTest` in the unit tests re-checks it on every build,
 * so a typo fails CI instead of silently costing donations.
 */
object Donation {

    const val ADDRESS = "1Be6LLAEndprdWKiH6YM62setFQRXJzfha"

    /** BIP21 URI, so a scan prefills a label in the payer's wallet. */
    const val URI = "bitcoin:$ADDRESS?label=Bitcoin%20Core%20Node"

    /**
     * Short line for the footer that sits at the bottom of every screen.
     */
    const val SHORT_PITCH =
        "Bitcoin Core Node is independent software, built and maintained without a " +
            "company behind it. It takes no fee from your transactions and never will."

    /**
     * Longer copy for the dedicated panel.
     *
     * A deliberate note on tone: this is written plainly, without claims about
     * what the project will become. A wallet asking for money while promising
     * world-changing results is the exact shape of a scam, and users have been
     * trained — correctly — to distrust it. Understating the pitch is also what
     * keeps the Play listing clear of "financial promises", which is a review
     * category you do not want to be argued about. The work speaks; the copy
     * just asks.
     */
    const val LONG_PITCH =
        "This app runs a real Bitcoin Core node on your phone, with your keys under " +
            "your own control. There is no company behind it, no investors, no fee " +
            "taken from your transactions, and no data collected about you.\n\n" +
            "That independence is the whole point, and it is funded by the people who " +
            "find the work useful. If this is one of them, a contribution goes directly " +
            "toward the next release — finishing the wallet, hardening the build, and " +
            "bringing the same design to iOS.\n\n" +
            "Any amount is genuinely appreciated. So is using the app and telling " +
            "someone about it."

    /** Shown next to the address itself. */
    const val VERIFY_HINT =
        "Check the first and last groups against another source before sending. " +
            "This address is fixed in the app's source code and never changes."
}
