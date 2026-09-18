package com.solitech.bitcoincorenode.ui.screens.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solitech.bitcoincorenode.core.model.DisplayUnit
import com.solitech.bitcoincorenode.core.model.Sats
import com.solitech.bitcoincorenode.core.node.NodeState
import com.solitech.bitcoincorenode.ui.components.rememberNodeStarter
import com.solitech.bitcoincorenode.ui.components.AmountText
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberDivider
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.DataRow
import com.solitech.bitcoincorenode.ui.components.DonateFooter
import com.solitech.bitcoincorenode.ui.components.ErrorPanel
import com.solitech.bitcoincorenode.ui.components.NetworkBadge
import com.solitech.bitcoincorenode.ui.components.SignalMeter
import com.solitech.bitcoincorenode.ui.components.StatusChip
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import com.solitech.bitcoincorenode.ui.theme.LocalNetworkAccent
import java.util.Locale

@Composable
fun DashboardScreen(
    onOpenNode: () -> Unit,
    onOpenWallets: () -> Unit,
    onSend: (String) -> Unit,
    onReceive: (String) -> Unit,
    onOpenImportExport: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startNode = rememberNodeStarter(viewModel::startNode)
    val accent = LocalNetworkAccent.current

    Column(Modifier.fillMaxSize()) {
        CyberTopBar(
            title = "Dashboard",
            trailing = {
                val net = state.chain?.blockchain?.network
                NetworkBadge(net?.label ?: "—", accent)
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let {
                ErrorPanel(headline = "Node unreachable", detail = it)
            }

            if (state.loadedWallets.isNotEmpty()) WalletSwitcherPanel(state, viewModel::selectWallet)

            // Import/export gets its own button, deliberately apart from the
            // wallet list and its "create" flow: moving an existing wallet on
            // or off this device has nothing to do with making a new one.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CyberButton(
                    text = "Import wallet",
                    onClick = onOpenImportExport,
                    style = CyberButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f),
                )
                CyberButton(
                    text = "Export wallet",
                    onClick = onOpenImportExport,
                    style = CyberButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f),
                )
            }

            BalancePanel(state)

            NodeSummaryPanel(state, onOpenNode = onOpenNode, onStart = startNode)

            if (state.activeWallet != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CyberButton(
                        text = "Send",
                        onClick = { onSend(state.activeWallet!!) },
                        style = CyberButtonStyle.PRIMARY,
                        modifier = Modifier.weight(1f),
                        // Sending while mid-IBD would compute a fee from an
                        // incomplete mempool and a balance from partial history.
                        enabled = state.nodeState.isUsable,
                    )
                    CyberButton(
                        text = "Receive",
                        onClick = { onReceive(state.activeWallet!!) },
                        style = CyberButtonStyle.SECONDARY,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                // While the node itself is still booting, wallets are not
                // loadable yet (Core answers -28 while it reads the block
                // index). Showing "Set up a wallet" here reads as "my wallets
                // were deleted" — the truth is they load with the node.
                val nodeBooting = state.nodeState is NodeState.Starting ||
                    state.nodeState is NodeState.Loading
                if (nodeBooting) {
                    Text(
                        "Node starting — your wallets load with it. This can take a " +
                            "few minutes while Core reads the block index.",
                        style = CyberType.Terminal,
                        color = CyberColors.Amber,
                    )
                } else {
                    CyberButton(
                        text = "Set up a wallet",
                        onClick = onOpenWallets,
                        fillWidth = true,
                    )
                }
            }

            MempoolPanel(state)
            DonateFooter()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The wallet container: one panel showing the wallet the whole app is acting
 * on, which drops down to every loaded wallet. Selecting one re-points the
 * dashboard, send, receive and tools at it — Core keeps them all loaded
 * underneath, exactly like the Qt wallet selector.
 */
@Composable
private fun WalletSwitcherPanel(state: DashboardState, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }

    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Wallet".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
                Spacer(Modifier.height(4.dp))
                Text(
                    state.activeWallet ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberColors.TextPrimary,
                )
            }
            Text(
                if (open) "▲" else "▼",
                style = CyberType.HudLabel,
                color = CyberColors.Cyan,
            )
        }

        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            state.loadedWallets.forEach { name ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (name == state.activeWallet) CyberColors.Cyan
                            else CyberColors.TextPrimary,
                        )
                    },
                    trailingIcon = if (name == state.activeWallet) {
                        { Text("●", style = CyberType.HudLabel, color = CyberColors.Cyan) }
                    } else null,
                    onClick = {
                        open = false
                        onSelect(name)
                    },
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "${state.loadedWallets.size} loaded · tap to switch",
            style = CyberType.Terminal,
            color = CyberColors.TextTertiary,
        )
    }
}

