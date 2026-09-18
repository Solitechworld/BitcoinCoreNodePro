package com.solitech.bitcoincorenode.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberShapes
import com.solitech.bitcoincorenode.ui.theme.CyberType
import com.solitech.bitcoincorenode.ui.theme.LocalNetworkAccent
import com.solitech.bitcoincorenode.ui.theme.neonBorder
import kotlin.math.roundToInt

enum class CyberButtonStyle { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * The button — bubble-style with neon glow.
 *
 * Primary: solid neon accent fill with dark text.
 * Secondary: glass surface with neon border.
 * Ghost: transparent with subtle accent border.
 * Danger: red neon fill.
 *
 * 56dp minimum height for accessibility touch targets.
 * Bubble shape with generous rounding.
 */
@Composable
fun CyberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CyberButtonStyle = CyberButtonStyle.PRIMARY,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    fillWidth: Boolean = false,
) {
    val accent = LocalNetworkAccent.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val container = when {
        !enabled -> CyberColors.SurfaceElevated
        style == CyberButtonStyle.PRIMARY -> if (pressed) accent.copy(alpha = 0.85f) else accent
        style == CyberButtonStyle.DANGER ->
            if (pressed) CyberColors.RedDim else CyberColors.Red
        style == CyberButtonStyle.SECONDARY ->
            if (pressed) CyberColors.SurfaceHigh else CyberColors.SurfaceElevated
        else -> if (pressed) CyberColors.Surface else Color.Transparent
    }

    val label = when {
        !enabled -> CyberColors.TextDisabled
        style == CyberButtonStyle.PRIMARY || style == CyberButtonStyle.DANGER ->
            CyberColors.TextOnAccent
        style == CyberButtonStyle.SECONDARY -> CyberColors.TextPrimary
        else -> CyberColors.TextSecondary
    }

    val borderCol = when {
        !enabled -> CyberColors.Border
        style == CyberButtonStyle.PRIMARY -> accent.copy(alpha = 0.6f)
        style == CyberButtonStyle.DANGER -> CyberColors.Red.copy(alpha = 0.6f)
        style == CyberButtonStyle.SECONDARY -> accent.copy(alpha = 0.3f)
        else -> CyberColors.TextTertiary.copy(alpha = 0.2f)
    }

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = 56.dp)
            .clip(CyberShapes.BubbleButton)
            .background(container)
            .border(1.5.dp, borderCol, CyberShapes.BubbleButton)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = label, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            color = label,
        )
    }
}

/** Slightly darker, for pressed state. */
private fun darken(c: Color) = Color(
    red = c.red * 0.86f, green = c.green * 0.86f, blue = c.blue * 0.86f, alpha = c.alpha,
)

/**
 * Slide to confirm — neon glow version.
 *
 * Used for broadcasting a transaction. Neon border intensifies as
 * the user drags, with the track filling with accent colour.
 */
@Composable
fun SlideToConfirm(
    label: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = CyberColors.Red,
) {
    val haptics = LocalHapticFeedback.current
    var dragX by remember { mutableFloatStateOf(0f) }
    var trackWidth by remember { mutableFloatStateOf(1f) }

    val knob = with(LocalDensity.current) { 56.dp.toPx() }
    val progress = (dragX / (trackWidth - knob).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(progress, label = "slide")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CyberShapes.BubbleButton)
            .background(CyberColors.SurfaceElevated)
            .neonBorder(
                color = if (enabled) accent.copy(alpha = 0.3f + animatedProgress * 0.7f)
                else CyberColors.TextDisabled,
                shape = CyberShapes.BubbleButton,
                glowAlpha = 0.3f * animatedProgress,
            )
            .onSizeChanged { trackWidth = it.width.toFloat() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(accent.copy(alpha = 0.10f * animatedProgress))
        )
        Text(
            text = if (progress > 0.85f) "RELEASE TO SEND" else label.uppercase(),
            style = CyberType.HudLabel,
            color = if (enabled) accent.copy(alpha = 0.5f + animatedProgress * 0.5f)
            else CyberColors.TextDisabled,
            textAlign = TextAlign.Center,
        )

        Box(
            Modifier
                .offset { IntOffset(dragX.roundToInt(), 0) }
                .size(56.dp)
                .padding(4.dp)
                .clip(CyberShapes.BubbleButton)
                .background(if (enabled) accent.copy(alpha = 0.9f) else CyberColors.TextDisabled)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (progress > 0.85f) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onConfirmed()
                            }
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                        onHorizontalDrag = { _, delta ->
                            dragX = (dragX + delta).coerceIn(0f, (trackWidth - knob).coerceAtLeast(0f))
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("»", style = CyberType.ReadoutSmall, color = CyberColors.TextOnAccent)
        }
    }
}

/** Text field with neon border in the cyberspace aesthetic. */
@Composable
fun CyberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Masks the entry — for passcodes and other secrets. */
    obscure: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val accent = LocalNetworkAccent.current
    val borderColor = when {
        isError -> CyberColors.Red
        !enabled -> CyberColors.Border
        else -> accent.copy(alpha = 0.3f)
    }

    Column(modifier) {
        if (label != null) {
            Text(label.uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CyberShapes.Field)
                .background(CyberColors.Surface)
                .border(1.5.dp, borderColor, CyberShapes.Field)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = if (monospace) CyberType.Hash else LocalTextStyle.current,
                        color = CyberColors.TextDisabled,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    textStyle = (if (monospace) CyberType.Hash else LocalTextStyle.current)
                        .copy(color = CyberColors.TextPrimary),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = keyboardOptions,
                    visualTransformation = if (obscure) PasswordVisualTransformation()
                    else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (supportingText != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                supportingText,
                style = CyberType.Terminal,
                color = if (isError) CyberColors.Red else CyberColors.TextTertiary,
            )
        }
    }
}
