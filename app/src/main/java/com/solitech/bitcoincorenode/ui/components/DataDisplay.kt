package com.solitech.bitcoincorenode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.solitech.bitcoincorenode.core.model.DisplayUnit
import com.solitech.bitcoincorenode.core.model.Sats
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberShapes
import com.solitech.bitcoincorenode.ui.theme.CyberType

/**
 * Renders a Bitcoin address so a human can actually verify it.
 *
 * ## Why this is not just Text(address)
 *
 * Verifying an address means comparing ~42 characters of base32 against another
 * screen, character by character. As one unbroken string that is a task people
 * do badly and know they do badly, so in practice they check the first four and
 * last four characters and hope. Address-substitution malware exists precisely
 * because of that habit.
 *
 * Grouping into blocks of four with alternating tint turns it into comparing
 * eleven short tokens, which people are measurably better at. This is the same
 * treatment Bitcoin Core's own GUI and every serious desktop wallet uses, and
 * it is one of the highest-value-per-line pieces of code in this app.
 *
 * The human-readable prefix (`bc1`) is shown in the accent colour and never
 * grouped — it is the network identifier and the first thing that should look
 * wrong if it is wrong.
 */
@Composable
fun AddressText(
    address: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CyberType.Hash,
    emphasiseEnds: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val annotated = remember(address, emphasiseEnds) {
        buildAddressAnnotation(address, emphasiseEnds)
    }
    Text(
        text = annotated,
        style = style,
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
    )
}

private fun buildAddressAnnotation(address: String, emphasiseEnds: Boolean): AnnotatedString =
    buildAnnotatedString {
        if (address.isEmpty()) return@buildAnnotatedString

        // Bech32/bech32m: split at the last '1', which is the separator by spec.
        val sepIndex = address.lastIndexOf('1')
        val isBech32 = sepIndex in 1..4 &&
            (address.startsWith("bc", true) || address.startsWith("tb", true) ||
                address.startsWith("bcrt", true))

        val prefix = if (isBech32) address.substring(0, sepIndex + 1) else ""
        val body = if (isBech32) address.substring(sepIndex + 1) else address

        if (prefix.isNotEmpty()) {
            withStyle(SpanStyle(color = CyberColors.Cyan, fontWeight = FontWeight.Medium)) {
                append(prefix)
            }
        }

        val groups = body.chunked(4)
        groups.forEachIndexed { index, group ->
            val isEnd = emphasiseEnds && (index == 0 || index == groups.lastIndex)
            val color = when {
                isEnd -> CyberColors.TextPrimary
                index % 2 == 0 -> CyberColors.TextPrimary.copy(alpha = 0.92f)
                else -> CyberColors.TextSecondary
            }
            withStyle(
                SpanStyle(
                    color = color,
                    fontWeight = if (isEnd) FontWeight.SemiBold else FontWeight.Normal,
                )
            ) { append(group) }
            if (index != groups.lastIndex) {
                withStyle(SpanStyle(color = Color.Transparent)) { append(" ") }
            }
        }
    }

/**
 * An amount, with the insignificant leading zeros dimmed.
 *
 * `0.00012340 BTC` is eight decimal places of which the first four carry no
 * information, and at a glance they make every small amount look like every
 * other small amount. Dimming everything before the first significant digit
 * makes the magnitude readable at a glance without hiding a single character —
 * nothing is truncated, nothing is rounded, it is only tinted.
 *
 * Rounding or abbreviating an amount in a wallet is not on the table.
 */
@Composable
fun AmountText(
    amount: Sats,
    unit: DisplayUnit,
    modifier: Modifier = Modifier,
    style: TextStyle = CyberType.ReadoutSmall,
    color: Color = CyberColors.TextPrimary,
    showUnit: Boolean = true,
    signed: Boolean = false,
) {
    val text = remember(amount, unit, signed, showUnit) {
        buildAmountAnnotation(amount, unit, signed, showUnit, color)
    }
    Text(text = text, style = style, modifier = modifier)
}

private fun buildAmountAnnotation(
    amount: Sats,
    unit: DisplayUnit,
    signed: Boolean,
    showUnit: Boolean,
    baseColor: Color,
): AnnotatedString = buildAnnotatedString {
    val body = unit.format(amount.absolute, withSymbol = false)
    val dim = baseColor.copy(alpha = 0.35f)

    if (signed) {
        withStyle(SpanStyle(color = if (amount.isNegative) CyberColors.Outgoing else CyberColors.Incoming)) {
            append(if (amount.isNegative) "−" else "+")
        }
    }

    if (unit == DisplayUnit.SATS) {
        withStyle(SpanStyle(color = baseColor)) { append(body) }
    } else {
        // Find the first significant digit; everything before it is scaffolding.
        var firstSignificant = -1
        for (i in body.indices) {
            val c = body[i]
            if (c in '1'..'9') { firstSignificant = i; break }
        }
        if (firstSignificant <= 0) {
            withStyle(SpanStyle(color = baseColor)) { append(body) }
        } else {
            withStyle(SpanStyle(color = dim)) { append(body.substring(0, firstSignificant)) }
            withStyle(SpanStyle(color = baseColor)) { append(body.substring(firstSignificant)) }
        }
    }

    if (showUnit) {
        withStyle(SpanStyle(color = baseColor.copy(alpha = 0.55f))) { append(" ${unit.label}") }
    }
}

/** Label on the left, monospace value on the right. The bread and butter row. */
@Composable
fun DataRow(
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = CyberColors.TextPrimary,
    value: String? = null,
    onValueClick: (() -> Unit)? = null,
    valueContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onValueClick != null) Modifier.clickable(onClick = onValueClick) else Modifier)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = CyberType.HudLabel,
            color = CyberColors.TextTertiary,
        )
        Spacer(Modifier.width(12.dp))
        if (valueContent != null) {
            valueContent()
        } else {
            Text(
                text = value.orEmpty(),
                style = CyberType.Hash,
                color = valueColor,
            )
        }
    }
}

/** A single big number with a caption. The dashboard is a grid of these. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = CyberColors.Cyan,
    caption: String? = null,
) {
    Column(
        modifier = modifier
            .clip(CyberShapes.Chip)
            .background(CyberColors.SurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label.uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
        Spacer(Modifier.height(4.dp))
        Text(value, style = CyberType.ReadoutSmall, color = accent)
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(caption, style = CyberType.Terminal, color = CyberColors.TextTertiary)
        }
    }
}

/**
 * A segmented bar meter.
 *
 * Segments rather than a smooth fill because discrete blocks are easier to read
 * at a glance and to compare between two meters — and because at these sizes a
 * smooth gradient bar reads as decoration while a segmented one reads as an
 * instrument.
 */
@Composable
fun SignalMeter(
    fraction: Float,
    modifier: Modifier = Modifier,
    segments: Int = 20,
    activeColor: Color = CyberColors.Cyan,
    inactiveColor: Color = CyberColors.Border,
) {
    val filled = (fraction.coerceIn(0f, 1f) * segments).toInt()
    Row(
        modifier = modifier.height(10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(segments) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(if (i < filled) activeColor else inactiveColor)
            )
        }
    }
}

/** Fixed-width label for a network or chain. */
@Composable
fun NetworkBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(CyberShapes.Chip)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label.uppercase(), style = CyberType.HudLabel, color = color)
    }
}
