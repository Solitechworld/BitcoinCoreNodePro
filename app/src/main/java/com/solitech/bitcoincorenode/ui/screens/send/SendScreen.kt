package com.solitech.bitcoincorenode.ui.screens.send

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solitech.bitcoincorenode.core.model.DisplayUnit
import com.solitech.bitcoincorenode.core.model.Sats
import com.solitech.bitcoincorenode.ui.components.AddressText
import com.solitech.bitcoincorenode.ui.components.AmountText
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberTextField
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.DataRow
import com.solitech.bitcoincorenode.ui.components.DonatePanel
import com.solitech.bitcoincorenode.ui.components.ErrorPanel
import com.solitech.bitcoincorenode.ui.components.HudSectionHeader
import com.solitech.bitcoincorenode.ui.components.SlideToConfirm
import com.solitech.bitcoincorenode.ui.components.StatusChip
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberShapes
import com.solitech.bitcoincorenode.ui.theme.CyberType
import java.util.Locale

@Composable
fun SendScreen(
    walletName: String,
    prefillUri: String?,
    onDone: () -> Unit,
    viewModel: SendViewModel = hiltViewModel(),
) {
    LaunchedEffect(walletName) { viewModel.bind(walletName, prefillUri) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().imePadding()) {
        CyberTopBar(
            title = when (state.stage) {
                SendStage.COMPOSING -> "Send"
                SendStage.REVIEWING -> "Review"
                SendStage.SIGNING -> "Signing"
                SendStage.BROADCASTING -> "Broadcasting"
                SendStage.SENT -> "Sent"
            },
            onBack = if (state.stage == SendStage.REVIEWING) viewModel::backToComposing else onDone,
        )

        when (state.stage) {
            SendStage.COMPOSING -> ComposeStep(state, viewModel)
            SendStage.SENT -> SentStep(state, onDone)
            else -> ReviewStep(state, viewModel)
        }
    }
}

