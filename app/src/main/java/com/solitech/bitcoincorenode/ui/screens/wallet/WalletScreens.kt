package com.solitech.bitcoincorenode.ui.screens.wallet

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.solitech.bitcoincorenode.ui.components.CyberTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.Balances
import com.solitech.bitcoincorenode.core.model.DisplayUnit
import com.solitech.bitcoincorenode.core.model.EncryptionState
import com.solitech.bitcoincorenode.core.model.Sats
import com.solitech.bitcoincorenode.core.model.WalletInfo
import com.solitech.bitcoincorenode.core.model.WalletTx
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.data.repo.WalletRepository
import com.solitech.bitcoincorenode.data.repo.WalletRepository.OnFileFacts
import com.solitech.bitcoincorenode.ui.components.AmountText
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberDivider
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.DataRow
import com.solitech.bitcoincorenode.ui.components.DonateFooter
import com.solitech.bitcoincorenode.ui.components.EmptyState
import com.solitech.bitcoincorenode.ui.components.ErrorPanel
import com.solitech.bitcoincorenode.ui.components.HudSectionHeader
import com.solitech.bitcoincorenode.ui.components.StatusChip
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ─────────────────────────────── list ───────────────────────────────

/**
 * Multi-wallet state.
 *
 * Core happily keeps several wallets loaded at once, and people rely on it:
 * savings apart from spending, or a rescued import kept away from everything
 * else. So the list distinguishes three things the user genuinely cares about —
 * what is on disk, what is currently loaded, and which one the rest of the app
 * is acting on — instead of collapsing them into one list.
 */
data class WalletListState(
    val onDisk: List<String> = emptyList(),
    val loaded: List<String> = emptyList(),
    val active: String? = null,
    val busy: String? = null,
    val error: String? = null,
    /** Per loaded wallet: what its own records say, chain position aside. */
    val facts: Map<String, OnFileFacts> = emptyMap(),
) {
    /** On disk but not loaded: shown greyed with a Load action. */
    val unloaded: List<String> get() = onDisk.filter { it !in loaded }
}

@HiltViewModel
class WalletListViewModel @Inject constructor(
    private val walletRepo: WalletRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(WalletListState())
    val state: StateFlow<WalletListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.activeWallet.collectLatest { a -> _state.update { it.copy(active = a) } }
        }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        try {
            val loaded = walletRepo.listLoadedWallets()
            val onDisk = runCatching { walletRepo.listWalletFiles() }.getOrDefault(loaded)
            // What each wallet's file itself says. This is the number that
            // settles "is my imported wallet actually here?" in one glance:
            // a wallet whose records made it onto the device shows its
            // transaction count and net amount even while the chain — and
            // therefore the balance — is still catching up.
            val facts = buildMap {
                for (name in loaded) {
                    runCatching { walletRepo.onFileFacts(name) }.getOrNull()
                        ?.let { put(name, it) }
                }
            }
            _state.update {
                it.copy(
                    loaded = loaded,
                    // Union, so a wallet that is loaded but whose directory
                    // listing failed still appears rather than vanishing.
                    onDisk = (onDisk + loaded).distinct().sorted(),
                    facts = facts,
                    error = null,
                )
            }
            // Auto-select when there is exactly one and no choice has been made,
            // so a single-wallet user never has to think about this at all.
            if (_state.value.active == null && loaded.size == 1) {
                settings.setActiveWallet(loaded.first())
            }
        } catch (e: RpcError) {
            _state.update { it.copy(error = e.userMessage()) }
        }
    }

    fun setActive(name: String) = viewModelScope.launch { settings.setActiveWallet(name) }

    fun load(name: String) = op(name, "Loading") {
        walletRepo.loadWallet(name)
        // Tapping Load IS the choice. The old "only if nothing is active"
        // rule meant importing a second wallet never switched the dashboard
        // to it, which reads exactly like "the imported wallet has no
        // balance" — the app was showing the other wallet's balance.
        settings.setActiveWallet(name)
    }

    fun unload(name: String) = op(name, "Unloading") {
        walletRepo.unloadWallet(name)
        // Never leave the app pointing at a wallet that is no longer loaded.
        if (_state.value.active == name) settings.setActiveWallet(null)
    }

    fun create(name: String, passphrase: String?, legacy: Boolean = false) = op(name, "Creating") {
        walletRepo.createWallet(name, passphrase, legacy = legacy)
        settings.setActiveWallet(name)
    }

    private fun op(name: String, label: String, block: suspend () -> Unit) =
        viewModelScope.launch {
            _state.update { it.copy(busy = "$label $name…", error = null) }
            try {
                block()
                _state.update { it.copy(busy = null) }
                refresh()
            } catch (e: RpcError) {
                _state.update { it.copy(busy = null, error = e.userMessage()) }
            }
        }
}

