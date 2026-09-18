package com.solitech.bitcoincorenode.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.prefs.RemoteRpcConfig
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.core.rpc.RpcProvider
import com.solitech.bitcoincorenode.core.rpc.toEndpoint
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberTextField
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.HudSectionHeader
import com.solitech.bitcoincorenode.ui.components.StatusChip
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Link the app to an external Bitcoin Core node over RPC — your own box at
 * home, or an onion endpoint. Everything else in the app is unchanged: the
 * RpcProvider abstraction means a remote node is indistinguishable from the
 * embedded one above the RPC layer.
 */
@HiltViewModel
class RemoteNodeViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val rpcProvider: RpcProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteNodeState())
    val state: StateFlow<RemoteNodeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.remoteRpc.collect { saved ->
                _state.update {
                    it.copy(
                        saved = saved,
                        host = if (it.loadedOnce) it.host else saved.host,
                        port = if (it.loadedOnce) it.port else saved.port.toString(),
                        user = if (it.loadedOnce) it.user else saved.user,
                        password = if (it.loadedOnce) it.password else saved.password,
                        overTor = if (it.loadedOnce) it.overTor else saved.overTor,
                        loadedOnce = true,
                    )
                }
            }
        }
    }

    fun setHost(v: String) = _state.update { it.copy(host = v) }
    fun setPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit)) }
    fun setUser(v: String) = _state.update { it.copy(user = v) }
    fun setPassword(v: String) = _state.update { it.copy(password = v) }
    fun setOverTor(v: Boolean) = _state.update { it.copy(overTor = v) }

    fun entryValid(): Boolean = _state.value.let { s ->
        s.host.isNotBlank() && s.port.toIntOrNull() != null && s.user.isNotBlank() && s.password.isNotBlank()
    }

    fun connect() {
        val s = _state.value
        val config = s.asConfig(copyEnabled = true)
        viewModelScope.launch {
            settings.setRemoteRpc(config)
            rpcProvider.useRemote(config.toEndpoint())
            _state.update { it.copy(saved = config) }
            test()
        }
    }

    /** Back to the embedded node on this device. */
    fun disconnect() = viewModelScope.launch {
        settings.setRemoteRpcEnabled(false)
        rpcProvider.useEmbedded()
        _state.update { it.copy(saved = it.saved.copy(enabled = false), testResult = null) }
    }

    fun test() {
        val s = _state.value
        if (!s.entryValid()) return
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            // Probe through clientFor: a plain OkHttp client cannot resolve
            // .onion at all (and leaks the name to DNS while failing), so Tor
            // endpoints must be probed over SOCKS like the real client.
            val probe = rpcProvider.clientFor(s.asConfig(copyEnabled = true).toEndpoint())
            val ok = probe.ping()
            _state.update { it.copy(testing = false, testResult = ok) }
        }
    }
}

data class RemoteNodeState(
    val saved: RemoteRpcConfig = RemoteRpcConfig(),
    val host: String = "",
    val port: String = "8332",
    val user: String = "",
    val password: String = "",
    val overTor: Boolean = false,
    val testing: Boolean = false,
    val testResult: Boolean? = null,
    val loadedOnce: Boolean = false,
) {
    fun entryValid(): Boolean =
        host.isNotBlank() && port.toIntOrNull() != null && user.isNotBlank() && password.isNotBlank()

    fun asConfig(copyEnabled: Boolean): RemoteRpcConfig = RemoteRpcConfig(
        enabled = copyEnabled,
        host = host,
        port = port.toIntOrNull() ?: 8332,
        user = user,
        password = password,
        overTor = overTor || host.endsWith(".onion"),
    )
}

@Composable
fun RemoteNodeScreen(onBack: () -> Unit, viewModel: RemoteNodeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().imePadding()) {
        CyberTopBar(title = "Remote node (RPC)", onBack = onBack)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CyberPanel(Modifier.fillMaxWidth(), glow = state.saved.enabled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HudSectionHeader("Current link")
                    if (state.saved.enabled) StatusChip("Remote", CyberColors.Cyan, pulsing = true)
                    else StatusChip("This device", CyberColors.Green)
                }
                Spacer(Modifier.height(8.dp))
                if (state.saved.enabled && state.saved.isComplete) {
                    Text(
                        "${state.saved.host}:${state.saved.port}" +
                            if (state.saved.overTor) " · over Tor" else "",
                        style = CyberType.Terminal,
                        color = CyberColors.TextSecondary,
                    )
                } else {
                    Text(
                        "The app is using the node running on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.TextSecondary,
                    )
                }
                if (state.saved.enabled) {
                    Spacer(Modifier.height(12.dp))
                    CyberButton(
                        text = "Use the embedded node",
                        onClick = viewModel::disconnect,
                        style = CyberButtonStyle.SECONDARY,
                        fillWidth = true,
                    )
                }
            }

            CyberPanel(Modifier.fillMaxWidth()) {
                HudSectionHeader("External node")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Point the app at a Bitcoin Core node you control: rpcconnect, rpcport, " +
                        "rpcuser and rpcpassword from its bitcoin.conf. Wallets, console, " +
                        "peers — everything then reads from that node.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberColors.TextSecondary,
                )
                Spacer(Modifier.height(14.dp))
                CyberTextField(
                    value = state.host,
                    onValueChange = viewModel::setHost,
                    label = "Host",
                    placeholder = "192.168.1.20 or abc….onion",
                    monospace = true,
                )
                Spacer(Modifier.height(10.dp))
                CyberTextField(
                    value = state.port,
                    onValueChange = viewModel::setPort,
                    label = "RPC port",
                    placeholder = "8332",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    monospace = true,
                )
                Spacer(Modifier.height(10.dp))
                CyberTextField(
                    value = state.user,
                    onValueChange = viewModel::setUser,
                    label = "rpcuser",
                    monospace = true,
                )
                Spacer(Modifier.height(10.dp))
                CyberTextField(
                    value = state.password,
                    onValueChange = viewModel::setPassword,
                    label = "rpcpassword",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    monospace = true,
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    if (state.host.endsWith(".onion"))
                        "Onion addresses always route through Tor (Orbot required)."
                    else "Route through Tor even for a clearnet host. Requires Orbot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberColors.TextTertiary,
                )
                if (!state.host.endsWith(".onion")) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setOverTor(!state.overTor) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Connect over Tor",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberColors.TextPrimary,
                        )
                        Text(
                            if (state.overTor) "ON" else "OFF",
                            style = CyberType.HudLabel,
                            color = if (state.overTor) CyberColors.Violet else CyberColors.TextTertiary,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Plain RPC on a LAN is unencrypted — the rpcpassword crosses the wire " +
                        "in clear. Fine on your own network; use Tor anywhere else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberColors.Amber,
                )

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CyberButton(
                        text = if (state.testing) "Testing…" else "Test",
                        onClick = viewModel::test,
                        enabled = state.entryValid() && !state.testing,
                        style = CyberButtonStyle.SECONDARY,
                        modifier = Modifier.weight(1f),
                    )
                    CyberButton(
                        text = "Connect",
                        onClick = viewModel::connect,
                        enabled = state.entryValid(),
                        modifier = Modifier.weight(1f),
                    )
                }
                state.testResult?.let { ok ->
                    Spacer(Modifier.height(10.dp))
                    StatusChip(
                        if (ok) "Node answered" else "No answer — check host, port and credentials",
                        if (ok) CyberColors.Green else CyberColors.Red,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
