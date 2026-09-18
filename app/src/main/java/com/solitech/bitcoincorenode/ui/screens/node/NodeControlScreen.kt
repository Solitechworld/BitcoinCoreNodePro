package com.solitech.bitcoincorenode.ui.screens.node

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solitech.bitcoincorenode.core.diag.CrashLog
import com.solitech.bitcoincorenode.core.node.NodeState
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.DataRow
import com.solitech.bitcoincorenode.ui.components.DonateFooter
import com.solitech.bitcoincorenode.ui.components.HudSectionHeader
import com.solitech.bitcoincorenode.ui.components.rememberNodeStarter
import com.solitech.bitcoincorenode.ui.components.SignalMeter
import com.solitech.bitcoincorenode.ui.components.StatusChip
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import java.util.Locale

@Composable
fun NodeControlScreen(
    onOpenPeers: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenSnapshot: () -> Unit,
    onOpenMempool: () -> Unit = {},
    viewModel: NodeControlViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    val startNode = rememberNodeStarter(viewModel::start)

    Column(Modifier.fillMaxSize()) {
        CyberTopBar(title = "Node")

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CrashReportPanel()

            state.chainStates?.takeIf { it.isDualChainstate }?.let { cs ->
                CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
                    HudSectionHeader("Snapshot chainstate", accent = CyberColors.Amber)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        cs.snapshot?.blocks?.let(::grouped) ?: "—",
                        style = CyberType.ReadoutSmall,
                        color = CyberColors.TextPrimary,
                    )
                    Text(
                        "usable at the tip",
                        style = CyberType.Terminal,
                        color = CyberColors.TextTertiary,
                    )

                    Spacer(Modifier.height(16.dp))
                    Text("Background validation".uppercase(),
                        style = CyberType.HudLabel, color = CyberColors.TextTertiary)
                    Spacer(Modifier.height(8.dp))
                    val bg = cs.background
                    val target = cs.snapshot?.blocks ?: cs.headers
                    val frac = if (target > 0) (bg?.blocks ?: 0).toFloat() / target else 0f
                    SignalMeter(fraction = frac, activeColor = CyberColors.Amber)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${grouped(bg?.blocks ?: 0)} / ${grouped(target)} blocks · " +
                            String.format(Locale.US, "%.1f", frac * 100) + "%",
                        style = CyberType.Terminal,
                        color = CyberColors.TextSecondary,
                    )
                    Spacer(Modifier.height(11.dp))
                    Text(
                        "Until this finishes you are trusting the UTXO commitment compiled " +
                            "into Bitcoin Core, not your own validation of history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.TextTertiary,
                    )
                }
            }

            StatusPanel(state)

            state.info?.let { info ->
                CyberPanel(Modifier.fillMaxWidth(), glow = true) {
                    HudSectionHeader("Chain")
                    Spacer(Modifier.height(8.dp))
                    DataRow("Network", value = info.chain)
                    DataRow("Blocks", value = grouped(info.blocks))
                    DataRow("Headers", value = grouped(info.headers))

                    // Real date of the chain tip, straight from the block data.
                    if (info.mediantime > 0) {
                        DataRow(
                            "Tip block date",
                            valueColor = CyberColors.Cyan,
                            value = syncDateFormat.format(java.util.Date(info.mediantime * 1000)),
                        )
                    }

                    DataRow("Difficulty", value = String.format(Locale.US, "%.3e", info.difficulty))
                    DataRow("Size on disk", value = bytes(info.sizeOnDisk))
                    if (info.pruned) {
                        DataRow("Pruned from", value = grouped(info.pruneheight ?: 0))
                        DataRow("Prune target", value = bytes(info.pruneTargetSize ?: 0))
                    }
                    if (info.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        info.warnings.forEach {
                            Text("⚠ $it", style = MaterialTheme.typography.bodySmall,
                                color = CyberColors.Amber)
                        }
                    }
                }
            }

            state.network?.let { net ->
                CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                    HudSectionHeader("Network", trailing = {
                        Text("detail", style = CyberType.HudLabel, color = CyberColors.Cyan)
                    })
                    Spacer(Modifier.height(8.dp))
                    DataRow("Version", value = net.subversion)
                    DataRow("Protocol", value = net.protocolversion.toString())
                    DataRow("Connections", value = "${net.connections} (${net.connectionsIn} in)")
                    DataRow(
                        "Tor",
                        valueColor = if (net.torReachable) CyberColors.Violet else CyberColors.TextTertiary,
                        value = if (net.torReachable) "reachable" else "not in use",
                    )
                    Spacer(Modifier.height(12.dp))
                    CyberButton("Peers", onOpenPeers, style = CyberButtonStyle.GHOST, fillWidth = true)
                }
            }

            HudSectionHeader("Controls")

            CyberButton("RPC console", onOpenConsole, style = CyberButtonStyle.SECONDARY, fillWidth = true)
            CyberButton("Mempool", onOpenMempool, style = CyberButtonStyle.GHOST, fillWidth = true)
            // Always reachable, not just while stopped: loading a snapshot is
            // an RPC call (loadtxoutset), so it needs the node UP. The screen
            // gates its own phases and says so when the node is down.
            CyberButton("Fast sync from snapshot", onOpenSnapshot,
                style = CyberButtonStyle.SECONDARY, fillWidth = true)

            if (state.nodeState is NodeState.Stopped || state.nodeState is NodeState.Crashed) {
                CyberButton("Start node", startNode, fillWidth = true)
            } else {
                if (!confirmStop) {
                    CyberButton("Stop node", { confirmStop = true },
                        style = CyberButtonStyle.DANGER, fillWidth = true)
                } else {
                    CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Red) {
                        Text(
                            "Stopping takes up to a minute while Bitcoin Core flushes its " +
                                "UTXO cache to disk. Do not force-close during that time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberColors.TextSecondary,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CyberButton("Cancel", { confirmStop = false },
                                style = CyberButtonStyle.GHOST, modifier = Modifier.weight(1f))
                            CyberButton("Stop cleanly", { confirmStop = false; viewModel.stop() },
                                style = CyberButtonStyle.DANGER, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                HudSectionHeader("Background sync")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Minimising the app does not stop the download. The node keeps " +
                        "syncing, and the ongoing notification is how you stop it from " +
                        "anywhere.\n\n" +
                        "Closing the app — swiping it out of recents — shuts the node down " +
                        "cleanly, and reopening starts it again from where it left off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberColors.TextSecondary,
                )
            }

            DonateFooter()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Tip-block date during IBD, matching the format Core itself prints. */
private val syncDateFormat =
    java.text.SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())

