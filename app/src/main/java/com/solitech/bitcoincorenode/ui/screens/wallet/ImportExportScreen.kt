package com.solitech.bitcoincorenode.ui.screens.wallet

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.solitech.bitcoincorenode.core.model.EncryptionState
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.data.repo.MigrationResult
import com.solitech.bitcoincorenode.data.repo.WalletRepository
import com.solitech.bitcoincorenode.wallet.WalletImporter
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberDivider
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberTextField
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.DataRow
import com.solitech.bitcoincorenode.ui.components.DonateFooter
import com.solitech.bitcoincorenode.ui.components.ErrorPanel
import com.solitech.bitcoincorenode.ui.components.HudSectionHeader
import com.solitech.bitcoincorenode.ui.components.StatusChip
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportExportState(
    val loadedWallets: List<WalletSummary> = emptyList(),
    val busy: Boolean = false,
    val busyLabel: String = "Working",
    val error: String? = null,
    val notice: String? = null,
    val migration: MigrationResult? = null,
)

data class WalletSummary(
    val name: String,
    val format: String,
    val txCount: Long,
    val isEncrypted: Boolean,
    val isLegacy: Boolean,
)

/**
 * Node-level import and export — deliberately separate from wallet creation.
 *
 * Import puts an existing wallet (wallet.dat, descriptor backup, or key dump)
 * onto this node; export gets one off it. Neither has anything to do with
 * making a new wallet, and burying them inside a per-wallet tools screen
 * made the two most safety-critical flows in the app the hardest to find.
 */
