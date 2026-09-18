package com.solitech.bitcoincorenode.ui.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.BitcoinNetwork
import com.solitech.bitcoincorenode.core.model.DisplayUnit
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.DataRow
import com.solitech.bitcoincorenode.ui.components.CyberTextField
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.DonateFooter
import com.solitech.bitcoincorenode.ui.components.HudSectionHeader
import com.solitech.bitcoincorenode.ui.components.StatusChip
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberShapes
import com.solitech.bitcoincorenode.ui.theme.CyberType
import com.solitech.bitcoincorenode.ui.theme.accentFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExplorerSettingsState(
    val enabled: Boolean = false,
    val url: String = "",
    val overTor: Boolean = false,
)

data class StorageState(
    val pruneMb: Int = SettingsStore.DEFAULT_PRUNE_MB,
    val freeGb: Double = 0.0,
    val fits: Boolean = true,
    /** Precomputed fits for each prune option, keyed by MB. */
    val optionsFits: Map<Int, Boolean> = emptyMap(),
)

data class SettingsState(
    val network: BitcoinNetwork = BitcoinNetwork.MAIN,
    val unit: DisplayUnit = DisplayUnit.BTC,
    val screenSecurity: Boolean = true,
    val requireBiometric: Boolean = true,
    val freeStorageGb: Double = 0.0,
    val canRunNode: Boolean = true,
    /** App-lock passcode exists — drives the Security panel's set/change UI. */
    val passcodeConfigured: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val appLock: com.solitech.bitcoincorenode.core.prefs.AppLock,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Outcome of the last passcode action, shown once under the fields. */
    private val _passcodeMessage = MutableStateFlow<String?>(null)
    val passcodeMessage: StateFlow<String?> = _passcodeMessage.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settings.network,
                settings.displayUnit,
                settings.screenSecurity,
                settings.requireBiometric,
                appLock.isConfigured,
            ) { net, unit, sec, bio, lockConfigured ->
                SettingsState(
                    network = net, unit = unit,
                    screenSecurity = sec, requireBiometric = bio,
                    freeStorageGb = settings.freeStorageBytes() / 1_073_741_824.0,
                    canRunNode = settings.canRunEmbeddedNode(),
                    passcodeConfigured = lockConfigured,
                )
            }.collect { s -> _state.value = s }
        }
    }

    val explorer: StateFlow<ExplorerSettingsState> = combine(
        settings.explorerEnabled,
        settings.explorerUrl,
        settings.explorerOverTor,
    ) { enabled, url, tor -> ExplorerSettingsState(enabled, url, tor) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExplorerSettingsState())

    val storage: StateFlow<StorageState> = settings.pruneMb
        .map { mb ->
            // StatFs is a disk syscall; precompute the per-option fits here
            // (off the main thread via flowOn) instead of calling it during
            // composition for every bubble on every recomposition.
            StorageState(
                pruneMb = mb,
                freeGb = settings.freeStorageBytes() / 1_073_741_824.0,
                fits = settings.storageFitsPrune(mb),
                optionsFits = SettingsStore.PRUNE_OPTIONS_MB.associateWith {
                    settings.storageFitsPrune(it)
                },
            )
        }
        .flowOn(kotlinx.coroutines.Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageState())

    fun setPruneMb(mb: Int) = viewModelScope.launch { settings.setPruneMb(mb) }
    fun storageFitsPrune(mb: Int): Boolean = settings.storageFitsPrune(mb)
    fun setExplorerEnabled(v: Boolean) = viewModelScope.launch { settings.setExplorerEnabled(v) }
    fun setExplorerUrl(v: String) = viewModelScope.launch { settings.setExplorerUrl(v) }
    fun setExplorerOverTor(v: Boolean) = viewModelScope.launch { settings.setExplorerOverTor(v) }
    fun setNetwork(n: BitcoinNetwork) = viewModelScope.launch { settings.setNetwork(n) }
    fun setUnit(u: DisplayUnit) = viewModelScope.launch { settings.setDisplayUnit(u) }
    fun setScreenSecurity(v: Boolean) = viewModelScope.launch { settings.setScreenSecurity(v) }
    fun setBiometric(v: Boolean) = viewModelScope.launch { settings.setRequireBiometric(v) }

    // -- app passcode ----------------------------------------------------------

    fun setAppPasscode(code: String, confirm: String) = viewModelScope.launch {
        _passcodeMessage.value = if (code != confirm) {
            "The two entries don't match."
        } else {
            appLock.setPasscode(code)
                ?: "Passcode set — the app locks when you background it."
        }
    }

    fun changeAppPasscode(current: String, next: String, confirm: String) =
        viewModelScope.launch {
            _passcodeMessage.value = when {
                next != confirm -> "The two new entries don't match."
                else -> appLock.changePasscode(current, next) ?: "Passcode changed."
            }
        }

    fun removeAppPasscode(current: String) = viewModelScope.launch {
        _passcodeMessage.value =
            appLock.clearPasscode(current) ?: "Passcode removed — the app no longer locks."
    }

    fun clearPasscodeMessage() { _passcodeMessage.value = null }
}