@Composable
fun WalletListScreen(
    onOpen: (String) -> Unit,
    viewModel: WalletListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    var legacy by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    // Re-read the wallet list every time this tab comes back to the front.
    // The list used to load once per process, so a wallet imported on the
    // Import/Export screen never appeared here until the app restarted —
    // reading exactly as "the import didn't work".
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        CyberTopBar(
            title = "Wallets",
            trailing = {
                if (state.busy != null) {
                    StatusChip(state.busy!!, CyberColors.Amber, pulsing = true)
                } else {
                    Text(
                        "${state.loaded.size} loaded",
                        style = CyberType.HudLabel,
                        color = CyberColors.TextTertiary,
                    )
                }
            },
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { err -> item { ErrorPanel("Wallet error", err) } }

            if (state.onDisk.isEmpty() && state.error == null) {
                item {
                    CyberPanel(Modifier.fillMaxWidth()) {
                        Text(
                            "No wallets yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberColors.TextPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "A wallet here is a Bitcoin Core wallet stored on this device. " +
                                "You can create a new one, or import an existing wallet.dat " +
                                "from Wallet tools.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberColors.TextTertiary,
                        )
                    }
                }
            }

            if (state.loaded.isNotEmpty()) {
                item { HudSectionHeader("Loaded") }
                items(state.loaded, key = { "l-$it" }) { name ->
                    WalletRow(
                        name = name,
                        active = name == state.active,
                        loaded = true,
                        facts = state.facts[name],
                        onOpen = { onOpen(name) },
                        onSetActive = { viewModel.setActive(name) },
                        onToggleLoad = { viewModel.unload(name) },
                    )
                }
            }

            if (state.unloaded.isNotEmpty()) {
                item { HudSectionHeader("On this device, not loaded") }
                items(state.unloaded, key = { "u-$it" }) { name ->
                    WalletRow(
                        name = name,
                        active = false,
                        loaded = false,
                        onOpen = {},
                        onSetActive = {},
                        onToggleLoad = { viewModel.load(name) },
                    )
                }
            }

            item {
                CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                    HudSectionHeader("New wallet")
                    Spacer(Modifier.height(10.dp))
                    CyberTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = "Name",
                        placeholder = "savings",
                        monospace = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    CyberButton(
                        text = "Create wallet",
                        onClick = {
                            creating = true
                            viewModel.create(newName.trim(), null, legacy = legacy)
                            newName = ""
                        },
                        enabled = newName.isNotBlank() && state.busy == null,
                        fillWidth = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    // Legacy toggle. Most users should never touch this: it exists
                    // for interop with old tooling that reads wallet.dat directly.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { legacy = !legacy }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Legacy (wallet.dat) format",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (legacy) CyberColors.Amber else CyberColors.TextPrimary,
                            )
                            Text(
                                "Berkeley DB wallet, like old Bitcoin Core. Descriptor is the modern default.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberColors.TextTertiary,
                            )
                        }
                        Text(
                            if (legacy) "ON" else "OFF",
                            style = CyberType.HudLabel,
                            color = if (legacy) CyberColors.Amber else CyberColors.TextTertiary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Created without a passphrase. Set one from Wallet tools straight " +
                            "away — an unencrypted wallet can be spent by anyone who unlocks " +
                            "this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.Amber,
                    )
                }
            }

            item { DonateFooter() }
        }
    }
}

