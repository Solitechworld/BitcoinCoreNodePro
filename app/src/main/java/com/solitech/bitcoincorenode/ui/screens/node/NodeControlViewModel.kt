package com.solitech.bitcoincorenode.ui.screens.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.BlockchainInfo
import com.solitech.bitcoincorenode.core.model.ChainStates
import com.solitech.bitcoincorenode.core.model.NetworkInfo
import com.solitech.bitcoincorenode.core.node.NodeState
import com.solitech.bitcoincorenode.core.node.NodeSupervisor
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.core.rpc.RpcProvider
import com.solitech.bitcoincorenode.data.repo.ChainRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NodeControlState(
    val nodeState: NodeState = NodeState.Stopped,
    val info: BlockchainInfo? = null,
    val network: NetworkInfo? = null,
    val chainStates: ChainStates? = null,
)

@HiltViewModel
class NodeControlViewModel @Inject constructor(
    private val supervisor: NodeSupervisor,
    private val rpcProvider: RpcProvider,
    private val chainRepo: ChainRepository,
    private val settings: SettingsStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(NodeControlState())
    val state: StateFlow<NodeControlState> = _state.asStateFlow()

    private var poll: Job? = null

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                supervisor.state,
                rpcProvider.mode,
            ) { ns, mode -> ns to mode }.collectLatest { (ns, mode) ->
                rpcProvider.refreshEmbedded()
                when {
                    // Remote mode: poll the remote node and say so, instead of
                    // sitting on a Stopped process and offering "Start node"
                    // for hardware that is not ours to start.
                    mode is com.solitech.bitcoincorenode.core.rpc.NodeMode.Remote &&
                        rpcProvider.active.value != null -> {
                        _state.update {
                            it.copy(
                                nodeState = com.solitech.bitcoincorenode.core.node.NodeState
                                    .RemoteUnreachable(mode.endpoint.displayName, "connecting…"),
                            )
                        }
                        startPoll()
                    }
                    ns.isRunning -> {
                        _state.update { it.copy(nodeState = ns) }
                        startPoll()
                    }
                    else -> {
                        _state.update { it.copy(nodeState = ns) }
                        poll?.cancel()
                    }
                }
            }
        }
    }

    private fun startPoll() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (true) {
                // Awaited inline and backed off during sync -- see the same
                // reasoning in DashboardViewModel. Two screens each firing an
                // un-awaited RPC batch on a short timer is how a node that is
                // busy validating ends up looking like an app that has frozen.
                runCatching { chainRepo.snapshot() }.onSuccess { s ->
                    _state.update {
                        it.copy(
                            nodeState = when (val m = rpcProvider.mode.value) {
                                is com.solitech.bitcoincorenode.core.rpc.NodeMode.Remote ->
                                    NodeState.RemoteConnected(s.blockchain, s.network, m.endpoint.displayName)
                                else -> it.nodeState
                            },
                            info = s.blockchain, network = s.network, chainStates = s.chainStates,
                        )
                    }
                }.onFailure { e ->
                    val m = rpcProvider.mode.value
                    if (m is com.solitech.bitcoincorenode.core.rpc.NodeMode.Remote) {
                        _state.update {
                            it.copy(
                                nodeState = NodeState.RemoteUnreachable(
                                    m.endpoint.displayName,
                                    e.message ?: "unreachable",
                                ),
                            )
                        }
                    }
                }
                delay(if (_state.value.nodeState is NodeState.Synced) 4_000L else 12_000L)
            }
        }
    }

    /**
     * Starts the node through the foreground service, never directly.
     *
     * Calling supervisor.start() from a ViewModel leaves the node parented to
     * the UI process with nothing telling Android to keep that process alive,
     * so minimising the app stops the sync. The service is what makes
     * background syncing work at all.
     */
    fun start() = NodeService.start(context)

    fun stop() = NodeService.stop(context)

    override fun onCleared() { poll?.cancel(); super.onCleared() }
}
