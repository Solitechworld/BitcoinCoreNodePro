package com.solitech.bitcoincorenode.ui.screens.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.prefs.AppLock
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import com.solitech.bitcoincorenode.ui.theme.LocalNetworkAccent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LockUiState(
    val checking: Boolean = false,
    val error: String? = null,
    val attempts: Int = 0,
    val biometrics: AppLock.BiometricAvailability = AppLock.BiometricAvailability.UNAVAILABLE,
)

@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val appLock: AppLock,
) : ViewModel() {

    private val _ui = MutableStateFlow(LockUiState())
    val ui: StateFlow<LockUiState> = _ui.asStateFlow()
    val lockState = appLock.state

    init {
        _ui.update { it.copy(biometrics = appLock.biometricAvailability()) }
    }

    fun setPasscode(code: String, confirm: String) = viewModelScope.launch {
        if (code != confirm) {
            _ui.update { it.copy(error = "Passcodes do not match.") }
            return@launch
        }
        _ui.update { it.copy(checking = true, error = null) }
        val problem = appLock.setPasscode(code)
        _ui.update { it.copy(checking = false, error = problem) }
    }

    fun submit(code: String) = viewModelScope.launch {
        _ui.update { it.copy(checking = true, error = null) }
        if (appLock.verify(code)) {
            appLock.unlock()
            _ui.update { it.copy(checking = false, attempts = 0) }
        } else {
            _ui.update {
                it.copy(
                    checking = false,
                    attempts = it.attempts + 1,
                    error = "Incorrect passcode.",
                )
            }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun onBiometricSuccess() = appLock.unlock()
}

/**
 * The lock screen — a modern take on the cyber aesthetic.
 *
 * - Animated wordmark with a slow scanning shimmer
 * - Capsule passcode indicator that fills, pulses and shakes on error
 * - Glass keys with press-scale animation and haptic ticks
 * - Biometric unlock orb with a breathing glow
 */
@Composable
fun AppLockScreen(viewModel: AppLockViewModel = hiltViewModel()) {
    val lockState by viewModel.lockState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    when (lockState) {
        AppLock.State.UNCONFIGURED -> SetupPasscode(ui, viewModel)
        AppLock.State.LOCKED -> Unlock(ui, viewModel)
        AppLock.State.UNLOCKED -> Unit
    }
}

/** Slow left-to-right shimmer that plays across the wordmark. */
@Composable
private fun ShimmeringWordmark(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(3200), RepeatMode.Restart),
        label = "shimmer-x",
    )
    // TextStyle carries the brush directly, so the highlight band sweeps
    // through the glyphs themselves rather than a box behind them.
    val brush = Brush.linearGradient(
        colors = listOf(color, Color.White, color),
        start = androidx.compose.ui.geometry.Offset(x * 400f, 0f),
        end = androidx.compose.ui.geometry.Offset(x * 400f + 180f, 60f),
    )
    Text(text, style = style.copy(brush = brush))
}

/**
 * Shake controller: bumped by error count, drives a decaying side-to-side
 * translation on whatever it is attached to.
 */
@Composable
private fun rememberShakeController(trigger: Int): Float {
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            shake.animateTo(
                0f,
                animationSpec = keyframes {
                    durationMillis = 420
                    0f at 0
                    -16f at 40
                    14f at 100
                    -10f at 160
                    6f at 220
                    0f at 300
                },
            )
        }
    }
    return shake.value
}

@Composable
private fun LockHeader(subtitle: String) {
    val accent = LocalNetworkAccent.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.25f), Color.Transparent),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("₿", style = CyberType.ReadoutSmall.copy(fontSize = 26.sp), color = accent)
        }
        Spacer(Modifier.height(14.dp))
        ShimmeringWordmark(
            text = "BITCOIN",
            style = CyberType.HudLabel.copy(fontSize = 15.sp, letterSpacing = 4.sp),
            color = CyberColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = CyberType.HudLabel.copy(fontSize = 9.sp, letterSpacing = 2.5.sp),
            color = accent.copy(alpha = 0.8f),
        )
    }
}

/**
 * Capsule passcode indicator. Filled segments carry a soft accent glow; the
 * next empty segment breathes; on error everything flashes red and the caller
 * shakes the row.
 */