@Composable
private fun ComposeStep(state: SendState, vm: SendViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.error?.let { ErrorPanel("Could not build the transaction", it) }

        CyberPanel(Modifier.fillMaxWidth(), glow = false) {
            CyberTextField(
                value = state.address,
                onValueChange = vm::onAddressChanged,
                label = "Pay to",
                placeholder = "bc1q… or scan a QR code",
                monospace = true,
                isError = state.addressValid == false,
                supportingText = state.addressError,
            )
            if (state.addressValid == true) {
                Spacer(Modifier.height(10.dp))
                // Re-display the address in grouped form once it validates, so
                // the user verifies what the node accepted rather than what
                // they think they typed.
                AddressText(state.address)
            }
        }

        CyberPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Amount".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
                Text(
                    if (state.sendMax) "MAX ON" else "SEND MAX",
                    style = CyberType.HudLabel,
                    color = if (state.sendMax) CyberColors.Amber else CyberColors.Cyan,
                    modifier = Modifier.clickable { vm.toggleSendMax() },
                )
            }
            Spacer(Modifier.height(9.dp))
            if (state.sendMax) {
                Text(
                    "Everything spendable, minus the fee",
                    style = CyberType.ReadoutSmall,
                    color = CyberColors.Amber,
                )
            } else {
                CyberTextField(
                    value = state.amountInput,
                    onValueChange = vm::onAmountChanged,
                    placeholder = if (state.unit == DisplayUnit.SATS) "0" else "0.00000000",
                    monospace = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = state.amount?.let {
                        // Always echo the other unit. Cross-checking magnitude
                        // in two units is what catches a slipped decimal point.
                        if (state.unit == DisplayUnit.SATS) "${it.toBtcStringTrimmed()} BTC"
                        else DisplayUnit.SATS.format(it)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            DataRow("Spendable") {
                AmountText(state.spendable, state.unit, style = CyberType.Hash)
            }
        }

        FeePanel(state, vm)

        CoinControlPanel(state, vm)

        CyberButton(
            text = "Review transaction",
            onClick = vm::build,
            enabled = state.canBuild,
            fillWidth = true,
        )

        Spacer(Modifier.height(8.dp))
        DonatePanel()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeePanel(state: SendState, vm: SendViewModel) {
    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        HudSectionHeader("Fee rate")
        Spacer(Modifier.height(10.dp))

        if (state.feeEstimates.isEmpty()) {
            // Honest failure. A fabricated default here is how transactions get
            // stuck for a week.
            Text(
                "Your node can't estimate fees yet. That's normal on a node that has " +
                    "just started, or one running in blocks-only mode — it hasn't seen " +
                    "enough mempool history. Set a rate manually, or wait.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.Amber,
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "~10 min", 3 to "~30 min", 6 to "~1 h", 24 to "~4 h").forEach { (blocks, label) ->
                    val rate = state.feeEstimates[blocks]?.satPerVb
                    val selected = rate != null &&
                        kotlin.math.abs(rate - state.feeRateSatPerVb) < 0.01 && !state.feeRateManual
                    Column(
                        Modifier
                            .weight(1f)
                            .background(
                                if (selected) CyberColors.Cyan.copy(alpha = 0.14f)
                                else CyberColors.SurfaceElevated,
                                CyberShapes.Chip,
                            )
                            .clickable(enabled = rate != null) { rate?.let { vm.setFeeRate(it, manual = false) } }
                            .padding(vertical = 9.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(label, style = CyberType.HudLabel,
                            color = if (selected) CyberColors.Cyan else CyberColors.TextTertiary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            rate?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                            style = CyberType.Hash,
                            color = if (selected) CyberColors.Cyan else CyberColors.TextPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        CyberTextField(
            value = if (state.feeRateManual) state.feeRateSatPerVb.toString() else "",
            onValueChange = { it.toDoubleOrNull()?.let(vm::setFeeRate) },
            label = "custom sat/vB",
            placeholder = String.format(Locale.US, "%.1f", state.feeRateSatPerVb),
            monospace = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = if (state.feeRateSatPerVb < state.minRelaySatPerVb)
                "Below this node's minimum relay fee of " +
                    String.format(Locale.US, "%.2f", state.minRelaySatPerVb) +
                    " sat/vB — it will not be relayed."
            else null,
            isError = state.feeRateSatPerVb < state.minRelaySatPerVb,
        )
    }
}

@Composable
private fun CoinControlPanel(state: SendState, vm: SendViewModel) {
    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        Row(
            Modifier.fillMaxWidth().clickable { vm.toggleCoinControl() },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Coin control".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
            Text(
                if (state.selectedOutpoints.isEmpty()) "automatic"
                else "${state.selectedOutpoints.size} of ${state.utxos.size} selected",
                style = CyberType.HudLabel,
                color = CyberColors.Cyan,
            )
        }

        if (state.coinControlOpen) {
            Spacer(Modifier.height(12.dp))
            state.utxos.forEach { utxo ->
                val selected = utxo.outpoint in state.selectedOutpoints
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggleUtxo(utxo.outpoint) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(15.dp)
                            .background(
                                if (selected) CyberColors.Cyan else CyberColors.Void,
                                CyberShapes.Chip,
                            )
                    )
                    Spacer(Modifier.height(0.dp))
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        AmountText(utxo.amount, state.unit, style = CyberType.Hash, showUnit = false)
                        Text(
                            "${utxo.txid.take(6)}…${utxo.txid.takeLast(4)}:${utxo.vout} · " +
                                "${utxo.confirmations} conf",
                            style = CyberType.Terminal,
                            color = CyberColors.TextTertiary,
                        )
                        if (utxo.isAddressReused) {
                            // Surfaced because spending a reused-address coin
                            // links it on-chain to the earlier spend. The
                            // wallet sets avoid_reuse, but coin control lets the
                            // user override it, so the warning has to be here.
                            Text(
                                "⚠ address already spent from — spending this links them",
                                style = CyberType.Terminal,
                                color = CyberColors.Amber,
                            )
                        }
                        if (!utxo.isConfirmed) {
                            Text("⚠ unconfirmed", style = CyberType.Terminal, color = CyberColors.Amber)
                        }
                    }
                }
            }
            if (state.selectedOutpoints.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                DataRow("Selected total") {
                    AmountText(state.selectedTotal, state.unit, style = CyberType.Hash)
                }
            }
        }
    }
}

@Composable
private fun ReviewStep(state: SendState, vm: SendViewModel) {
    var passphrase by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.error?.let { ErrorPanel("Could not send", it) }

        CyberPanel(Modifier.fillMaxWidth()) {
            Text("Paying".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
            Spacer(Modifier.height(9.dp))
            AddressText(state.address)
            Spacer(Modifier.height(16.dp))
            Text("Amount".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
            AmountText(
                state.amount ?: state.spendable,
                state.unit,
                style = CyberType.Readout,
            )
        }

        CyberPanel(Modifier.fillMaxWidth(), glow = false) {
            HudSectionHeader("What you will pay")
            Spacer(Modifier.height(8.dp))
            DataRow("Network fee") {
                AmountText(state.fee ?: Sats.ZERO, state.unit, style = CyberType.Hash,
                    color = CyberColors.Amber)
            }
            state.vsize?.let { DataRow("Size", value = "$it vB") }
            state.analysis?.feerateSatPerVb?.let {
                DataRow("Effective rate", value = String.format(Locale.US, "%.2f sat/vB", it))
            }
            DataRow(
                "Change",
                valueColor = if (state.changePosition >= 0) CyberColors.TextPrimary else CyberColors.Amber,
                value = if (state.changePosition >= 0) "output #${state.changePosition}"
                // No change output means the entire input value goes to the
                // recipient and the fee. Worth flagging: it is normal for a
                // send-max, and a surprise otherwise.
                else "none — this spends the inputs exactly",
            )
            DataRow(
                "Replaceable",
                valueColor = CyberColors.Green,
                value = "yes · you can bump the fee later",
            )
        }

        if (state.needsPassphrase) {
            CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
                Text("This wallet is locked", style = CyberType.HudLabel, color = CyberColors.Amber)
                Spacer(Modifier.height(10.dp))
                CyberTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = "Wallet passphrase",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Unlocked for 60 seconds, then locked again automatically.",
                    style = CyberType.Terminal,
                    color = CyberColors.TextTertiary,
                )
            }
        }

        when (state.stage) {
            SendStage.SIGNING -> StatusChip("Signing…", CyberColors.Amber, pulsing = true)
            SendStage.BROADCASTING -> StatusChip("Broadcasting…", CyberColors.Cyan, pulsing = true)
            else -> SlideToConfirm(
                label = "Slide to broadcast",
                onConfirmed = { vm.confirmAndSend(passphrase.takeIf { it.isNotBlank() }) },
            )
        }

        Text(
            "Once broadcast, a transaction cannot be recalled. It can only be replaced " +
                "at a higher fee, and only while it is unconfirmed.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextTertiary,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SentStep(state: SendState, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        StatusChip("Broadcast", CyberColors.Green)
        Text(
            "Sent to the network",
            style = MaterialTheme.typography.headlineSmall,
            color = CyberColors.TextPrimary,
        )
        Text(
            "It will appear as unconfirmed until a miner includes it in a block.",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberColors.TextSecondary,
        )
        CyberPanel(Modifier.fillMaxWidth(), glow = false) {
            Text("Transaction ID".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
            Spacer(Modifier.height(8.dp))
            Text(
                state.broadcastTxid.orEmpty(),
                style = CyberType.Hash,
                color = CyberColors.TextPrimary,
            )
        }
        CyberButton("Done", onDone, fillWidth = true)
    }
}
