package com.solitech.bitcoincorenode.ui.screens.peers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.PeerInfo
import com.solitech.bitcoincorenode.core.model.PeerNetwork
import com.solitech.bitcoincorenode.data.repo.ChainRepository
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.DataRow
import com.solitech.bitcoincorenode.ui.components.DonateFooter
import com.solitech.bitcoincorenode.ui.components.EmptyState
import com.solitech.bitcoincorenode.ui.components.SignalMeter
import com.solitech.bitcoincorenode.ui.components.StatusChip
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PeersViewModel @Inject constructor(
    private val chainRepo: ChainRepository,
) : ViewModel() {
    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                runCatching { chainRepo.peers() }.onSuccess { list ->
                    // Sort by usefulness, not by id: encrypted transports first,
                    // then longest-lived. A peer list ordered by connection slot
                    // tells you nothing at a glance.
                    _peers.value = list.sortedWith(
                        compareByDescending<PeerInfo> { it.isEncryptedTransport }
                            .thenBy { it.conntime }
                    )
                }
                delay(5_000)
            }
        }
    }
}

@Composable
fun PeersScreen(
    onBack: () -> Unit,
    viewModel: PeersViewModel = hiltViewModel(),
) {
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis() / 1000

    Column(Modifier.fillMaxSize()) {
        CyberTopBar(
            title = "Peers",
            onBack = onBack,
            trailing = { Text("${peers.size}", style = CyberType.HudLabel, color = CyberColors.Cyan) },
        )

        if (peers.isEmpty()) {
            EmptyState(
                title = "No peers yet",
                body = "The node is still finding other nodes to connect to. On a fresh " +
                    "start this takes a few seconds; over Tor it can take a minute or two.",
            )
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { NetworkSummary(peers) }
            items(peers, key = { it.id }) { peer -> PeerCard(peer, now) }
            item { DonateFooter() }
        }
    }
}

@Composable
private fun NetworkSummary(peers: List<PeerInfo>) {
    val byNet = peers.groupingBy { it.networkKind }.eachCount()
    val encrypted = peers.count { it.isEncryptedTransport }

    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            byNet[PeerNetwork.ONION]?.let {
                StatusChip("$it tor", CyberColors.Violet)
            }
            byNet[PeerNetwork.IPV4]?.let { StatusChip("$it ipv4", CyberColors.Cyan) }
            byNet[PeerNetwork.IPV6]?.let { StatusChip("$it ipv6", CyberColors.Cyan) }
        }
        Spacer(Modifier.height(10.dp))
        DataRow(
            "Encrypted (BIP324)",
            valueColor = if (encrypted > 0) CyberColors.Green else CyberColors.TextTertiary,
            value = "$encrypted of ${peers.size}",
        )
        DataRow("Inbound", value = peers.count { it.inbound }.toString())
    }
}

@Composable
private fun PeerCard(peer: PeerInfo, nowSeconds: Long) {
    val netColor = when (peer.networkKind) {
        PeerNetwork.ONION -> CyberColors.Violet
        PeerNetwork.I2P, PeerNetwork.CJDNS -> CyberColors.Magenta
        else -> CyberColors.Cyan
    }

    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                // Onion addresses are 56 characters; showing all of one in a
                // list turns every row into three lines of base32.
                if (peer.addr.length > 26) peer.addr.take(12) + "…" + peer.addr.takeLast(11)
                else peer.addr,
                style = CyberType.Hash,
                color = CyberColors.TextPrimary,
            )
            Text(
                "${peer.networkKind.label} · ${peer.transportProtocolType.ifBlank { "v1" }}",
                style = CyberType.HudLabel,
                color = if (peer.isEncryptedTransport) CyberColors.Green else netColor,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${peer.subver} · ${peer.roleLabel} · ${uptime(peer.ageSeconds(nowSeconds))}",
            style = CyberType.Terminal,
            color = CyberColors.TextTertiary,
        )

        Spacer(Modifier.height(9.dp))
        // Sync position relative to us: a peer far behind our tip is not
        // helping, and that is more useful than raw byte counters.
        val frac = if (peer.startingheight > 0 && peer.syncedBlocks > 0)
            (peer.syncedBlocks.toFloat() / peer.startingheight.coerceAtLeast(1)).coerceIn(0f, 1f)
        else 0f
        SignalMeter(fraction = frac, activeColor = netColor)

        Spacer(Modifier.height(8.dp))
        peer.pingMillis?.let {
            DataRow("Ping", value = "$it ms",
                valueColor = when {
                    it < 100 -> CyberColors.Green
                    it < 500 -> CyberColors.TextPrimary
                    else -> CyberColors.Amber
                })
        }
        DataRow("Sent / recv", value = "${bytes(peer.bytessent)} / ${bytes(peer.bytesrecv)}")
        if (peer.minfeefilter > 0) {
            DataRow(
                "Fee filter",
                value = String.format(Locale.US, "%.2f sat/vB", peer.minfeefilter * 1e8 / 1000),
            )
        }
    }
}

private fun uptime(s: Long): String = when {
    s < 60 -> "${s}s"
    s < 3600 -> "${s / 60}m"
    s < 86_400 -> "${s / 3600}h ${(s % 3600) / 60}m"
    else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
}

private fun bytes(b: Long): String {
    val u = listOf("B", "KB", "MB", "GB"); var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, u[i])
}