@Composable
fun PasscodeCapsules(
    length: Int,
    total: Int,
    isError: Boolean = false,
) {
    val accent = LocalNetworkAccent.current
    val breath = rememberInfiniteTransition(label = "breath")
    val nextPulse by breath.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "next-pulse",
    )
    val errorColor = remember { Animatable(0f) }
    LaunchedEffect(isError) {
        if (isError) {
            errorColor.snapTo(1f)
            errorColor.animateTo(0f, tween(1200))
        }
    }
    val err = errorColor.value

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until total) {
            val filled = i < length
            val isNext = i == length
            val fill = when {
                filled -> accent.copy(alpha = 0.95f)
                isNext -> accent.copy(alpha = nextPulse * 0.35f)
                else -> Color.Transparent
            }
            val border = when {
                err > 0.02f && (filled || isNext) ->
                    androidx.compose.ui.graphics.lerp(accent, CyberColors.Red, err)
                filled -> accent
                isNext -> accent.copy(alpha = nextPulse * 0.7f)
                else -> CyberColors.TextTertiary.copy(alpha = 0.35f)
            }
            // Just-filled pops briefly.
            val scale by animateFloatAsState(
                targetValue = if (i == length - 1) 1.12f else 1f,
                animationSpec = tween(160),
                label = "cap-$i",
            )
            Box(
                Modifier
                    .size(width = (34 * scale).dp, height = (10 * scale).dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(fill)
                    .border(1.5.dp, border, RoundedCornerShape(5.dp)),
            )
        }
    }
}

/**
 * Modern glass keypad. Keys scale down and brighten while pressed, and every
 * key tick is accompanied by a light haptic — the feedback a physical keypad
 * has and on-screen ones usually lack.
 */
@Composable
fun GlassKeypad(
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
    showConfirm: Boolean,
) {
    val haptics = LocalHapticFeedback.current
    val accent = LocalNetworkAccent.current

    @Composable
    fun Key(
        onClick: () -> Unit,
        bright: Boolean = false,
        dim: Boolean = false,
        wide: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(110), label = "key-scale")

        Box(
            modifier = Modifier
                .size(width = if (wide) 236.dp else 74.dp, height = 62.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(18.dp))
                .background(
                    when {
                        bright -> accent.copy(alpha = 0.16f)
                        pressed -> CyberColors.SurfaceElevated.copy(alpha = 0.9f)
                        else -> CyberColors.SurfaceElevated.copy(alpha = 0.55f)
                    },
                )
                .border(
                    width = 1.dp,
                    color = when {
                        bright -> accent.copy(alpha = 0.8f)
                        dim -> CyberColors.TextTertiary.copy(alpha = 0.15f)
                        else -> CyberColors.TextTertiary.copy(alpha = 0.22f)
                    },
                    shape = RoundedCornerShape(18.dp),
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { (digit, letters) ->
                    Key(onClick = { onDigit(digit) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                digit,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 21.sp,
                                ),
                                color = CyberColors.TextPrimary,
                            )
                            if (letters.isNotEmpty()) {
                                Text(
                                    letters,
                                    style = CyberType.HudLabel.copy(fontSize = 7.sp, letterSpacing = 1.5.sp),
                                    color = CyberColors.TextTertiary.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Key(onClick = onDelete, dim = true) {
                Text("⌫", style = MaterialTheme.typography.titleMedium, color = CyberColors.TextSecondary)
            }
            Key(onClick = { onDigit("0") }) {
                Text(
                    "0",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium, fontSize = 21.sp,
                    ),
                    color = CyberColors.TextPrimary,
                )
            }
            Key(onClick = onConfirm, bright = showConfirm, dim = !showConfirm) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (showConfirm) accent else CyberColors.TextDisabled,
                )
            }
        }
    }
}