@Composable
fun SettingsScreen(
    onOpenRemoteNode: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        CyberTopBar(title = "Settings")

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!state.canRunNode) {
                CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Red) {
                    Text("NOT ENOUGH STORAGE", style = CyberType.HudLabel, color = CyberColors.Red)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A pruned node needs about 25 GB of free space. This device has " +
                            String.format(java.util.Locale.US, "%.1f", state.freeStorageGb) + " GB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.TextSecondary,
                    )
                }
            }

            CyberPanel(Modifier.fillMaxWidth(), glow = true) {
                HudSectionHeader("Network")
                Spacer(Modifier.height(10.dp))
                BitcoinNetwork.entries.forEach { net ->
                    val selected = state.network == net
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setNetwork(net) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                net.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) accentFor(net) else CyberColors.TextPrimary,
                            )
                            Text(
                                if (net.isRealMoney) "real money" else "test coins, no value",
                                style = CyberType.Terminal,
                                color = if (net.isRealMoney) CyberColors.Amber else CyberColors.TextTertiary,
                            )
                        }
                        if (selected) {
                            Text("ACTIVE", style = CyberType.HudLabel, color = accentFor(net))
                        }
                    }
                }
            }

            CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                HudSectionHeader("Display")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DisplayUnit.entries.forEach { u ->
                        val selected = state.unit == u
                        Text(
                            u.label,
                            style = CyberType.HudLabel,
                            color = if (selected) CyberColors.Cyan else CyberColors.TextTertiary,
                            modifier = Modifier
                                .clickable { viewModel.setUnit(u) }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                        )
                    }
                }
            }

            CyberPanel(Modifier.fillMaxWidth(), glow = true) {
                HudSectionHeader("Security")
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = "Block screenshots",
                    body = "Stops screenshots, screen recording, and the app switcher preview.",
                    checked = state.screenSecurity,
                    onChange = viewModel::setScreenSecurity,
                )
                ToggleRow(
                    title = "Require biometrics to sign",
                    body = "Ask for fingerprint or face before signing a transaction.",
                    checked = state.requireBiometric,
                    onChange = viewModel::setBiometric,
                )
                Spacer(Modifier.height(10.dp))
                PasscodePanel(state.passcodeConfigured, viewModel)
            }

            CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                HudSectionHeader("Node link")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Use the node on this device, or point the app at your own Bitcoin Core " +
                        "node over RPC — host, port, rpcuser, rpcpassword.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                CyberButton(
                    text = "Remote node (RPC)",
                    onClick = onOpenRemoteNode,
                    style = CyberButtonStyle.SECONDARY,
                    fillWidth = true,
                )
            }

            StorageSettingsPanel(viewModel)

            ExplorerSettingsPanel(viewModel)

            CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                HudSectionHeader("About")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Bitcoin Mobile runs an unmodified Bitcoin Core 28.3 as a child process. " +
                        "Consensus rules, wallet, and signing are Core's; this app is the interface.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "No analytics of our own. Crash reports stay on this device. " +
                    "Advertising is served through Google AdMob, whose SDK collects " +
                    "the data Google documents for ads. No update checks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberColors.Green,
                )
            }
            DonateFooter()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The app passcode: entirely voluntary, managed here.
 *
 * The app used to demand a passcode on first launch before showing anything.
 * That gate is gone — the app opens straight in, and this panel is the only
 * place a passcode is created. It states plainly what the lock is (a privacy
 * screen over balances and addresses) and is not (encryption of keys).
 */