@Composable
private fun BalancePanel(state: DashboardState) {
    val b = state.balances
    CyberPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Spendable".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
            // Whose money this is. A balance without a name invites the
            // reader to assume it is the wallet they came here to check.
            state.activeWallet?.let { name ->
                Text(
                    name.uppercase(),
                    style = CyberType.HudLabel,
                    color = CyberColors.Cyan,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        if (b == null && state.balanceProblem != null) {
            // An unreadable balance is a problem, not a zero. Rendering zeros
            // here is how "my imported wallet has no balance" reports start.
            Text(
                "Balance unavailable",
                style = CyberType.ReadoutSmall,
                color = CyberColors.Amber,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.balanceProblem ?: "",
                style = CyberType.Terminal,
                color = CyberColors.Amber,
            )
        } else if (b == null && state.walletNotice != null) {
            // No wallet picked: chosen-but-unloaded, or several loaded and
            // nothing selected (migratewallet leaves 2-3). Say it — a silent
            // blank panel reads as "import broken".
            Text(
                "No wallet selected",
                style = CyberType.ReadoutSmall,
                color = CyberColors.Amber,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.walletNotice ?: "",
                style = CyberType.Terminal,
                color = CyberColors.Amber,
            )
        } else if (b == null) {
            // Read not finished (first poll, node warmup). Deliberately not
            // 0.00000000: "not read yet" and "you have no coins" must never
            // look the same.
            Text(
                "reading…",
                style = CyberType.ReadoutSmall,
                color = CyberColors.TextTertiary,
            )
        } else {
            AmountText(
                amount = b.spendable,
                unit = state.unit,
                style = CyberType.Readout,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                // Always show the sat figure alongside, whatever unit is selected.
                // Cross-checking magnitude in two units catches a misread decimal
                // point, which is the error that costs the most.
                DisplayUnit.SATS.format(b.spendable),
                style = CyberType.Terminal,
                color = CyberColors.TextTertiary,
            )
            if (b.isWatchOnlyBalance) {
                Spacer(Modifier.height(6.dp))
                StatusChip("Watch-only — cannot spend", CyberColors.Violet)
            }
        }

        // The other half of every "zero balance" report: the node itself is
        // still downloading the chain. A wallet's balance can only include
        // blocks the node has verified — showing a bare 0.00000000 during IBD
        // with no context reads as a broken wallet. Core's own GUI shows the
        // same "out of sync" caveat on its overview page.
        val chain = state.chain?.blockchain
        if (chain != null && chain.initialblockdownload) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Node still syncing — balances fill in as the chain catches up" +
                    (if (chain.mediantime > 0) {
                        " (verified through ${
                            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                                .format(java.util.Date(chain.mediantime * 1000))
                        })"
                    } else ""),
                style = CyberType.Terminal,
                color = CyberColors.Amber,
            )
        }

        if (b != null) {
            val g = b.display
            if (g.untrustedPending.value != 0L || g.immature.value != 0L) {
                Spacer(Modifier.height(11.dp))
                CyberDivider()
                Spacer(Modifier.height(4.dp))
                if (g.untrustedPending.value != 0L) {
                    DataRow(label = "Pending in", valueColor = CyberColors.Pending) {
                        AmountText(g.untrustedPending, state.unit, style = CyberType.Hash,
                            color = CyberColors.Pending, showUnit = false)
                    }
                }
                if (g.immature.value != 0L) {
                    DataRow(label = "Immature", valueColor = CyberColors.TextTertiary) {
                        AmountText(g.immature, state.unit, style = CyberType.Hash,
                            color = CyberColors.TextTertiary, showUnit = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeSummaryPanel(
    state: DashboardState,
    onOpenNode: () -> Unit,
    onStart: () -> Unit,
) {
    val ns = state.nodeState
    val info = state.chain?.blockchain

    CyberPanel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Node".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
            NodeStatusChip(ns)
        }

        when (ns) {
            is NodeState.Stopped, is NodeState.Crashed -> {
                Spacer(Modifier.height(12.dp))
                if (ns is NodeState.Crashed) {
                    Text(
                        ns.likelyCause.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.Red,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ns.likelyCause.suggestion,
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.TextTertiary,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                CyberButton("Start node", onStart, fillWidth = true)
            }

            else -> {
                Spacer(Modifier.height(9.dp))
                Text(
                    info?.blocks?.let { formatHeight(it) } ?: "—",
                    style = CyberType.ReadoutSmall,
                    color = CyberColors.TextPrimary,
                )
                info?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "tip · ${it.bestblockhash.take(8)}…${it.bestblockhash.takeLast(7)}",
                        style = CyberType.Terminal,
                        color = CyberColors.TextTertiary,
                    )
                    Spacer(Modifier.height(10.dp))

                    val synced = it.isEffectivelySynced
                    SignalMeter(
                        fraction = if (synced) 1f else it.verificationprogress.toFloat(),
                        activeColor = if (synced) CyberColors.Green else CyberColors.Amber,
                    )
                    if (!synced) {
                        Spacer(Modifier.height(7.dp))
                        val presyncing = state.presyncHeight > 0
                        if (presyncing) {
                            // Core's exact phase label, from qt/modaloverlay.cpp
                            // ("Unknown. Pre-syncing Headers (N, P%)…"). During
                            // presync getblockchaininfo's blocks/headers sit at
                            // their last committed values, so the ordinary
                            // "X blocks behind · Y%" line would read "0 · 0.00%"
                            // — technically true, functionally a lie about what
                            // the node is doing.
                            val pct = info.presyncPercent(
                                state.presyncHeight,
                                System.currentTimeMillis() / 1000,
                            )
                            Text(
                                "Pre-syncing Headers (" +
                                    String.format(Locale.US, "%,d", state.presyncHeight) +
                                    (pct?.let { ", ${String.format(Locale.US, "%.1f", it)}%" } ?: "") +
                                    ")…",
                                style = CyberType.Terminal,
                                color = CyberColors.Amber,
                            )
                            Text(
                                "Verifying block-header work before committing headers. " +
                                    "The block download and the progress date start when " +
                                    "this finishes — normal on a fresh node.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberColors.TextTertiary,
                            )
                        } else {
                            Text(
                                // Blocks-behind, not the percentage. Near the tip
                                // verificationprogress reads 99.99% for hours, and
                                // a bar parked at "100%" is worse than no bar.
                                "${it.blocksBehind} blocks behind · ${
                                    String.format(Locale.US, "%.2f", it.progressPercent)
                                }%",
                                style = CyberType.Terminal,
                                color = CyberColors.Amber,
                            )
                            if (it.headers > it.blocks) {
                                Text(
                                    // Headers-first sync: headers land before the
                                    // blocks they announce, so this leads the tip
                                    // during the whole download.
                                    "headers ${formatHeight(it.headers)}",
                                    style = CyberType.Terminal,
                                    color = CyberColors.TextTertiary,
                                )
                            }
                            if (it.mediantime > 0) {
                                Text(
                                    // The date of the block the node is actually
                                    // on — Core's own progress line, no estimates.
                                    "syncing ${java.text.SimpleDateFormat(
                                        "d MMM yyyy", Locale.getDefault()
                                    ).format(java.util.Date(it.mediantime * 1000))}",
                                    style = CyberType.Terminal,
                                    color = CyberColors.TextTertiary,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                state.chain?.network?.let { net ->
                    DataRow(label = "Peers") {
                        Text(
                            "${net.connections}  ↓${net.connectionsIn} ↑${net.connectionsOut}",
                            style = CyberType.Hash,
                            color = CyberColors.TextPrimary,
                        )
                    }
                }
                state.chain?.blockchain?.let { bc ->
                    if (bc.pruned) {
                        DataRow(
                            label = "On disk",
                            value = formatBytes(bc.sizeOnDisk),
                        )
                    }
                }

                if (state.chain?.chainStates?.isDualChainstate == true) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Background validation still running — you are trusting " +
                            "Core's built-in snapshot commitment until it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.Amber,
                    )
                }

                Spacer(Modifier.height(12.dp))
                CyberButton("Node detail", onOpenNode, style = CyberButtonStyle.GHOST, fillWidth = true)
            }
        }
    }
}

@Composable
private fun NodeStatusChip(ns: NodeState) = when (ns) {
    is NodeState.Synced -> StatusChip("Synced", CyberColors.Green, pulsing = true)
    is NodeState.Syncing -> StatusChip("Syncing", CyberColors.Amber, pulsing = true)
    is NodeState.Loading -> StatusChip("Loading", CyberColors.Amber, pulsing = true)
    is NodeState.Starting -> StatusChip("Starting", CyberColors.Amber, pulsing = true)
    is NodeState.SnapshotLoading -> StatusChip("Snapshot", CyberColors.Violet, pulsing = true)
    is NodeState.Reindexing -> StatusChip("Reindex", CyberColors.Amber, pulsing = true)
    is NodeState.RemoteConnected -> StatusChip("Remote", CyberColors.Cyan, pulsing = true)
    is NodeState.RemoteUnreachable -> StatusChip("No link", CyberColors.Red)
    is NodeState.Crashed -> StatusChip("Stopped", CyberColors.Red)
    NodeState.Stopping -> StatusChip("Stopping", CyberColors.TextTertiary)
    NodeState.Stopped -> StatusChip("Offline", CyberColors.TextTertiary)
}

@Composable
private fun MempoolPanel(state: DashboardState) {
    val mp = state.chain?.mempool ?: return
    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        Text("Mempool".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
        Spacer(Modifier.height(9.dp))
        DataRow(label = "Transactions", value = formatHeight(mp.size))
        DataRow(label = "Size", value = formatBytes(mp.bytes))
        DataRow(label = "Min relay", value = String.format(Locale.US, "%.2f sat/vB", mp.minRelaySatPerVb))
        state.nextBlockFeeSatPerVb?.let {
            DataRow(
                label = "Next block",
                valueColor = CyberColors.Amber,
                value = String.format(Locale.US, "%.1f sat/vB", it),
            )
        } ?: DataRow(
            label = "Next block",
            valueColor = CyberColors.TextTertiary,
            // Honest, not a placeholder number. estimatesmartfee genuinely has
            // nothing to say on a fresh node or one running blocksonly.
            value = "not enough data",
        )
        Spacer(Modifier.height(9.dp))
        SignalMeter(
            fraction = mp.usageFraction,
            activeColor = if (mp.usageFraction > 0.85f) CyberColors.Red else CyberColors.Cyan,
        )
    }
}

private fun formatHeight(v: Long): String =
    v.toString().reversed().chunked(3).joinToString(" ").reversed()

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
