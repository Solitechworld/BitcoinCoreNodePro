package com.solitech.bitcoincorenode.ui.screens.node

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.BitcoinNetwork
import com.solitech.bitcoincorenode.core.node.AssumeUtxoCommitments
import com.solitech.bitcoincorenode.core.node.AssumeUtxoManager
import com.solitech.bitcoincorenode.core.node.SnapshotProgress
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.core.rpc.RpcProvider
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberTextField
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.ErrorPanel
import com.solitech.bitcoincorenode.ui.components.HudSectionHeader
import com.solitech.bitcoincorenode.ui.components.SignalMeter
import com.solitech.bitcoincorenode.ui.components.StatusChip
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * The assumeutxo bootstrap, as a screen.
 *
 * This destination existed as a route and a button before it existed as a
 * screen — tapping "Fast sync from snapshot" threw
 * `IllegalArgumentException: Destination … cannot be found` because nothing
 * was registered under [com.solitech.bitcoincorenode.ui.nav.Dest.SnapshotBootstrap].
 */
data class SnapshotBootstrapState(
    val network: BitcoinNetwork = BitcoinNetwork.MAIN,
    val commitments: List<com.solitech.bitcoincorenode.core.node.AssumeUtxoCommitment> = emptyList(),
    val selectedHeight: Long = 0,
    val sourceUrl: String = "",
    val phase: SnapshotProgress? = null,
    val running: Boolean = false,
    /** loadtxoutset is an RPC: the node must be up for the final step. */
    val rpcReady: Boolean = false,
) {
    val selected get() = commitments.firstOrNull { it.height == selectedHeight }
    val canStart: Boolean
        get() = !running && rpcReady && selected != null &&
            sourceUrl.trim().let { it.startsWith("http://") || it.startsWith("https://") }
}

@HiltViewModel
class SnapshotBootstrapViewModel @Inject constructor(
    private val manager: AssumeUtxoManager,
    private val rpcProvider: RpcProvider,
    settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SnapshotBootstrapState())
    val state: StateFlow<SnapshotBootstrapState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            settings.network.collectLatest { net ->
                val list = AssumeUtxoCommitments.forNetwork(net)
                _state.update {
                    it.copy(
                        network = net,
                        commitments = list,
                        // The newest commitment is the right default: less
                        // background validation, fewer missing blocks.
                        selectedHeight = list.maxOfOrNull { c -> c.height } ?: 0,
                    )
                }
            }
        }
        viewModelScope.launch {
            rpcProvider.active.collectLatest { client ->
                _state.update { it.copy(rpcReady = client != null) }
            }
        }
    }

    fun select(height: Long) = _state.update { it.copy(selectedHeight = height) }

    fun setUrl(url: String) = _state.update { it.copy(sourceUrl = url) }

    fun start() {
        val rpc = rpcProvider.active.value ?: return
        val commitment = state.value.selected ?: return
        val url = state.value.sourceUrl.trim()
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, phase = null) }
            // Failed ends the flow; Done is emitted once and the flow ends;
            // BackgroundValidating repeats every few seconds until the
            // background chainstate catches up (or the user leaves).
            manager.bootstrap(rpc, commitment, url).collect { p ->
                _state.update {
                    it.copy(phase = p, running = p !is SnapshotProgress.Failed)
                }
            }
        }
    }

    /**
     * Cancels download/load. The partial file is kept — bootstrap resumes
     * from where the download stopped rather than restarting a ~12 GB fetch.
     */
    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }
}

