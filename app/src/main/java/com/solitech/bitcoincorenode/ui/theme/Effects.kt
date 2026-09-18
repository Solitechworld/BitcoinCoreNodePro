package com.solitech.bitcoincorenode.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Cyberspace visual effects — neon glow, scanlines, cyber grid, vignette.
 *
 * All drawn in drawBehind/drawWithCache — no bitmaps, no RenderEffect, no
 * saved layers. Glow is faked with layered strokes at decreasing alpha.
 */

/** Neon edge: a bright inner stroke plus soft outer glow layers. */
fun Modifier.neonBorder(
    color: Color,
    shape: Shape,
    borderWidth: Dp = 1.5.dp,
    glowRadius: Dp = 8.dp,
    glowAlpha: Float = 0.3f,
): Modifier = this
    .background(CyberColors.Surface, shape)
    .then(
        if (glowRadius > 0.dp && glowAlpha > 0f) {
            Modifier.drawWithCache {
                val glowPx = glowRadius.toPx()
                val borderPx = borderWidth.toPx()
                val steps = 4
                onDrawBehind {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val path = when (outline) {
                        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                        is Outline.Generic -> outline.path
                    }
                    // Outer glow layers
                    for (i in steps downTo 1) {
                        val frac = i.toFloat() / steps
                        val width = borderPx + glowPx * frac
                        val alpha = glowAlpha * (1f - frac) * 0.6f
                        drawPath(path, color = color.copy(alpha = alpha), style = Stroke(width = width))
                    }
                    // Bright inner border
                    drawPath(path, color = color.copy(alpha = 0.7f), style = Stroke(width = borderPx))
                }
            }
        } else {
            Modifier.border(borderWidth, color.copy(alpha = 0.5f), shape)
        }
    )

/** A hairline rule with subtle glow. */
fun Modifier.neonRule(color: Color, thickness: Dp = 1.dp): Modifier = this.drawBehind {
    val h = thickness.toPx()
    drawRect(
        color = color.copy(alpha = 0.3f),
        topLeft = Offset(0f, size.height - h),
        size = Size(size.width, h),
    )
}

/** Blueprint grid — subtle lines across the surface. */
fun Modifier.cyberGrid(
    color: Color = CyberColors.Grid,
    spacing: Dp = 24.dp,
): Modifier = this.drawWithCache {
    val spacingPx = spacing.toPx()
    onDrawBehind {
        val gridColor = color.copy(alpha = 0.15f)
        var x = spacingPx
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5f)
            x += spacingPx
        }
        var y = spacingPx
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
            y += spacingPx
        }
    }
}

/** CRT scanlines — subtle horizontal lines for the cyber aesthetic. */
fun Modifier.scanlines(alpha: Float = 0.04f, spacing: Dp = 3.dp): Modifier = this.drawWithCache {
    val spacingPx = spacing.toPx()
    onDrawBehind {
        var y = 0f
        while (y < size.height) {
            drawLine(
                CyberColors.TextPrimary.copy(alpha = alpha),
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 0.5f,
            )
            y += spacingPx
        }
    }
}

/** Vignette — darkens edges for a CRT/cockpit feel. */
fun Modifier.vignette(strength: Float = 0.4f): Modifier = this.drawWithCache {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = kotlin.math.sqrt(cx * cx + cy * cy)
    onDrawBehind {
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.Transparent, CyberColors.Void.copy(alpha = strength)),
                center = Offset(cx, cy),
                radius = maxR,
            )
        )
    }
}

/** Panel sheen — subtle gradient from top to bottom. */
fun Modifier.panelSheen(
    top: Color = CyberColors.SurfaceElevated.copy(alpha = 0.3f),
    bottom: Color = Color.Transparent,
): Modifier = this.drawWithCache {
    onDrawBehind {
        drawRect(
            Brush.verticalGradient(
                colors = listOf(top, bottom),
                startY = 0f,
                endY = size.height,
            )
        )
    }
}

@Composable
fun rememberPulse(
    durationMillis: Int = 2600,
    min: Float = 0.55f,
    max: Float = 1f,
): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    return transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    ).value
}

/** A travelling highlight for indeterminate progress. 0f..1f, loops. */
@Composable
fun rememberSweep(durationMillis: Int = 1800): Float {
    val transition = rememberInfiniteTransition(label = "sweep")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep-position",
    ).value
}
