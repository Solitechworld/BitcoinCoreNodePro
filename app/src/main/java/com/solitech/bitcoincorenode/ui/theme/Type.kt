package com.solitech.bitcoincorenode.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.sp

/**
 * Typography for the cyberspace aesthetic.
 *
 * Two families, with a hard rule about which is which:
 *
 * **Monospace for anything a user might compare, copy, or read character by
 * character** — addresses, txids, hashes, amounts, block heights, hex.
 *
 * **Sans for prose** — explanations, warnings, buttons.
 *
 * The scale is deliberately compact: dense HUD readouts, not large-print
 * prose. Monospace never drops below 11sp.
 */
object CyberType {
    val Mono = FontFamily.Monospace
    val Sans = FontFamily.SansSerif
    val Display = FontFamily.SansSerif

    /** Section headers — uppercase, wide-tracked, HUD style. */
    val HudLabel = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 1.4.sp,
    )

    /** The balance — big, heavy, tightly tracked, neon hero. */
    val Readout = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Black,
        fontSize = 36.sp,
        lineHeight = 41.sp,
        letterSpacing = (-1.2).sp,
    )

    val ReadoutSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.4).sp,
    )

    /** Addresses, txids, descriptors — monospace, never below 11sp. */
    val Hash = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.96f),
    )

    /** Log output and raw RPC — terminal green-on-black style. */
    val Terminal = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

val BitcoinCoreNodeTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = CyberType.Display, fontWeight = FontWeight.Black,
        fontSize = 36.sp, lineHeight = 41.sp, letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = CyberType.Display, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = CyberType.Display, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = CyberType.Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 19.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = CyberType.Sans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 17.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = CyberType.Sans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = CyberType.Sans, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = CyberType.Sans, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = CyberType.Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = CyberType.Mono, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 1.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = CyberType.Mono, fontWeight = FontWeight.Normal,
        fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.1.sp,
    ),
)