@Composable
fun SnapshotBootstrapScreen(
    onBack: () -> Unit,
    viewModel: SnapshotBootstrapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        CyberTopBar(title = "Fast sync from snapshot", onBack = onBack)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                Text(
                    "Boot from a UTXO snapshot instead of syncing from 2009",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberColors.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The node loads the UTXO set at the snapshot's height and is " +
                        "usable immediately, then validates history from genesis in " +
                        "the background. Bitcoin Core refuses any snapshot whose UTXO " +
                        "hash does not match the commitment compiled into it — a bad " +
                        "download costs bandwidth, not correctness.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberColors.TextSecondary,
                )
            }

            if (state.commitments.isEmpty()) {
                CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
                    Text(
                        "No snapshot commitments are compiled into Core for " +
                            "${state.network.label}. Regular block download is the " +
                            "only path on this network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.Amber,
                    )
                }
            } else {
                CyberPanel(Modifier.fillMaxWidth()) {
                    HudSectionHeader("Snapshot")
                    Spacer(Modifier.height(10.dp))
                    state.commitments.forEach { c ->
                        val picked = c.height == state.selectedHeight
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.running) {
                                    viewModel.select(c.height)
                                }
                                .padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    "height ${String.format(Locale.US, "%,d", c.height)}",
                                    style = CyberType.Hash,
                                    color = if (picked) CyberColors.Cyan else CyberColors.TextPrimary,
                                )
                                Text(
                                    "${c.blockHash.take(10)}… · ${String.format(
                                        Locale.US, "%,d", c.chainTxCount
                                    )} txs · ≈${c.approximateGigabytes} GB",
                                    style = CyberType.Terminal,
                                    color = CyberColors.TextTertiary,
                                )
                            }
                            if (picked) StatusChip("Selected", CyberColors.Cyan)
                        }
                    }
                }

                CyberPanel(Modifier.fillMaxWidth()) {
                    HudSectionHeader("Source")
                    Spacer(Modifier.height(10.dp))
                    // Deliberately no default host. A default means every
                    // install fetches ~12 GB from a server this app's author
                    // chose, and tells that server who just installed a
                    // wallet. The user names their own mirror.
                    CyberTextField(
                        value = state.sourceUrl,
                        onValueChange = viewModel::setUrl,
                        label = "Snapshot URL",
                        placeholder = "https://your-mirror.example/utxo-${state.selectedHeight}.dat",
                        monospace = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The full URL of a utxo-<height>.dat file produced by " +
                            "dumptxoutset. Whoever hosts it sees this device's " +
                            "address in their logs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.TextTertiary,
                    )
                }

                if (!state.rpcReady) {
                    CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
                        Text(
                            "Node not running — start it from the Node tab first. " +
                                "Loading a snapshot is an RPC call; the node has to be " +
                                "up to receive it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberColors.Amber,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.running) {
                        CyberButton(
                            text = "Stop",
                            onClick = viewModel::stop,
                            style = CyberButtonStyle.DANGER,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        CyberButton(
                            text = "Download & load",
                            onClick = viewModel::start,
                            enabled = state.canStart,
                            style = CyberButtonStyle.PRIMARY,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                state.phase?.let { phase -> PhasePanel(phase) }
            }
        }
    }
}

@Composable
private fun PhasePanel(phase: SnapshotProgress) {
    when (phase) {
        is SnapshotProgress.Downloading -> CyberPanel(Modifier.fillMaxWidth()) {
            Text("Downloading", style = CyberType.HudLabel, color = CyberColors.Cyan)
            Spacer(Modifier.height(8.dp))
            SignalMeter(fraction = phase.fraction, activeColor = CyberColors.Cyan)
            Spacer(Modifier.height(8.dp))
            Text(
                "${gb(phase.bytesRead)} of ${if (phase.totalBytes > 0) gb(phase.totalBytes) else "?"} GB" +
                    " · ${mb(phase.bytesPerSecond)}/s" +
                    (phase.etaSeconds?.let { s ->
                        " · ~${if (s > 90) "${s / 60} min" else "$s s"} left"
                    } ?: ""),
                style = CyberType.Terminal,
                color = CyberColors.TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Stopped downloads resume where they left off.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.TextTertiary,
            )
        }

        SnapshotProgress.Loading -> CyberPanel(Modifier.fillMaxWidth()) {
            Text("Loading into the node", style = CyberType.HudLabel, color = CyberColors.Amber)
            Spacer(Modifier.height(6.dp))
            Text(
                "Deserializing and hashing the UTXO set — several minutes of disk " +
                    "work, no network. If the hash does not match Core's commitment " +
                    "the node refuses it and nothing is written.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.TextSecondary,
            )
        }

        is SnapshotProgress.BackgroundValidating -> CyberPanel(
            Modifier.fillMaxWidth(), accent = CyberColors.Amber,
        ) {
            Text("Snapshot active — validating history", style = CyberType.HudLabel, color = CyberColors.Amber)
            Spacer(Modifier.height(8.dp))
            SignalMeter(fraction = phase.fraction, activeColor = CyberColors.Amber)
            Spacer(Modifier.height(8.dp))
            Text(
                "${String.format(Locale.US, "%,d", phase.blocks)} / " +
                    "${String.format(Locale.US, "%,d", phase.target)} blocks",
                style = CyberType.Terminal,
                color = CyberColors.TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Your node is usable now. Until this finishes you are trusting " +
                    "the UTXO commitment compiled into Bitcoin Core rather than " +
                    "your own validation of history.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.TextTertiary,
            )
        }

        is SnapshotProgress.Done -> CyberPanel(Modifier.fillMaxWidth(), glow = true) {
            Text("Snapshot loaded", style = CyberType.HudLabel, color = CyberColors.Green)
            Spacer(Modifier.height(6.dp))
            Text(
                "Chainstate active at height " +
                    String.format(Locale.US, "%,d", phase.tipHeight) +
                    ". Background validation continues on the Node tab.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.TextSecondary,
            )
        }

        is SnapshotProgress.Failed -> ErrorPanel("Snapshot failed", phase.reason)
    }
}

private fun gb(bytes: Long): String =
    String.format(Locale.US, "%.1f", bytes / 1e9)

private fun mb(bytesPerSecond: Long): String =
    String.format(Locale.US, "%.1f", bytesPerSecond / 1e6)
