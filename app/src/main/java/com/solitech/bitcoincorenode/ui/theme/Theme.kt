package com.solitech.bitcoincorenode.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.solitech.bitcoincorenode.core.model.BitcoinNetwork

/**
 * The app theme — cyberspace dark.
 *
 * A single dark scheme with neon accents. The dark background makes the
 * glowing borders, scanlines, and neon status indicators pop. Dynamic
 * colour is declined deliberately: it would let the user's wallpaper decide
 * what "confirmed" and "failed" look like in a Bitcoin wallet.
 */

private val CyberDarkScheme = darkColorScheme(
    primary = CyberColors.Cyan,
    onPrimary = CyberColors.TextOnAccent,
    primaryContainer = CyberColors.CyanDeep,
    onPrimaryContainer = CyberColors.CyanDim,

    secondary = CyberColors.Magenta,
    onSecondary = CyberColors.TextOnAccent,
    secondaryContainer = CyberColors.SurfaceHigh,
    onSecondaryContainer = CyberColors.Magenta,

    tertiary = CyberColors.Violet,
    onTertiary = CyberColors.TextOnAccent,

    background = CyberColors.Void,
    onBackground = CyberColors.TextPrimary,
    surface = CyberColors.Surface,
    onSurface = CyberColors.TextPrimary,
    surfaceVariant = CyberColors.SurfaceElevated,
    onSurfaceVariant = CyberColors.TextSecondary,
    surfaceContainer = CyberColors.SurfaceElevated,
    surfaceContainerHigh = CyberColors.SurfaceHigh,

    error = CyberColors.Red,
    onError = CyberColors.TextOnAccent,
    errorContainer = CyberColors.RedDim.copy(alpha = 0.2f),
    onErrorContainer = CyberColors.Red,

    outline = CyberColors.Border,
    outlineVariant = CyberColors.BorderBright,
    scrim = CyberColors.Scrim,
)

/**
 * The accent for whichever chain is active, available anywhere without
 * threading it through every composable.
 */
val LocalNetworkAccent = staticCompositionLocalOf { CyberColors.MainnetAccent }

/** True when the active chain moves real money. Gates confirmations and copy. */
val LocalIsMainnet = staticCompositionLocalOf { true }

fun accentFor(network: BitcoinNetwork): Color = when (network) {
    BitcoinNetwork.MAIN -> CyberColors.MainnetAccent
    BitcoinNetwork.TEST, BitcoinNetwork.TEST4 -> CyberColors.TestnetAccent
    BitcoinNetwork.SIGNET -> CyberColors.SignetAccent
    BitcoinNetwork.REGTEST -> CyberColors.RegtestAccent
}

@Composable
fun BitcoinCoreNodeTheme(
    network: BitcoinNetwork = BitcoinNetwork.MAIN,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge, with LIGHT system-bar icons: the app is now a
            // dark interface, and dark icons on a dark status bar are
            // invisible. Light icons on dark bar = readable.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalNetworkAccent provides accentFor(network),
        LocalIsMainnet provides network.isRealMoney,
    ) {
        MaterialTheme(
            colorScheme = CyberDarkScheme,
            typography = BitcoinCoreNodeTypography,
            shapes = BitcoinCoreNodeShapes,
            content = content,
        )
    }
}
