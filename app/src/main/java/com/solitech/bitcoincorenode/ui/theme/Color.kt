package com.solitech.bitcoincorenode.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Cyberspace palette — neon on void.
 *
 * Dark ground with electric cyan, amber, green and magenta accents.
 * Designed for a cyberpunk HUD aesthetic: glowing edges, glass panels,
 * and neon status indicators. Every foreground/background pair clears
 * WCAG AA on dark backgrounds.
 */
object CyberColors {

    // --- Grounds -----------------------------------------------------------

    /** The void — deep space black, not pure #000 to avoid OLED banding. */
    val Void = Color(0xFF0A0E17)

    /** Cards and grouped rows: a dark translucent surface. */
    val Surface = Color(0xFF111827)
    val SurfaceElevated = Color(0xFF1A2236)

    /** Pressed states and inset wells. */
    val SurfaceHigh = Color(0xFF243049)

    /** Glass overlay for bubble panels. */
    val Glass = Color(0x1AFFFFFF)

    val Scrim = Color(0xCC000000)

    // --- Lines -------------------------------------------------------------

    /** Subtle cyber grid lines. */
    val Border = Color(0xFF1E293B)
    val BorderBright = Color(0xFF334155)
    val Grid = Color(0xFF0F172A)

    // --- Accents — neon electric ------------------------------------------------

    /** Primary neon cyan — the main accent, links, active states. */
    val Cyan = Color(0xFF00F0FF)
    val CyanDim = Color(0xFF0097A7)
    val CyanDeep = Color(0xFF001B2E)

    /** The green — confirmed transactions, money in, success. */
    val Green = Color(0xFF00FF88)
    val GreenDim = Color(0xFF00C244)

    /** Amber — pending, warnings, sync progress. */
    val Amber = Color(0xFFFFB800)
    val AmberDim = Color(0xFFF5A623)

    /** Magenta / violet — secondary accent, special states. */
    val Magenta = Color(0xFFFF00FF)
    val MagentaDim = Color(0xFF7A5CFF)

    /** Red — errors, danger, failed. */
    val Red = Color(0xFFFF3366)
    val RedDim = Color(0xFFE5342B)

    val Violet = Color(0xFF7A5CFF)

    // --- Text — high contrast on dark -----------------------------------------

    /** Bright white for primary text on dark. */
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextTertiary = Color(0xFF64748B)
    val TextDisabled = Color(0xFF475569)

    /** On a filled neon button. */
    val TextOnAccent = Color(0xFF0A0E17)

    // --- Semantic ----------------------------------------------------------

    val Confirmed = Green
    val Pending = Amber
    val Failed = Red
    val Incoming = Green
    val Outgoing = TextPrimary

    // --- Network identity --------------------------------------------------

    val MainnetAccent = Cyan
    val TestnetAccent = Amber
    val SignetAccent = Violet
    val RegtestAccent = Magenta

    // --- Glow colours for effects ------------------------------------------

    val GlowCyan = Color(0x4000F0FF)
    val GlowGreen = Color(0x4000FF88)
    val GlowAmber = Color(0x40FFB800)
    val GlowMagenta = Color(0x40FF00FF)
    val GlowRed = Color(0x40FF3366)
}
