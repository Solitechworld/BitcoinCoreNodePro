package com.solitech.bitcoincorenode.ui.screens.transactions

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.DisplayUnit
import com.solitech.bitcoincorenode.core.model.Sats
import com.solitech.bitcoincorenode.core.model.WalletTx
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.data.repo.WalletRepository
import com.solitech.bitcoincorenode.ui.components.AmountText
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberDivider
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.DataRow
import com.solitech.bitcoincorenode.ui.components.DonateFooter
import com.solitech.bitcoincorenode.ui.components.EmptyState
import com.solitech.bitcoincorenode.ui.components.ErrorPanel
import com.solitech.bitcoincorenode.ui.components.HudSectionHeader
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class TransactionsState(
    /** Every wallet currently loaded on the node. */
    val loadedWallets: List<String> = emptyList(),
    /** The wallet whose history is on screen. */
    val wallet: String? = null,
    val transactions: List<WalletTx> = emptyList(),
    val unit: DisplayUnit = DisplayUnit.BTC,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

/**
 * The Activity tab: full transaction history, one wallet at a time.
 *
 * Core's `listtransactions` is the source, newest first, paginated — this is
 * the same list `bitcoin-cli listtransactions "*"` prints, with the wallet
 * chosen from a tray instead of a command-line flag.
 */
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val walletRepo: WalletRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(TransactionsState())
    val state: StateFlow<TransactionsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.displayUnit.collectLatest { u -> _state.update { it.copy(unit = u) } }
        }
        viewModelScope.launch {
            settings.activeWallet.collectLatest { a -> _state.update { it.copy(wallet = a) } }
        }
        refresh()
        poll()
    }

    /** Slow poll: history changes only as blocks confirm transactions. */
    private fun poll() = viewModelScope.launch {
        while (true) {
            kotlinx.coroutines.delay(20_000L)
            refresh()
        }
    }

    fun selectWallet(name: String) = viewModelScope.launch {
        settings.setActiveWallet(name)
        _state.update { it.copy(wallet = name, transactions = emptyList(), hasMore = false) }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        try {
            val loaded = walletRepo.listLoadedWallets()
            val active = settings.activeWallet.firstOrNull()
                ?.takeIf { it in loaded }
                ?: loaded.singleOrNull()
            if (active != null && loaded.size == 1 && settings.activeWallet.firstOrNull() == null) {
                settings.setActiveWallet(active)
            }
            _state.update { it.copy(loadedWallets = loaded, wallet = active, error = null) }
            if (active != null) {
                val page = walletRepo.transactions(active, count = PAGE)
                _state.update {
                    it.copy(
                        // listtransactions can serve the same (txid, vout)
                        // twice — a self-transfer shows as both send and
                        // receive — and a LazyColumn keyed on txid:vout
                        // crashes on the duplicate. Category is part of the
                        // identity, so both sides survive.
                        transactions = page.distinctBy { t ->
                            Triple(t.category, t.txid, t.vout)
                        },
                        hasMore = page.size >= PAGE,
                    )
                }
            }
        } catch (e: RpcError) {
            _state.update { it.copy(error = e.userMessage()) }
        }
    }

    fun loadMore() {
        if (_state.value.loadingMore) return
        val wallet = _state.value.wallet ?: return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val skip = _state.value.transactions.size
                val page = walletRepo.transactions(wallet, count = PAGE, skip = skip)
                _state.update {
                    it.copy(
                        transactions = (it.transactions + page)
                            .distinctBy { t -> Triple(t.category, t.txid, t.vout) },
                        hasMore = page.size >= PAGE,
                        loadingMore = false,
                    )
                }
            } catch (e: RpcError) {
                _state.update { it.copy(loadingMore = false, error = e.userMessage()) }
            }
        }
    }

    private companion object { const val PAGE = 50 }
}