@Composable
private fun SetupPasscode(ui: LockUiState, vm: AppLockViewModel) {
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(0) } // 0 = enter, 1 = confirm
    val accent = LocalNetworkAccent.current
    val shakeX = rememberShakeController(ui.attempts + (ui.error?.hashCode() ?: 0))

    // A mismatch resets both entries: making the user retype from the start is
    // safer than keeping a passcode on screen the user believes was rejected.
    LaunchedEffect(ui.error) {
        if (ui.error != null) {
            code = ""
            confirm = ""
            step = 0
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LockHeader(subtitle = "SECURE SETUP")

        Spacer(Modifier.height(30.dp))

        // Step pills
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { i ->
                Box(
                    Modifier
                        .size(width = 26.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step) accent else CyberColors.TextTertiary.copy(alpha = 0.25f)),
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            if (step == 0) "Create a 6-digit passcode" else "Confirm your passcode",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberColors.TextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(26.dp))

        Box(Modifier.graphicsLayer { translationX = shakeX }) {
            PasscodeCapsules(
                length = if (step == 0) code.length else confirm.length,
                total = AppLock.MIN_LENGTH,
                isError = ui.error != null,
            )
        }

        Spacer(Modifier.height(30.dp))

        GlassKeypad(
            onDigit = { digit ->
                if (step == 0 && code.length < AppLock.MIN_LENGTH) {
                    code += digit
                    if (code.length == AppLock.MIN_LENGTH) step = 1
                } else if (step == 1 && confirm.length < AppLock.MIN_LENGTH) {
                    confirm += digit
                }
            },
            onDelete = {
                if (step == 1 && confirm.isNotEmpty()) {
                    confirm = confirm.dropLast(1)
                } else if (step == 0 && code.isNotEmpty()) {
                    code = code.dropLast(1)
                }
                if (step == 1 && confirm.isEmpty()) step = 0
            },
            onConfirm = {
                if (step == 1 && confirm.length == AppLock.MIN_LENGTH) {
                    vm.setPasscode(code, confirm)
                }
            },
            showConfirm = step == 1 && confirm.length == AppLock.MIN_LENGTH,
        )

        ui.error?.let {
            Spacer(Modifier.height(18.dp))
            Text(it, style = CyberType.Terminal, color = CyberColors.Red, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(26.dp))
        Text(
            "This passcode locks the app. It does not encrypt your wallet — your coins " +
                "are protected by the wallet passphrase, set in Wallet tools.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp).alpha(0.85f),
        )
    }
}

@Composable
private fun Unlock(ui: LockUiState, vm: AppLockViewModel) {
    var code by remember { mutableStateOf("") }
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val accent = LocalNetworkAccent.current
    val shakeX = rememberShakeController(ui.attempts)

    // Auto-clear the typed code after a failed attempt so the shake lands on
    // empty capsules — the passcode itself never lingers on screen.
    LaunchedEffect(ui.attempts) {
        if (ui.attempts > 0) code = ""
    }

    // Prompt for biometrics as soon as the screen appears.
    LaunchedEffect(Unit) {
        if (activity != null && ui.biometrics == AppLock.BiometricAvailability.AVAILABLE) {
            promptBiometric(activity) { vm.onBiometricSuccess() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LockHeader(subtitle = "NODE LOCKED")

        Spacer(Modifier.height(34.dp))

        Box(Modifier.graphicsLayer { translationX = shakeX }) {
            PasscodeCapsules(
                length = code.length,
                total = AppLock.MIN_LENGTH,
                isError = ui.error != null,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            when {
                ui.error != null -> "ACCESS DENIED"
                ui.checking -> "VERIFYING"
                code.isEmpty() -> "ENTER PASSCODE"
                else -> "• • •"
            },
            style = CyberType.HudLabel.copy(fontSize = 9.sp, letterSpacing = 3.sp),
            color = if (ui.error != null) CyberColors.Red else CyberColors.TextTertiary,
        )

        Spacer(Modifier.height(30.dp))

        GlassKeypad(
            onDigit = { digit ->
                vm.clearError()
                if (code.length < AppLock.MIN_LENGTH) {
                    code += digit
                    if (code.length == AppLock.MIN_LENGTH) {
                        vm.submit(code)
                        code = ""
                    }
                }
            },
            onDelete = { if (code.isNotEmpty()) code = code.dropLast(1) },
            onConfirm = {},
            showConfirm = false,
        )

        if (ui.biometrics == AppLock.BiometricAvailability.AVAILABLE && activity != null) {
            Spacer(Modifier.height(26.dp))
            BiometricOrb { promptBiometric(activity) { vm.onBiometricSuccess() } }
        }

        ui.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = CyberType.Terminal, color = CyberColors.Red, textAlign = TextAlign.Center)
        }

        if (ui.attempts >= 5) {
            Spacer(Modifier.height(16.dp))
            Text(
                "If you have forgotten this passcode there is no way to recover it. " +
                    "Reinstall the app and restore your wallet from its seed phrase.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.Amber,
                textAlign = TextAlign.Center,
            )
        }

        if (ui.biometrics == AppLock.BiometricAvailability.NONE_ENROLLED) {
            Spacer(Modifier.height(16.dp))
            Text(
                "No fingerprint or face enrolled — passcode only.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Breathing biometric orb. */
@Composable
private fun BiometricOrb(onClick: () -> Unit) {
    val breath = rememberInfiniteTransition(label = "orb")
    val glow by breath.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "orb-glow",
    )
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .size(68.dp)
            .graphicsLayer {
                val g = glow
                scaleX = 0.96f + g * 0.08f
                scaleY = 0.96f + g * 0.08f
            }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(CyberColors.Violet.copy(alpha = 0.28f * glow), Color.Transparent),
                ),
            )
            .border(1.5.dp, CyberColors.Violet.copy(alpha = 0.4f + 0.4f * glow), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Fingerprint,
            contentDescription = "Use biometrics",
            tint = CyberColors.Violet.copy(alpha = 0.5f + 0.5f * glow),
            modifier = Modifier.size(30.dp),
        )
    }
}

/**
 * System biometric prompt.
 *
 * DEVICE_CREDENTIAL is allowed alongside BIOMETRIC_STRONG so the sheet offers
 * the phone's own PIN/pattern as a fallback.
 */
private fun promptBiometric(activity: FragmentActivity, onSuccess: () -> Unit) {
    BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ) {
                onSuccess()
            }
        },
    ).authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Bitcoin Core Node")
            .setSubtitle("Use your fingerprint, face, or device passcode")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
    )
}
