package com.solitech.bitcoincorenode.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shapes — soft bubbles for the cyberspace aesthetic.
 *
 * Generous radii on cards (bubbles), fully rounded pills for buttons.
 * The bubble shapes give the cyberpunk HUD a friendly, approachable feel
 * while maintaining the dark neon aesthetic.
 */
object CyberShapes {
    /** Bubble cards with generous rounding. */
    val Panel = RoundedCornerShape(24.dp)
    val PanelAll = RoundedCornerShape(24.dp)

    /** Small status pills. */
    val Chip = RoundedCornerShape(percent = 50)

    /** The signature shape: a full pill, whatever the height. */
    val Button = RoundedCornerShape(percent = 50)

    /** Bottom sheets: rounded at the top only. */
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Text fields — rounded, but not a pill, so multi-line still reads well. */
    val Field = RoundedCornerShape(16.dp)

    /** Circle for passcode dots and biometric button. */
    val Circle = RoundedCornerShape(percent = 50)

    /** Bubble button with extra rounding. */
    val BubbleButton = RoundedCornerShape(28.dp)
}

val BitcoinCoreNodeShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
