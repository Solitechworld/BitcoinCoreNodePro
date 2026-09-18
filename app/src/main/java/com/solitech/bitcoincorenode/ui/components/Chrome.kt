package com.solitech.bitcoincorenode.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solitech.bitcoincorenode.ui.nav.TopLevel
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import com.solitech.bitcoincorenode.ui.theme.LocalNetworkAccent

/**
 * Bottom navigation bar — cyberspace HUD style.
 *
 * Active tab has a neon indicator dot and accent-coloured icon.
 * Inactive tabs are muted. A thin neon rule separates the bar from content.
 */
@Composable
fun CyberBottomBar(
    selected: TopLevel?,
    onSelect: (TopLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalNetworkAccent.current
    Column(modifier.fillMaxWidth()) {
        // Neon rule at top of bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .drawBehind {
                    drawRect(accent.copy(alpha = 0.15f))
                }
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(CyberColors.Void)
                .navigationBarsPadding(),
        ) {
            TopLevel.entries.forEach { item ->
                val active = item == selected
                val dotAlpha by animateFloatAsState(
                    targetValue = if (active) 1f else 0f,
                    animationSpec = tween(200),
                    label = "tab-${item.name}",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(item) }
                        .height(58.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (active) accent else CyberColors.TextTertiary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.label.uppercase(),
                        style = CyberType.HudLabel.copy(fontSize = 9.sp, letterSpacing = 1.sp),
                        color = if (active) accent else CyberColors.TextTertiary,
                        textAlign = TextAlign.Center,
                    )
                    // Neon dot indicator
                    if (dotAlpha > 0.01f) {
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = dotAlpha))
                        )
                    }
                }
            }
        }
    }
}

/** Screen header: back affordance, neon-accented title, optional trailing chip. */
@Composable
fun CyberTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val accent = LocalNetworkAccent.current
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = accent,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onBack),
                )
                Spacer(Modifier.width(14.dp))
            }
            Text(
                title.uppercase(),
                style = CyberType.HudLabel.copy(fontSize = 18.sp, letterSpacing = 2.sp),
                color = accent,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) trailing()
        }
    }
}

/**
 * What a screen shows when there is nothing to show.
 *
 * Each one says what is missing, why, and what to do — never just "No data".
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title.uppercase(),
            style = CyberType.HudLabel.copy(fontSize = 12.sp),
            color = CyberColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            action()
        }
    }
}

/** Error surface with neon red border. */
@Composable
fun ErrorPanel(
    headline: String,
    detail: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    CyberPanel(modifier = modifier, accent = CyberColors.Red) {
        Text(headline.uppercase(), style = CyberType.HudLabel, color = CyberColors.Red)
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.TextSecondary,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}