@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val walletRepo: WalletRepository,
    private val importer: WalletImporter,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportExportState())
    val state: StateFlow<ImportExportState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val loaded = walletRepo.listLoadedWallets()
            val summaries = loaded.mapNotNull { name ->
                runCatching { walletRepo.info(name) }.getOrNull()?.let { info ->
                    WalletSummary(
                        name = name,
                        format = info.format,
                        txCount = info.txcount,
                        isEncrypted = info.encryption != EncryptionState.UNENCRYPTED,
                        isLegacy = info.format.equals("bdb", ignoreCase = true),
                    )
                }
            }
            _state.update { it.copy(loadedWallets = summaries, error = null) }
        }
    }

    /**
     * Imports a legacy wallet.dat and reports the FILE's own facts — format
     * and transaction count — because that is the evidence that separates
     * "the app fails to show it" from "the file never had it".
     */
    fun importLegacy(uri: Uri, name: String, passphrase: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, busyLabel = "Importing", error = null, notice = null) }
        try {
            val network = settings.network.first()
            when (val staged = importer.stageLegacyWallet(uri, name, network)) {
                is WalletImporter.Result.Rejected ->
                    _state.update { it.copy(busy = false, error = staged.reason) }
                is WalletImporter.Result.Ready -> {
                    _state.update { it.copy(busyLabel = "Opening") }
                    when (val outcome = walletRepo.importLegacyWallet(
                        name = staged.walletName,
                        passphrase = passphrase.takeIf { it.isNotBlank() },
                    )) {
                        is WalletRepository.LegacyImport.Loaded -> {
                            settings.setActiveWallet(outcome.walletName)
                            finishImport(outcome.walletName, converted = false, passphrase)
                        }
                        is WalletRepository.LegacyImport.Migrated -> {
                            val r = outcome.result
                            settings.setActiveWallet(r.walletName)
                            _state.update { it.copy(migration = r) }
                            finishImport(r.walletName, converted = true, passphrase)
                        }
                        is WalletRepository.LegacyImport.NeedsPassphrase ->
                            _state.update {
                                it.copy(
                                    busy = false,
                                    error = "This wallet is encrypted and must be converted " +
                                        "on this node. Enter its passphrase above and try again.",
                                )
                            }
                    }
                }
            }
        } catch (e: RpcError) {
            val hint = if (e.userMessage().contains("not found", true))
                "\n\nThe node may still be running on a different network than Settings " +
                    "selected — that applies after a node restart." else ""
            _state.update { it.copy(busy = false, error = e.userMessage() + hint) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message ?: "Import failed") }
        }
    }

    private suspend fun finishImport(walletName: String, converted: Boolean, passphrase: String = "") {
        val facts = runCatching { walletRepo.info(walletName) }.getOrNull()
        val txCount = facts?.txcount ?: -1L
        val factText = facts?.let {
            "\n\nOn file in this wallet: $txCount transactions, format " +
                it.format.ifBlank { if (converted) "sqlite" else "legacy" }
        } ?: ""
        val warning = if (txCount == 0L) {
            "\n\nThis file contains NO transaction records — zero balance and empty " +
                "history are what Core itself reports for it. The file picked was " +
                "likely a fresh/blank wallet or not the one holding funds."
        } else {
            "\n\nA rescan is running — balances fill in as it progresses."
        }
        _state.update {
            it.copy(
                busy = false,
                notice = "Imported \"${walletName}\"" +
                    (if (converted) " (converted to a descriptor wallet)" else " (opened as-is)") +
                    factText + warning,
            )
        }
        // Rescan with unlock-retry, mirroring the tools screen: Core refuses
        // to rescan a locked wallet (-13), so if the user typed a passphrase,
        // unlock briefly, scan, re-lock.
        var outcome = walletRepo.rescanImportedWallet(walletName)
        if (outcome is WalletRepository.RescanOutcome.NeedsUnlock && passphrase.isNotBlank()) {
            runCatching { walletRepo.unlock(walletName, passphrase, seconds = 600) }
                .onSuccess {
                    outcome = walletRepo.rescanImportedWallet(walletName)
                    runCatching { walletRepo.lock(walletName) }
                }
        }
        surface(outcome)
        refresh()
    }

    private fun surface(outcome: WalletRepository.RescanOutcome) {
        when (outcome) {
            WalletRepository.RescanOutcome.Started,
            WalletRepository.RescanOutcome.AlreadyRunning -> Unit
            WalletRepository.RescanOutcome.NeedsUnlock -> _state.update {
                it.copy(error = "The wallet opened, but it is passphrase-locked and Core " +
                    "refuses to rescan a locked wallet. Unlock it from the console with " +
                    "walletpassphrase \"…\" 600, then rescanblockchain.")
            }
            is WalletRepository.RescanOutcome.BlockedByPrune -> _state.update {
                it.copy(error = "The rescan could not cover this wallet's full history: " +
                    "this node is pruned and no longer has the oldest blocks.")
            }
            is WalletRepository.RescanOutcome.Failed -> _state.update {
                it.copy(error = "Rescan after import failed: ${outcome.detail}")
            }
        }
    }

    /** Saves a wallet.dat backup to a user-picked location. */
    fun backupWallet(wallet: String, uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true, busyLabel = "Backing up") }
        try {
            val dest = importer.backupDestination(wallet)
            walletRepo.backupWallet(wallet, dest.absolutePath)
            importer.copyToUri(dest, uri)
            _state.update { it.copy(busy = false, notice = "Backup of \"$wallet\" saved.") }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message ?: "Backup failed") }
        }
    }

    /** Exports keys: dumpwallet for legacy, private descriptors otherwise. */
    fun exportKeys(wallet: String, legacy: Boolean, uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true, busyLabel = "Exporting") }
        try {
            if (legacy) {
                val dest = importer.dumpDestination(wallet)
                walletRepo.dumpWallet(wallet, dest.absolutePath)
                importer.copyToUri(dest, uri)
            } else {
                importer.writeTextToUri(walletRepo.dumpDescriptorsPrivate(wallet), uri)
            }
            _state.update { it.copy(busy = false, notice = "Key export of \"$wallet\" saved.") }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message ?: "Export failed") }
        }
    }
}

