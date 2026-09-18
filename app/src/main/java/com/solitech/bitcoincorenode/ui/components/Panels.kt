package com.solitech.bitcoincorenode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberShapes
import com.solitech.bitcoincorenode.ui.theme.CyberType
import com.solitech.bitcoincorenode.ui.theme.LocalNetworkAccent
import com.solitech.bitcoincorenode.ui.theme.neonBorder
import com.solitech.bitcoincorenode.ui.theme.panelSheen
import com.solitech.bitcoincorenode.ui.theme.rememberPulse
import com.solitech.bitcoincorenode.ui.theme.scanlines

/**
 * The bubble panel — the workhorse container of the cyberspace design.
 *
 * Glass-like dark surface with neon border glow, scanlines, and sheen.
 * [accent] tints the border; leave it null to inherit the current network's
 * accent.
 */
@Composable
fun CyberPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    glow: Boolean = true,
    textured: Boolean = true,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val edge = accent ?: LocalNetworkAccent.current
    Column(
        modifier = modifier
            .clip(CyberShapes.Panel)
            .background(CyberColors.Surface)
            .panelSheen()
            .then(if (textured) Modifier.scanlines(alpha = 0.03f) else Modifier)
            .then(
                if (glow) Modifier.neonBorder(
                    color = edge,
                    shape = CyberShapes.Panel,
                    glowAlpha = 0.25f,
                )
                else Modifier.neonBorder(
                    color = CyberColors.Border,
                    shape = CyberShapes.Panel,
                    glowRadius = 0.dp,
                    glowAlpha = 0f,
                )
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A section header: wide-tracked uppercase label, a neon rule, optional trailing.
 *
 * The rule is a glowing line that runs to the edge.
 */
@Composable
fun HudSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val edge = accent ?: LocalNetworkAccent.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 12.dp)
                .background(edge)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label.uppercase(),
            style = CyberType.HudLabel,
            color = edge,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(edge.copy(alpha = 0.25f))
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/**
 * A status dot — neon pulse for live states.
 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 8.dp,
    pulsing: Boolean = false,
) {
    val alpha = if (pulsing) rememberPulse() else 1f
    Box(
        modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

/** Dot + label — the standard "state of one thing" readout. */
@Composable
fun StatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(CyberShapes.Chip)
            .background(color.copy(alpha = 0.10f))
            .neonBorder(color.copy(alpha = 0.45f), CyberShapes.Chip, glowRadius = 3.dp, glowAlpha = 0.2f)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = color, size = 6.dp, pulsing = pulsing)
        Text(text = label.uppercase(), style = CyberType.HudLabel, color = color)
    }
}

/** Thin divider matching the panel language. */
@Composable
fun CyberDivider(
    modifier: Modifier = Modifier,
    color: Color = CyberColors.Border,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}
