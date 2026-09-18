package com.solitech.bitcoincorenode.ui.screens.mempool

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.FeeEstimate
import com.solitech.bitcoincorenode.core.model.MempoolInfo
import com.solitech.bitcoincorenode.data.repo.ChainRepository
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.DataRow
import com.solitech.bitcoincorenode.ui.components.DonateFooter
import com.solitech.bitcoincorenode.ui.components.HudSectionHeader
import com.solitech.bitcoincorenode.ui.components.SignalMeter
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class MempoolScreenState(
    val info: MempoolInfo? = null,
    val fees: Map<Int, FeeEstimate> = emptyMap(),
    val tipHeight: Long = 0,
    val blocksOnlyLikely: Boolean = false,
)

@HiltViewModel
class MempoolViewModel @Inject constructor(
    private val chainRepo: ChainRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MempoolScreenState())
    val state: StateFlow<MempoolScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                runCatching {
                    val snap = chainRepo.snapshot()
                    val fees = runCatching { chainRepo.feeEstimates() }.getOrDefault(emptyMap())
                    _state.update {
                        it.copy(
                            info = snap.mempool,
                            fees = fees,
                            tipHeight = snap.blockchain.blocks,
                            // An empty mempool on a synced node almost always
                            // means blocksonly. Saying so is more useful than
                            // showing a row of zeros and letting the user guess.
                            blocksOnlyLikely = snap.mempool?.size == 0L &&
                                !snap.blockchain.initialblockdownload,
                        )
                    }
                }
                delay(8_000)
            }
        }
    }
}

@Composable
fun MempoolScreen(viewModel: MempoolViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        CyberTopBar(title = "Mempool")

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.blocksOnlyLikely) {
                CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
                    Text("Mempool is empty", style = CyberType.HudLabel, color = CyberColors.Amber)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your node isn't relaying loose transactions — either it's in " +
                            "blocks-only mode (Settings → Data saver) or it has only just " +
                            "started. Fee estimates degrade without a mempool.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.TextSecondary,
                    )
                }
            }

            state.info?.let { mp ->
                CyberPanel(Modifier.fillMaxWidth()) {
                    Text("In the mempool".uppercase(), style = CyberType.HudLabel,
                        color = CyberColors.TextTertiary)
                    Spacer(Modifier.height(7.dp))
                    Text(grouped(mp.size), style = CyberType.Readout, color = CyberColors.TextPrimary)
                    Text("transactions waiting", style = CyberType.Terminal,
                        color = CyberColors.TextTertiary)

                    Spacer(Modifier.height(14.dp))
                    Text("Memory used".uppercase(), style = CyberType.HudLabel,
                        color = CyberColors.TextTertiary)
                    Spacer(Modifier.height(7.dp))
                    SignalMeter(
                        fraction = mp.usageFraction,
                        activeColor = when {
                            mp.usageFraction > 0.9f -> CyberColors.Red
                            mp.usageFraction > 0.6f -> CyberColors.Amber
                            else -> CyberColors.Cyan
                        },
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "${bytes(mp.usage)} of ${bytes(mp.maxmempool)}",
                        style = CyberType.Terminal, color = CyberColors.TextSecondary,
                    )
                    if (mp.usageFraction > 0.9f) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            // The consequence, not just the number: a full
                            // mempool means the eviction floor rises and cheap
                            // transactions start getting dropped.
                            "The mempool is nearly full. Your node is evicting the lowest-fee " +
                                "transactions, and the minimum relay fee has risen accordingly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberColors.Amber,
                        )
                    }
                }

                CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                    HudSectionHeader("Fee estimates")
                    Spacer(Modifier.height(8.dp))
                    listOf(1 to "Next block", 3 to "~30 minutes", 6 to "~1 hour", 24 to "~4 hours")
                        .forEach { (blocks, label) ->
                            val est = state.fees[blocks]
                            DataRow(
                                label,
                                valueColor = if (est?.satPerVb != null) CyberColors.TextPrimary
                                else CyberColors.TextTertiary,
                                value = est?.satPerVb
                                    ?.let { String.format(Locale.US, "%.2f sat/vB", it) }
                                    ?: "no estimate",
                            )
                        }
                    Spacer(Modifier.height(10.dp))
                    DataRow(
                        "Minimum relay",
                        value = String.format(Locale.US, "%.2f sat/vB", mp.minRelaySatPerVb),
                    )
                }

                CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                    HudSectionHeader("Detail")
                    Spacer(Modifier.height(8.dp))
                    DataRow("Chain tip", value = grouped(state.tipHeight))
                    DataRow("Serialized size", value = bytes(mp.bytes))
                    DataRow("Unbroadcast", value = mp.unbroadcastcount.toString())
                    DataRow("Loaded from disk",
                        valueColor = if (mp.loaded) CyberColors.Green else CyberColors.Amber,
                        value = if (mp.loaded) "yes" else "still loading")
                }
            }
            DonateFooter()
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun grouped(v: Long) = v.toString().reversed().chunked(3).joinToString(" ").reversed()

private fun bytes(b: Long): String {
    val u = listOf("B", "KB", "MB", "GB"); var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, u[i])
}