@Composable
private fun WalletRow(
    name: String,
    active: Boolean,
    loaded: Boolean,
    facts: OnFileFacts? = null,
    onOpen: () -> Unit,
    onSetActive: () -> Unit,
    onToggleLoad: () -> Unit,
) {
    CyberPanel(
        Modifier.fillMaxWidth(),
        accent = if (active) null else CyberColors.Border,
        glow = active,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (loaded) CyberColors.TextPrimary else CyberColors.TextTertiary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (loaded) "loaded" else "not loaded",
                    style = CyberType.Terminal,
                    color = if (loaded) CyberColors.Green else CyberColors.TextTertiary,
                )
                // The file's own records, visible regardless of sync. A row
                // that says "12 txs on file" is a wallet whose import worked;
                // a row with no such line is a wallet with nothing on file —
                // and that distinction is the whole "where is my balance"
                // question answered without opening anything.
                facts?.let { f ->
                    if (f.txCount > 0) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${f.txCount} txs on file · ${f.recorded.toBtcStringTrimmed()} BTC recorded",
                            style = CyberType.Terminal,
                            color = if (f.recorded > Sats.ZERO) CyberColors.Cyan
                            else CyberColors.TextTertiary,
                        )
                    }
                }
            }
            if (active) StatusChip("Active", CyberColors.Cyan)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loaded) {
                CyberButton("Open", onOpen, style = CyberButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f))
                if (!active) {
                    CyberButton("Use", onSetActive, style = CyberButtonStyle.PRIMARY,
                        modifier = Modifier.weight(1f))
                }
                CyberButton("Unload", onToggleLoad, style = CyberButtonStyle.GHOST,
                    modifier = Modifier.weight(1f))
            } else {
                CyberButton("Load", onToggleLoad, style = CyberButtonStyle.PRIMARY,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

// ─────────────────────────────── detail ───────────────────────────────

data class WalletScreenState(
    val info: WalletInfo? = null,
    val balances: Balances? = null,
    val transactions: List<WalletTx> = emptyList(),
    val unit: DisplayUnit = DisplayUnit.BTC,
    val error: String? = null,
    /** True when listtransactions returned a full page — more history exists. */
    val hasMore: Boolean = false,
    /** True while another page is being fetched. */
    val loadingMore: Boolean = false,
    /** Entries Core returned that the app could not decode and skipped. */
    val skippedEntries: Int = 0,
    /** Node is still in initial block download — balances grow as it syncs. */
    val chainSyncing: Boolean = false,
    /** Median time of the tip the node has verified through, epoch seconds. */
    val verifiedThrough: Long = 0,
    /** What the wallet file itself records, independent of the chain. */
    val onFile: OnFileFacts? = null,
)

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletRepo: WalletRepository,
    private val settings: SettingsStore,
    private val chainRepo: com.solitech.bitcoincorenode.data.repo.ChainRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WalletScreenState())
    val state: StateFlow<WalletScreenState> = _state.asStateFlow()
    private var wallet = ""

    private var pollJob: kotlinx.coroutines.Job? = null

    fun bind(name: String) {
        if (wallet == name) return
        wallet = name
        viewModelScope.launch {
            settings.displayUnit.collectLatest { u -> _state.update { it.copy(unit = u) } }
        }
        refresh()
        startPolling()
    }

    /**
     * Keeps the screen current. Fast while a rescan runs — that is when
     * balances and history are actively filling in and a stale zero on screen
     * reads as "my wallet is gone" — and slow otherwise.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val scanning = _state.value.info?.scanning is
                    com.solitech.bitcoincorenode.core.model.ScanProgress.Scanning
                kotlinx.coroutines.delay(if (scanning) 4_000L else 20_000L)
                refreshSuspend()
            }
        }
    }

    private suspend fun refreshSuspend() {
        try {
            val page = walletRepo.transactionsResilient(wallet, count = PAGE)
            // One extra batched snapshot so the screen can say WHERE the chain
            // is: during IBD the balance legitimately grows block by block,
            // and a bare number with no context reads as broken.
            val chain = runCatching { chainRepo.snapshot().blockchain }.getOrNull()
            _state.update {
                it.copy(
                    info = walletRepo.info(wallet),
                    balances = walletRepo.balances(wallet),
                    // Category is part of a record's identity: a self-transfer
                    // is listed by Core as both a send and a receive on the
                    // same outpoint, and collapsing it away misstates history.
                    transactions = page.entries.distinctBy { t ->
                        Triple(t.category, t.txid, t.vout)
                    },
                    hasMore = page.entries.size >= PAGE,
                    skippedEntries = page.skipped,
                    chainSyncing = chain?.initialblockdownload ?: false,
                    verifiedThrough = chain?.mediantime ?: 0,
                    onFile = runCatching { walletRepo.onFileFacts(wallet) }.getOrNull(),
                    error = null,
                )
            }
        } catch (e: RpcError) {
            // Transient blips during node restarts retry silently; a decode
            // problem is not transient and must be visible, because "empty
            // history" is exactly how it presents itself.
            if (e is RpcError.Malformed) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    fun refresh() = viewModelScope.launch { refreshSuspend() }

    /** Appends the next page of history. Core's listtransactions is newest-first. */
    fun loadMore() {
        if (_state.value.loadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val skip = _state.value.transactions.size
                val page = walletRepo.transactionsResilient(wallet, count = PAGE, skip = skip)
                _state.update {
                    it.copy(
                        // listtransactions can re-serve rows that moved between
                        // pages; dedupe keeps the list honest.
                        transactions = (it.transactions + page.entries)
                            .distinctBy { t -> Triple(t.category, t.txid, t.vout) },
                        hasMore = page.entries.size >= PAGE,
                        skippedEntries = it.skippedEntries + page.skipped,
                        loadingMore = false,
                    )
                }
            } catch (e: RpcError) {
                _state.update { it.copy(loadingMore = false, error = e.userMessage()) }
            }
        }
    }

    private companion object { const val PAGE = 50 }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