@Composable
fun TransactionsScreen(viewModel: TransactionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showWatchOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Wallets imported on another screen must appear here on return.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val visible = remember(state.transactions, showWatchOnly) {
        state.transactions.filter { showWatchOnly || !it.involvesWatchonly }
    }

    Column(Modifier.fillMaxSize()) {
        com.solitech.bitcoincorenode.ui.components.CyberTopBar(title = "Activity")

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // -- wallet tray --------------------------------------------------
            item {
                CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                    var open by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = state.loadedWallets.isNotEmpty()) { open = true }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Wallet".uppercase(),
                                style = CyberType.HudLabel,
                                color = CyberColors.TextTertiary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.wallet ?: "—",
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
                    DropdownMenu(
                        expanded = open,
                        onDismissRequest = { open = false },
                    ) {
                        state.loadedWallets.forEach { name ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (name == state.wallet) CyberColors.Cyan
                                        else CyberColors.TextPrimary,
                                    )
                                },
                                trailingIcon = if (name == state.wallet) {
                                    { Text("●", style = CyberType.HudLabel, color = CyberColors.Cyan) }
                                } else null,
                                onClick = {
                                    open = false
                                    viewModel.selectWallet(name)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${state.loadedWallets.size} loaded · ${state.transactions.size} transactions" +
                            (if (state.hasMore) " +" else ""),
                        style = CyberType.Terminal,
                        color = CyberColors.TextTertiary,
                    )
                }
            }

            state.error?.let { err -> item { ErrorPanel("History error", err) } }

            // -- filter row ---------------------------------------------------
            if (state.transactions.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HudSectionHeader("History")
                        Text(
                            if (showWatchOnly) "hide watch-only" else "show watch-only",
                            style = CyberType.Terminal,
                            color = CyberColors.Cyan,
                            modifier = Modifier.clickable { showWatchOnly = !showWatchOnly },
                        )
                    }
                }
            }

            if (state.wallet == null && state.error == null) {
                item {
                    CyberPanel(Modifier.fillMaxWidth()) {
                        Text(
                            if (state.loadedWallets.isEmpty()) "No wallet loaded"
                            else "Pick a wallet above",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberColors.TextPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (state.loadedWallets.isEmpty())
                                "Create or load a wallet from the Wallet tab, or start the " +
                                    "node — wallets load with it."
                            else "Choose which wallet's history to show.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberColors.TextTertiary,
                        )
                    }
                }
            } else if (visible.isEmpty() && state.error == null) {
                item {
                    EmptyState(
                        title = "Nothing yet",
                        body = if (state.transactions.isEmpty())
                            "Transactions appear here as the node verifies blocks that pay " +
                                "this wallet. During sync they fill in progressively."
                        else "Only watch-only transactions — enable them with the link above.",
                    )
                }
            } else {
                items(visible, key = { "${it.category}:${it.txid}:${it.vout}" }) { tx ->
                    HistoryRow(tx, state.unit)
                }
                if (state.hasMore) {
                    item {
                        CyberButton(
                            text = if (state.loadingMore) "Loading…" else "Load more history",
                            onClick = viewModel::loadMore,
                            enabled = !state.loadingMore,
                            style = CyberButtonStyle.GHOST,
                            fillWidth = true,
                        )
                    }
                }
            }

            item { DonateFooter() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HistoryRow(tx: WalletTx, unit: DisplayUnit) {
    var expanded by remember { mutableStateOf(false) }
    val color = when {
        tx.isConflicted -> CyberColors.Red
        tx.isPending -> CyberColors.Amber
        tx.isIncoming -> CyberColors.Incoming
        else -> CyberColors.Outgoing
    }
    CyberPanel(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        glow = false,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                when {
                    tx.isConflicted -> "Conflicted"
                    tx.isIncoming -> "Received"
                    else -> "Sent"
                }.uppercase(),
                style = CyberType.HudLabel,
                color = color,
            )
            AmountText(
                amount = if (tx.isIncoming) tx.amount else -tx.amount.absolute,
                unit = unit,
                style = CyberType.Hash,
                signed = true,
                showUnit = false,
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${tx.txid.take(10)}…${tx.txid.takeLast(8)}",
                style = CyberType.Terminal,
                color = CyberColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTime(tx.effectiveTime),
                style = CyberType.Terminal,
                color = CyberColors.TextTertiary,
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (tx.confirmations > 0) "${tx.confirmations} conf" else "unconfirmed",
                style = CyberType.Terminal,
                color = if (tx.confirmations > 0) CyberColors.TextTertiary else CyberColors.Amber,
            )
            if (tx.involvesWatchonly) {
                Text("watch-only", style = CyberType.Terminal, color = CyberColors.Violet)
            }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            CyberDivider()
            Spacer(Modifier.height(8.dp))
            DataRow("txid", value = tx.txid)
            tx.address?.let { DataRow("address", value = it) }
            tx.fee?.let {
                DataRow("fee") { AmountText(it, unit, style = CyberType.Hash, showUnit = false) }
            }
            tx.blockheight?.let { DataRow("block height", value = it.toString()) }
            tx.blockhash?.let { DataRow("block", value = it) }
            DataRow("received", value = formatTime(tx.timereceived))
            tx.label?.takeIf { it.isNotBlank() }?.let { DataRow("label", value = it) }
            tx.comment?.let { DataRow("comment", value = it) }
        }
    }
}

private val historyFormat = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
private fun formatTime(epochSeconds: Long): String =
    if (epochSeconds <= 0) "—" else historyFormat.format(Date(epochSeconds * 1000))