@Composable
private fun PasscodePanel(configured: Boolean, viewModel: SettingsViewModel) {
    val message by viewModel.passcodeMessage.collectAsStateWithLifecycle()

    Column {
        Text(
            if (configured) "App passcode — set" else "App passcode — not set",
            style = CyberType.HudLabel,
            color = if (configured) CyberColors.Green else CyberColors.TextTertiary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Optional. Locks the app behind a passcode when you background it — " +
                "a privacy screen over balances and addresses, not encryption of " +
                "your keys (that is the wallet passphrase).",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))

        var current by remember { mutableStateOf("") }
        var next by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }

        val numberField = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
        )

        if (configured) {
            CyberTextField(
                value = current, onValueChange = { current = it },
                label = "Current passcode", obscure = true,
                keyboardOptions = numberField,
            )
            Spacer(Modifier.height(8.dp))
        }
        CyberTextField(
            value = next, onValueChange = { next = it },
            label = if (configured) "New passcode" else "Passcode",
            supportingText = "At least ${com.solitech.bitcoincorenode.core.prefs.AppLock.MIN_LENGTH} characters.",
            obscure = true, keyboardOptions = numberField,
        )
        Spacer(Modifier.height(8.dp))
        CyberTextField(
            value = confirm, onValueChange = { confirm = it },
            label = "Confirm", obscure = true, keyboardOptions = numberField,
        )
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CyberButton(
                text = if (configured) "Change" else "Set passcode",
                onClick = {
                    if (configured) viewModel.changeAppPasscode(current, next, confirm)
                    else viewModel.setAppPasscode(next, confirm)
                    current = ""; next = ""; confirm = ""
                },
                enabled = next.length >= com.solitech.bitcoincorenode.core.prefs.AppLock.MIN_LENGTH &&
                    next == confirm &&
                    (!configured || current.isNotEmpty()),
                style = CyberButtonStyle.PRIMARY,
                modifier = Modifier.weight(1f),
            )
            if (configured) {
                CyberButton(
                    text = "Remove",
                    onClick = {
                        viewModel.removeAppPasscode(current)
                        current = ""; next = ""; confirm = ""
                    },
                    enabled = current.isNotEmpty(),
                    style = CyberButtonStyle.DANGER,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        message?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                msg,
                style = CyberType.Terminal,
                color = CyberColors.Amber,
                modifier = Modifier.clickable { viewModel.clearPasscodeMessage() },
            )
        }
    }
}

/**
 * Storage budget — prune options: 15 / 20 / 25 / 30 / 40 / 50 GB.
 *
 * Default is 20 GB. Each option is a bubble-style selector.
 */