@Composable
fun WalletScreen(
    walletName: String,
    onSend: (String) -> Unit,
    onReceive: (String) -> Unit,
    onBack: () -> Unit,
    onTools: (String) -> Unit = {},
    viewModel: WalletViewModel = hiltViewModel(),
) {
    LaunchedEffect(walletName) { viewModel.bind(walletName) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        CyberTopBar(
            title = walletName.ifBlank { "Wallet" },
            onBack = onBack,
            trailing = {
                when (state.info?.encryption) {
                    EncryptionState.LOCKED -> StatusChip("Locked", CyberColors.Green)
                    EncryptionState.UNLOCKED -> StatusChip("Unlocked", CyberColors.Amber, pulsing = true)
                    // An unencrypted wallet is shown in red, not neutral. It is
                    // not a configuration choice, it is money with no lock on it.
                    EncryptionState.UNENCRYPTED -> StatusChip("No passphrase", CyberColors.Red)
                    null -> Unit
                }
            },
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { ErrorPanel("Wallet error", it) }

            CyberPanel(Modifier.fillMaxWidth()) {
                Text(
                    if (state.balances?.isWatchOnlyBalance == true) "Watched balance"
                    else "Spendable",
                    style = CyberType.HudLabel,
                    color = CyberColors.TextTertiary,
                )
                Spacer(Modifier.height(7.dp))
                AmountText(state.balances?.spendable ?: Sats.ZERO, state.unit, style = CyberType.Readout)
                if (state.chainSyncing) {
                    // The wallet scans each block as the node verifies it, so
                    // this number grows as the sync climbs through the years —
                    // it is live, not final, and saying so prevents a
                    // mid-IBD zero from reading as "wallet not working".
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Updating with sync — verified through ${
                            if (state.verifiedThrough > 0)
                                java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                                    .format(java.util.Date(state.verifiedThrough * 1000))
                            else "the chain so far"
                        }",
                        style = CyberType.Terminal,
                        color = CyberColors.Amber,
                    )
                }
                if (state.balances?.isWatchOnlyBalance == true) {
                    Spacer(Modifier.height(6.dp))
                    StatusChip("Watch-only — cannot spend", CyberColors.Violet)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CyberButton("Send", { onSend(walletName) }, modifier = Modifier.weight(1f),
                        enabled = state.balances?.isWatchOnlyBalance != true)
                    CyberButton("Receive", { onReceive(walletName) },
                        style = CyberButtonStyle.SECONDARY, modifier = Modifier.weight(1f))
                }
            }

            // The truth layer getbalances cannot show. Core reports zero for
            // every record whose block the chain has not verified, while the
            // wallet file still counts them — verified against a real 2011
            // wallet.dat on 28.3 where 9 records / 2.4 BTC coexisted with
            // 0.00000000 across getbalances. Rendering both numbers is the
            // only honest version of this screen.
            state.onFile?.let { facts ->
                val shown = state.balances?.total ?: Sats.ZERO
                when {
                    facts.recorded > Sats.ZERO && facts.recorded > shown ->
                        CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
                            Text(
                                "RECORDED ON FILE — ${facts.recorded.toBtcStringTrimmed()} BTC",
                                style = CyberType.HudLabel,
                                color = CyberColors.Amber,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "This wallet's file carries ${facts.txCount} transaction records " +
                                    "worth ${facts.recorded.toBtcStringTrimmed()} BTC. Bitcoin " +
                                    "Core counts none of it as balance until the chain verifies " +
                                    "each record's block, so it appears here on its own as the " +
                                    "sync advances — no rescan needed." +
                                    if (state.verifiedThrough > 0) {
                                        " Currently verified through ${
                                            SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                                                .format(Date(state.verifiedThrough * 1000))
                                        }."
                                    } else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberColors.TextSecondary,
                            )
                        }

                    facts.txCount == 0L && shown.isZero ->
                        CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                            Text(
                                "No transaction records on file",
                                style = CyberType.HudLabel,
                                color = CyberColors.TextTertiary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "This wallet contains no transaction records at all — it is " +
                                    "either brand new, or an import that did not carry the " +
                                    "original file's history. A funded wallet.dat always " +
                                    "brings its records with it the moment it loads.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberColors.TextTertiary,
                            )
                        }
                }
            }

            state.info?.let { info ->
                if (info.encryption == EncryptionState.UNENCRYPTED) {
                    CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Red) {
                        Text("This wallet has no passphrase", style = CyberType.HudLabel,
                            color = CyberColors.Red)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Anyone who unlocks this phone can spend from it. Set a passphrase " +
                                "in wallet settings — it encrypts the private keys at rest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberColors.TextSecondary,
                        )
                    }
                }

                val scan = info.scanning
                if (scan is com.solitech.bitcoincorenode.core.model.ScanProgress.Scanning) {
                    // The answer to "my imported wallet shows no balance": the
                    // rescan that rebuilds its transaction view is still running.
                    CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
                        Text(
                            String.format(
                                java.util.Locale.US,
                                "Rescanning — %.1f%%", scan.progress * 100,
                            ),
                            style = CyberType.HudLabel,
                            color = CyberColors.Amber,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Balances and history are filling in as the scan walks the " +
                                "blockchain. This happens after an import or restore.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberColors.TextSecondary,
                        )
                    }
                }

                CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                    HudSectionHeader("Details")
                    Spacer(Modifier.height(8.dp))
                    DataRow("Format", value = info.format)
                    DataRow("Transactions", value = info.txcount.toString())
                    DataRow("Keypool", value = info.keypoolsize.toString())
                    DataRow("Avoid reuse",
                        valueColor = if (info.avoidReuse) CyberColors.Green else CyberColors.Amber,
                        value = if (info.avoidReuse) "on" else "off")
                    if (info.isWatchOnly) {
                        DataRow("Keys", valueColor = CyberColors.Violet, value = "watch-only")
                    }
                }
            }

            CyberButton(
                "Wallet tools",
                { onTools(walletName) },
                style = CyberButtonStyle.SECONDARY,
                fillWidth = true,
            )

            HudSectionHeader("Activity")

            var watchOnlyToo by remember { mutableStateOf(false) }
            val visibleTxs = remember(state.transactions, watchOnlyToo) {
                // Involves watch-only is a client-side signal: getbalances already
                // separates watcher-only amounts, so filtering here matches what
                // the balance panels show.
                state.transactions.filter { watchOnlyToo || !it.involvesWatchonly }
            }

            if (state.transactions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        "${state.transactions.size} shown ${if (state.hasMore) "+" else ""}" +
                            (if (state.skippedEntries > 0) " · ${state.skippedEntries} unreadable" else ""),
                        style = CyberType.Terminal,
                        color = if (state.skippedEntries > 0) CyberColors.Amber
                        else CyberColors.TextTertiary,
                    )
                    Text(
                        if (watchOnlyToo) "hide watch-only" else "show watch-only",
                        style = CyberType.Terminal,
                        color = CyberColors.Cyan,
                        modifier = Modifier.clickable { watchOnlyToo = !watchOnlyToo },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (visibleTxs.isEmpty()) {
                val onFile = state.info?.txcount ?: -1L
                when {
                    // The wallet.dat's OWN records say there is history, yet
                    // nothing listed it: that gap is the app's to explain, not
                    // to paper over. This is the panel that settles "import
                    // broken" vs "display broken" on sight.
                    onFile > 0 && state.skippedEntries > 0 ->
                        CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Red) {
                            Text("Records exist but could not be displayed",
                                style = CyberType.HudLabel, color = CyberColors.Red)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "This wallet has $onFile transactions on file, and " +
                                    "${state.skippedEntries} of them came back in a shape the " +
                                    "app failed to read. Run `listtransactions \"*\" 5` in the " +
                                    "console to see them raw.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberColors.TextSecondary,
                            )
                        }
                    onFile > 0 ->
                        CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
                            Text(
                                "$onFile transactions on file in this wallet",
                                style = CyberType.HudLabel, color = CyberColors.Amber,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Core has the records; nothing has listed yet. If this stays " +
                                    "empty, verify in the console with getwalletinfo and " +
                                    "listtransactions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberColors.TextSecondary,
                            )
                        }
                    else -> Text(
                        if (state.transactions.isEmpty())
                            if (onFile == 0L)
                                "This wallet file has no transaction records — an empty " +
                                    "history is what Core itself reports for it."
                            else "Nothing yet. Received payments appear here as soon as your node sees them."
                        else "Only watch-only transactions — enable them with the link above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.TextTertiary,
                    )
                }
            } else {
                visibleTxs.forEach { tx -> TxRow(tx, state.unit) }
                if (state.hasMore) {
                    CyberButton(
                        text = if (state.loadingMore) "Loading…" else "Load more history",
                        onClick = viewModel::loadMore,
                        enabled = !state.loadingMore,
                        style = CyberButtonStyle.GHOST,
                        fillWidth = true,
                    )
                }
            }
            DonateFooter()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TxRow(tx: WalletTx, unit: DisplayUnit) {
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
        Text(
            "${tx.txid.take(10)}…${tx.txid.takeLast(8)}",
            style = CyberType.Terminal,
            color = CyberColors.TextTertiary,
        )
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (tx.confirmations > 0) "${tx.confirmations} conf" else "unconfirmed",
                style = CyberType.Terminal,
                color = if (tx.confirmations > 0) CyberColors.TextTertiary else CyberColors.Amber,
            )
            Text(
                formatTime(tx.effectiveTime),
                style = CyberType.Terminal,
                color = CyberColors.TextTertiary,
            )
        }
        // Only offered when Core says the transaction really is replaceable.
        // "Unknown" is not turned into a button that would fail.
        if (tx.isPending && tx.replaceability == com.solitech.bitcoincorenode.core.model.Replaceability.YES) {
            Spacer(Modifier.height(9.dp))
            Text("Fee can be bumped", style = CyberType.Terminal, color = CyberColors.Cyan)
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            CyberDivider()
            Spacer(Modifier.height(8.dp))
            DataRow("txid", value = tx.txid)
            tx.address?.let { DataRow("address", value = it) }
            tx.fee?.let {
                DataRow("fee") {
                    AmountText(it, unit, style = CyberType.Hash, showUnit = false)
                }
            }
            tx.blockheight?.let { DataRow("block height", value = it.toString()) }
            tx.blockhash?.let { DataRow("block", value = it) }
            DataRow("received", value = formatTime(tx.timereceived))
            if (tx.involvesWatchonly) DataRow("involves watch-only", valueColor = CyberColors.Violet, value = "yes")
            tx.label?.takeIf { it.isNotBlank() }?.let { DataRow("label", value = it) }
            tx.comment?.let { DataRow("comment", value = it) }
        }
    }
}

private val dateFormat = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
private fun formatTime(epochSeconds: Long): String =
    if (epochSeconds <= 0) "—" else dateFormat.format(Date(epochSeconds * 1000))
