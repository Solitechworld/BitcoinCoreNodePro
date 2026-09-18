package com.solitech.bitcoincorenode.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.Balances
import com.solitech.bitcoincorenode.core.model.DisplayUnit
import com.solitech.bitcoincorenode.core.node.NodeState
import com.solitech.bitcoincorenode.core.node.NodeSupervisor
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.core.rpc.RpcProvider
import com.solitech.bitcoincorenode.data.repo.ChainRepository
import com.solitech.bitcoincorenode.data.repo.ChainSnapshot
import com.solitech.bitcoincorenode.data.repo.WalletRepository
import android.content.Context
import com.solitech.bitcoincorenode.service.NodeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardState(
    val nodeState: NodeState = NodeState.Stopped,
    val chain: ChainSnapshot? = null,
    val activeWallet: String? = null,
    val loadedWallets: List<String> = emptyList(),
    val balances: Balances? = null,
    /** Set when a balance could not be read — never render it as zero. */
    val balanceProblem: String? = null,
    val unit: DisplayUnit = DisplayUnit.BTC,
    val nextBlockFeeSatPerVb: Double? = null,
    val error: String? = null,
    val refreshing: Boolean = false,
    /**
     * Why no wallet's balance is shown, in the user's terms: their chosen
     * wallet isn't loaded on this node, or several are loaded and none is
     * picked. Distinct from [balanceProblem] (a wallet WAS picked and its
     * balance could not be read).
     */
    val walletNotice: String? = null,
    /**
     * The header-presync height while Core runs its low-work header sync, -1
     * otherwise. During this phase blocks/headers in getblockchaininfo sit
     * at the last committed value, so this is the only number that moves.
     */
    val presyncHeight: Long = -1,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val supervisor: NodeSupervisor,
    private val rpcProvider: RpcProvider,
    private val chainRepo: ChainRepository,
    private val walletRepo: WalletRepository,
    private val settings: SettingsStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                supervisor.state,
                rpcProvider.mode,
            ) { ns, mode -> ns to mode }.collectLatest { (ns, mode) ->
                rpcProvider.refreshEmbedded()
                when {
                    // Remote mode: the embedded process stays Stopped, which
                    // used to leave the dashboard polling nothing and showing
                    // "Offline / Start node" while talking to a remote node.
                    // "Connecting" until the first refresh answers; the
                    // success/failure paths of doRefresh settle the real state.
                    mode is com.solitech.bitcoincorenode.core.rpc.NodeMode.Remote -> {
                        _state.update {
                            it.copy(
                                nodeState = NodeState.RemoteUnreachable(
                                    mode.endpoint.displayName,
                                    "connecting…",
                                ),
                            )
                        }
                        if (rpcProvider.active.value != null) startPolling() else stopPolling()
                    }
                    // Only start polling once there is something to poll. Hammering
                    // a node that is still loading its block index just adds work
                    // to the thing you are waiting on.
                    ns.isRunning && rpcProvider.active.value != null -> {
                        _state.update { it.copy(nodeState = ns) }
                        startPolling()
                    }
                    else -> {
                        _state.update { it.copy(nodeState = ns) }
                        stopPolling()
                    }
                }
            }
        }
        viewModelScope.launch {
            settings.displayUnit.collectLatest { u -> _state.update { it.copy(unit = u) } }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                // AWAITED, not fired and forgotten.
                //
                // This used to call refresh(), which launches its own
                // coroutine, so a new batch of RPC calls went out every 6
                // seconds whether or not the last one had come back. During
                // initial sync a single getblockchaininfo can take seconds
                // because the node is busy validating, so the batches stacked
                // up, saturated Core's four RPC threads, and the app appeared
                // to hang -- while stealing CPU from the very sync the user
                // was waiting on.
                doRefresh()
                delay(pollIntervalMs())
            }
        }
    }

    /**
     * Poll slower while syncing.
     *
     * At the tip, peer count and mempool move continuously and a stale
     * dashboard feels broken, so 6s is right. During IBD the only thing
     * changing is a progress number, and every RPC round trip is CPU taken
     * away from block validation. Backing off to 15s costs the user nothing
     * they can perceive and gives it back to the sync.
     */
    private fun pollIntervalMs(): Long =
        if (_state.value.nodeState is NodeState.Synced) 6_000L else 15_000L

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Manual (pull-to-refresh). Ignored while one is already in flight. */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch { doRefresh() }
    }

    private suspend fun doRefresh() {
        _state.update { it.copy(refreshing = true) }

        // ── Half one: the chain. Independent of everything below.
        //
        // A chain failure used to abort the whole refresh, which meant one
        // routine hiccup during sync (a warmup reply, a batch timing out
        // while the node is busy validating) left the wallet list and balance
        // unread at their previous values — and warmups are silenced, so the
        // panel just sat there. Now the halves fail alone, and a failed chain
        // poll KEEPS the last good snapshot instead of blanking the screen.
        val snap: ChainSnapshot? = try {
            chainRepo.snapshot()
        } catch (e: RpcError) {
            if (e !is RpcError.Warmup) {
                _state.update { st ->
                    st.copy(
                        nodeState = when (val m = rpcProvider.mode.value) {
                            is com.solitech.bitcoincorenode.core.rpc.NodeMode.Remote ->
                                NodeState.RemoteUnreachable(m.endpoint.displayName, e.userMessage())
                            else -> st.nodeState
                        },
                        error = e.userMessage(),
                    )
                }
            }
            null
        }
        if (snap != null) {
            _state.update { st ->
                st.copy(
                    nodeState = when (val m = rpcProvider.mode.value) {
                        is com.solitech.bitcoincorenode.core.rpc.NodeMode.Remote ->
                            NodeState.RemoteConnected(snap.blockchain, snap.network, m.endpoint.displayName)
                        else -> st.nodeState
                    },
                    chain = snap,
                    error = null,
                    presyncHeight = if (snap.blockchain.initialblockdownload)
                        snap.headerPresyncHeight else -1,
                )
            }
        }

        // ── Half two: wallets and the balance. Runs whether or not the chain
        // half succeeded — the wallet RPCs do not depend on it.
        try {
            val fees = runCatching { chainRepo.feeEstimates(listOf(1)) }.getOrNull()
            // The user's explicit choice wins. Only fall back to "first
            // loaded" when they have not chosen, otherwise the home screen
            // balance changes meaning depending on wallet load order.
            val chosen = settings.activeWallet.first()
            val loaded = runCatching { walletRepo.listLoadedWallets() }.getOrDefault(emptyList())

            // The user's explicit choice wins. With no choice made and
            // several wallets loaded, show NOTHING rather than a random
            // wallet's balance — but SAY so. A silent blank panel under
            // migratewallet's 2-3 created wallets reads as "import broken".
            val wallet = chosen?.takeIf { it in loaded } ?: loaded.singleOrNull()
            if (wallet != null && chosen == null && loaded.size == 1) {
                settings.setActiveWallet(wallet)
            }
            val walletNotice = when {
                chosen != null && chosen !in loaded ->
                    "\"$chosen\" is not loaded on this node — open Wallets and " +
                        "load it, or pick another in the selector above."
                wallet == null && loaded.size > 1 ->
                    "${loaded.size} wallets are loaded and none is selected — " +
                        "pick one in the selector above."
                else -> null
            }

            // A failed balance read must not look like a zero balance. The
            // old code swallowed the error and the panel calmly rendered
            // 0.00000000 for a wallet the node could not even answer about.
            var balanceProblem: String? = null
            val bal = wallet?.let {
                runCatching { walletRepo.balances(it) }.getOrElse { e ->
                    balanceProblem = (e as? RpcError)?.userMessage()
                        ?: (e.message ?: "balance unavailable")
                    null
                }
            }

            _state.update {
                it.copy(
                    activeWallet = wallet,
                    loadedWallets = loaded,
                    balances = bal,
                    balanceProblem = balanceProblem,
                    walletNotice = walletNotice,
                    nextBlockFeeSatPerVb = fees?.get(1)?.satPerVb,
                    refreshing = false,
                )
            }
        } catch (e: RpcError) {
            // The wallet half failing does not take the chain half's fresh
            // snapshot down with it; just surface why.
            _state.update {
                it.copy(
                    error = if (e is RpcError.Warmup) null else e.userMessage(),
                    refreshing = false,
                )
            }
        }
    }

    /** Via the foreground service, so syncing survives minimisation. */
    fun startNode() = NodeService.start(context)

    /**
     * Switches the wallet every screen acts on. The wallet must already be
     * loaded; the dropdown only offers loaded wallets, so this is a
     * DataStore write plus the next refresh picking it up.
     */
    fun selectWallet(name: String) = viewModelScope.launch {
        settings.setActiveWallet(name)
        _state.update { it.copy(activeWallet = name) }
        refresh()
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