@Composable
private fun StorageSettingsPanel(viewModel: SettingsViewModel) {
    val storage by viewModel.storage.collectAsStateWithLifecycle()

    CyberPanel(Modifier.fillMaxWidth(), glow = true) {
        HudSectionHeader("Storage budget")
        Spacer(Modifier.height(10.dp))

        Text(
            "How much block data the node keeps on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))

        // Prune options: 15, 20, 25, 30, 40, 50 GB — two rows of bubbles
        SettingsStore.PRUNE_OPTIONS_MB.chunked(3).forEach { rowOptions ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowOptions.forEach { mb ->
                val label = "${mb / 1000} GB"
                val selected = storage.pruneMb == mb
                val fits = storage.optionsFits[mb] ?: true
                Column(
                    Modifier
                        .weight(1f)
                        .background(
                            if (selected) CyberColors.Cyan.copy(alpha = 0.18f)
                            else CyberColors.SurfaceElevated,
                            CyberShapes.Chip,
                        )
                        .clickable(enabled = fits) { viewModel.setPruneMb(mb) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        style = CyberType.Hash.copy(fontSize = 12.sp),
                        color = when {
                            selected -> CyberColors.Cyan
                            !fits -> CyberColors.TextDisabled
                            else -> CyberColors.TextPrimary
                        },
                    )
                }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        DataRow(
            "Free on device",
            valueColor = if (storage.fits) CyberColors.TextPrimary else CyberColors.Red,
            value = String.format(java.util.Locale.US, "%.1f GB", storage.freeGb),
        )
        if (!storage.fits) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Not enough room. Pick a smaller budget or free up storage.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.Red,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("THIS LIMITS STORAGE, NOT DATA USE", style = CyberType.HudLabel, color = CyberColors.Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "A pruned node still downloads and verifies every block ever made (~700 GB) " +
                "and deletes them as it goes, keeping only the most recent " +
                (storage.pruneMb / 1000) + " GB. Pruning shrinks the footprint, not the download.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Use Fast sync on the Node screen to avoid the full download. " +
                "It loads a UTXO snapshot (~11 GB), is usable within minutes, and " +
                "verifies history in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.Green,
        )
    }
}

/**
 * Block-explorer fallback — privacy-first, opt-in.
 */
@Composable
private fun ExplorerSettingsPanel(viewModel: SettingsViewModel) {
    val explorer by viewModel.explorer.collectAsStateWithLifecycle()

    CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
        HudSectionHeader("Block explorer fallback", accent = CyberColors.Amber)
        Spacer(Modifier.height(10.dp))
        Text(
            "Reads balances and transaction history from a Blockbook server. " +
                "Useful while the node is still syncing.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Every lookup hands one of your Bitcoin addresses to that server, from your IP. " +
                "The operator can link them to you. This linkage is permanent.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.Amber,
        )

        Spacer(Modifier.height(14.dp))
        CyberTextField(
            value = explorer.url,
            onValueChange = viewModel::setExplorerUrl,
            label = "Blockbook server",
            placeholder = "https://your-blockbook/api/v2",
            monospace = true,
            supportingText = "Point this at your own Blockbook if you run one.",
        )

        Spacer(Modifier.height(6.dp))
        ToggleRow(
            title = "Use the explorer",
            body = if (explorer.url.isBlank())
                "Enter a Blockbook server address above to enable."
            else
                "Off by default. Sends your addresses to the configured server.",
            checked = explorer.enabled,
            onChange = viewModel::setExplorerEnabled,
            enabled = explorer.url.isNotBlank(),
        )
        ToggleRow(
            title = "Route through Tor",
            body = if (!explorer.enabled) "Turn the explorer on first."
            else "Hides your IP from the explorer operator. Requires Orbot.",
            checked = explorer.overTor,
            onChange = viewModel::setExplorerOverTor,
            enabled = explorer.enabled,
        )

        if (explorer.enabled && explorer.url.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    if (explorer.overTor) "Active over Tor" else "Active",
                    if (explorer.overTor) CyberColors.Violet else CyberColors.Green,
                    pulsing = true,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) CyberColors.TextPrimary else CyberColors.TextDisabled,
            )
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = CyberColors.TextTertiary)
        }
        Spacer(Modifier.height(0.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CyberColors.TextOnAccent,
                checkedTrackColor = CyberColors.Cyan,
                uncheckedThumbColor = CyberColors.TextTertiary,
                uncheckedTrackColor = CyberColors.SurfaceHigh,
                uncheckedBorderColor = CyberColors.Border,
            ),
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