@Composable
private fun StatusPanel(state: NodeControlState) {
    CyberPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("STATUS", style = CyberType.HudLabel, color = CyberColors.TextTertiary)
            when (val ns = state.nodeState) {
                is NodeState.Synced -> StatusChip("Synced", CyberColors.Green, pulsing = true)
                is NodeState.Syncing -> StatusChip("Syncing", CyberColors.Amber, pulsing = true)
                is NodeState.Crashed -> StatusChip("Crashed", CyberColors.Red)
                NodeState.Stopped -> StatusChip("Offline", CyberColors.TextTertiary)
                else -> StatusChip("Working", CyberColors.Amber, pulsing = true)
            }
        }

        when (val ns = state.nodeState) {
            is NodeState.Starting -> Detail(ns.stage.label, ns.detail)
            is NodeState.Loading -> Detail(ns.message, null)
            is NodeState.Reindexing -> Detail(ns.message, "This rebuilds the block index from scratch.")
            is NodeState.Crashed -> {
                Detail(ns.likelyCause.summary, ns.likelyCause.suggestion)
                if (ns.logTail.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        ns.logTail.takeLast(6).joinToString("\n"),
                        style = CyberType.Terminal,
                        color = CyberColors.TextTertiary,
                    )
                }
            }
            is NodeState.Syncing -> {
                Spacer(Modifier.height(10.dp))
                SignalMeter(
                    fraction = ns.info.verificationprogress.toFloat(),
                    activeColor = CyberColors.Amber,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${grouped(ns.info.blocksBehind)} blocks behind",
                    style = CyberType.Terminal, color = CyberColors.Amber,
                )
                Text(
                    ns.etaSeconds?.let { "≈ ${duration(it)} remaining" } ?: "estimating rate…",
                    style = CyberType.Terminal, color = CyberColors.TextTertiary,
                )
                // The real date of the block at the tip, from its median
                // timestamp — this is what Core's own progress line means by
                // "syncing 2017-06-12". No height heuristics.
                if (ns.info.mediantime > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Block date ${syncDateFormat.format(java.util.Date(ns.info.mediantime * 1000))}",
                        style = CyberType.HudLabel,
                        color = CyberColors.Cyan,
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun Detail(headline: String, body: String?) {
    Spacer(Modifier.height(10.dp))
    Text(headline, style = MaterialTheme.typography.bodyMedium, color = CyberColors.TextPrimary)
    if (!body.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = CyberColors.TextTertiary)
    }
}

private fun grouped(v: Long) = v.toString().reversed().chunked(3).joinToString(" ").reversed()

private fun bytes(b: Long): String {
    val u = listOf("B", "KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, u[i])
}

private fun duration(seconds: Long): String = when {
    seconds < 90 -> "${seconds}s"
    seconds < 5400 -> "${seconds / 60} min"
    seconds < 172_800 -> "${seconds / 3600} h"
    else -> "${seconds / 86_400} d"
}

@Composable
private fun CrashReportPanel() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    var expanded by remember { mutableStateOf(false) }

    val text = report ?: return

    CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Red) {
        HudSectionHeader("Last crash", accent = CyberColors.Red)
        Spacer(Modifier.height(10.dp))
        Text(
            "The app closed unexpectedly. This trace contains no wallet data.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (expanded) text else text.lineSequence().take(6).joinToString("\n"),
            style = CyberType.Terminal,
            color = CyberColors.TextTertiary,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CyberButton(
                if (expanded) "Less" else "Full trace",
                { expanded = !expanded },
                style = CyberButtonStyle.GHOST,
                modifier = Modifier.weight(1f),
            )
            CyberButton(
                "Copy",
                { clipboard.setText(AnnotatedString(text)) },
                style = CyberButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f),
            )
            CyberButton(
                "Dismiss",
                { CrashLog.clear(context); report = null },
                style = CyberButtonStyle.GHOST,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
