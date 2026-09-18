package com.solitech.bitcoincorenode.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Ad-free implementation. This is the one in the public source tree.
 *
 * The app has an optional advertising integration that is not part of this
 * repository. `app/build.gradle.kts` picks exactly one of two source
 * directories at configure time:
 *
 *   src/ads/java    present only on a machine that ships ads -- pulls in the
 *                   Google Mobile Ads SDK and a real banner
 *   src/noads/java  this file -- no SDK, no network, no identifiers
 *
 * Only one is ever added to the `main` source set, so the two can declare the
 * same symbols without colliding. Everything that calls into this package
 * (`BitcoinCoreNodeApp`, `BitcoinCoreNodeRoot`) compiles unchanged against
 * either, which is the point: the call sites must never need to know.
 *
 * Keep the signatures below identical to the ones in src/ads. If they drift,
 * the ad-enabled build breaks on a machine this CI never tests.
 */
object Ads {
    /** No-op. There is no ad SDK in this build to initialise. */
    fun initialize(context: android.content.Context) {
        // Intentionally empty.
    }
}

/**
 * Renders nothing and occupies no space, so the layout above the bottom
 * navigation closes up cleanly rather than leaving a reserved gap.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    // Intentionally empty.
}