@Composable
fun ImportExportScreen(
    onBack: () -> Unit,
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().imePadding()) {
        CyberTopBar(
            title = "Import / Export",
            onBack = onBack,
            trailing = {
                if (state.busy) StatusChip(state.busyLabel, CyberColors.Amber, pulsing = true)
            },
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { ErrorPanel("That didn't work", it) }
            state.notice?.let { notice ->
                CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Green) {
                    Text(notice, style = MaterialTheme.typography.bodyMedium,
                        color = CyberColors.TextPrimary)
                }
            }

            ImportPanel(state, viewModel)
            ExportPanel(state, viewModel)

            DonateFooter()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ImportPanel(state: ImportExportState, vm: ImportExportViewModel) {
    var picked by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri -> picked = uri
    }

    CyberPanel(Modifier.fillMaxWidth()) {
        HudSectionHeader("Import a wallet")
        Spacer(Modifier.height(10.dp))
        Text(
            "Bring an existing wallet onto this node: a legacy wallet.dat from Bitcoin " +
                "Core, opened as-is with nothing converted. Your file is copied and left " +
                "untouched.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(14.dp))
        CyberButton(
            text = picked?.let { "File selected — pick another" } ?: "Choose wallet.dat",
            onClick = { picker.launch(arrayOf("*/*")) },
            style = if (picked == null) CyberButtonStyle.PRIMARY else CyberButtonStyle.SECONDARY,
            fillWidth = true,
        )
        picked?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.lastPathSegment ?: it.toString(),
                style = CyberType.Terminal, color = CyberColors.TextTertiary)
        }
        Spacer(Modifier.height(12.dp))
        CyberTextField(
            value = name, onValueChange = { name = it },
            label = "Wallet name on this device", placeholder = "imported",
            monospace = true,
            supportingText = "Letters, digits, dash, underscore. No spaces.",
        )
        Spacer(Modifier.height(10.dp))
        CyberTextField(
            value = passphrase, onValueChange = { passphrase = it },
            label = "Passphrase (only if encrypted + conversion needed)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(14.dp))
        CyberButton(
            text = "Import wallet",
            onClick = { picked?.let { vm.importLegacy(it, name.trim(), passphrase) } },
            enabled = picked != null && name.isNotBlank() && !state.busy,
            fillWidth = true,
        )
        state.migration?.let { m ->
            Spacer(Modifier.height(12.dp))
            DataRow("Converted to", value = m.walletName)
            m.watchonlyName?.let { DataRow("Watch-only wallet", value = it) }
            m.solvablesName?.let { DataRow("Solvables wallet", value = it) }
        }
    }
}

@Composable
private fun ExportPanel(state: ImportExportState, vm: ImportExportViewModel) {
    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        HudSectionHeader("Export a wallet")
        Spacer(Modifier.height(10.dp))
        Text(
            "Save a wallet database backup (wallet.dat / wallet file) or a key export " +
                "to Documents or anywhere you pick. The key export contains everything " +
                "needed to spend — treat it accordingly.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )

        if (state.loadedWallets.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "No wallet is loaded. Start the node — wallets load with it — or import one above.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.TextTertiary,
            )
        }

        state.loadedWallets.forEach { w ->
            val backupPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri -> uri?.let { vm.backupWallet(w.name, it) } }
            val keysPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/plain"),
            ) { uri -> uri?.let { vm.exportKeys(w.name, w.isLegacy, it) } }

            Spacer(Modifier.height(14.dp))
            CyberDivider()
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, style = MaterialTheme.typography.titleMedium,
                        color = CyberColors.TextPrimary)
                    Text(
                        "${w.format.ifBlank { "wallet" }} · ${w.txCount} tx on file" +
                            (if (w.isEncrypted) " · encrypted" else " · no passphrase"),
                        style = CyberType.Terminal,
                        color = if (w.isEncrypted) CyberColors.Green else CyberColors.Red,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CyberButton(
                    text = "Backup",
                    onClick = { backupPicker.launch("${w.name}-backup.dat") },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
                CyberButton(
                    text = if (w.isLegacy) "Keys (dump)" else "Descriptors",
                    onClick = { keysPicker.launch("${w.name}-keys.txt") },
                    style = CyberButtonStyle.SECONDARY,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
