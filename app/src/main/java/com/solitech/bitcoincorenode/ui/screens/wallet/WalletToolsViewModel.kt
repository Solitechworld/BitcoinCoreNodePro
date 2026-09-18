package com.solitech.bitcoincorenode.ui.screens.wallet

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.BitcoinNetwork
import com.solitech.bitcoincorenode.core.model.EncryptionState
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.data.repo.MigrationResult
import com.solitech.bitcoincorenode.data.repo.WalletRepository
import com.solitech.bitcoincorenode.wallet.WalletImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletToolsState(
    val walletName: String = "",
    val isEncrypted: Boolean = false,
    /** Core's own format string: "bdb" for legacy, "sqlite" for descriptor. */
    val format: String = "",
    val isLegacy: Boolean = false,
    val txCount: Long? = null,
    val keypoolSize: Long? = null,
    val busy: Boolean = false,
    val busyLabel: String = "Working",
    val error: String? = null,
    val notice: String? = null,
    val migration: MigrationResult? = null,
    val lastBackupPath: String? = null,
    val signature: String? = null,
    val verifyResult: Boolean? = null,
)

@HiltViewModel
class WalletToolsViewModel @Inject constructor(
    private val walletRepo: WalletRepository,
    private val importer: WalletImporter,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(WalletToolsState())
    val state: StateFlow<WalletToolsState> = _state.asStateFlow()

    private var wallet: String = ""

    fun bind(name: String) {
        if (wallet == name) return
        wallet = name
        _state.update { it.copy(walletName = name) }
        refresh()
    }

    private fun refresh() = viewModelScope.launch {
        runCatching { walletRepo.info(wallet) }
            .onSuccess { info ->
                _state.update {
                    it.copy(
                        isEncrypted = info.encryption != EncryptionState.UNENCRYPTED,
                        format = info.format,
                        isLegacy = info.format.equals("bdb", ignoreCase = true),
                        txCount = info.txcount,
                        keypoolSize = info.keypoolsize,
                    )
                }
            }
            // Swallowing this left format/isLegacy blank with no error, which
            // disabled the legacy-only tools for a perfectly good legacy
            // wallet and made the backup export pick the wrong RPC path.
            .onFailure { e ->
                _state.update {
                    it.copy(
                        error = (e as? RpcError)?.userMessage()
                            ?: (e.message ?: "Could not read this wallet's info")
                    )
                }
            }
    }

    /**
     * Runs one tool operation with consistent busy/error handling.
     *
     * Every operation on this screen either completes or explains itself. The
     * `notice` on success matters as much as the error: after an irreversible
     * step like encrypting a wallet, silence is indistinguishable from failure.
     */
    private fun run(label: String, block: suspend () -> String?) = viewModelScope.launch {
        _state.update { it.copy(busy = true, busyLabel = label, error = null, notice = null) }
        try {
            val notice = block()
            _state.update { it.copy(busy = false, notice = notice) }
            refresh()
        } catch (e: RpcError) {
            _state.update { it.copy(busy = false, error = e.userMessage()) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message ?: "Unexpected failure") }
        }
    }

    // -- import --------------------------------------------------------------

    /**
     * A rescan that cannot run must be explained, not swallowed. The worst
     * case — pruned blocks covering the wallet's history — is the one that
     * otherwise reads as "my imported wallet has no balance" forever.
     */
    private fun surfaceRescan(outcome: WalletRepository.RescanOutcome) {
        when (outcome) {
            WalletRepository.RescanOutcome.Started,
            WalletRepository.RescanOutcome.AlreadyRunning -> Unit
            WalletRepository.RescanOutcome.NeedsUnlock -> _state.update {
                it.copy(
                    error = "The wallet opened, but it is passphrase-locked and Core " +
                        "refuses to rescan a locked wallet. Enter its passphrase in the " +
                        "import form above and import again — or unlock it from the " +
                        "console with walletpassphrase \"…\" 600 and rerun " +
                        "rescanblockchain. The balance will not fill in until the scan runs.",
                )
            }
            is WalletRepository.RescanOutcome.BlockedByPrune -> _state.update {
                it.copy(
                    error = "The rescan could not cover this wallet's full history: " +
                        "this node is pruned and no longer has the oldest blocks. " +
                        "Coins received before the prune horizon will not show until " +
                        "the node resyncs deeper (raise the storage budget) or the " +
                        "wallet is re-imported with descriptors from a full node.\n\n" +
                        "Core said: ${outcome.detail}",
                )
            }
            is WalletRepository.RescanOutcome.Failed -> _state.update {
                it.copy(error = "The rescan after import failed: ${outcome.detail}")
            }
        }
    }

    /**
     * Rescan with unlock retry: for a locked, encrypted wallet, unlock briefly
     * with the passphrase the user typed (if any) and scan again. Core's
     * rescanblockchain requires an unlocked wallet (error -13).
     */
    private suspend fun rescanWithUnlockRetry(wallet: String, passphrase: String?) {
        var outcome = walletRepo.rescanImportedWallet(wallet)
        if (outcome is WalletRepository.RescanOutcome.NeedsUnlock && !passphrase.isNullOrBlank()) {
            runCatching { walletRepo.unlock(wallet, passphrase, seconds = 600) }
                .onSuccess {
                    outcome = walletRepo.rescanImportedWallet(wallet)
                    // Re-lock when done; Core re-locks itself only on timeout.
                    runCatching { walletRepo.lock(wallet) }
                }
        }
        surfaceRescan(outcome)
    }

    fun importLegacy(uri: Uri, name: String, passphrase: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, busyLabel = "Importing", error = null, notice = null) }
        try {
            val network = settings.network.first()

            when (val staged = importer.stageLegacyWallet(uri, name, network)) {
                is WalletImporter.Result.Rejected -> {
                    _state.update { it.copy(busy = false, error = staged.reason) }
                    return@launch
                }
                is WalletImporter.Result.Ready -> {
                    _state.update { it.copy(busyLabel = "Opening") }
                    when (val outcome = walletRepo.importLegacyWallet(
                        name = staged.walletName,
                        passphrase = passphrase.takeIf { it.isNotBlank() },
                    )) {
                        is WalletRepository.LegacyImport.Loaded -> {
                            // Make the imported wallet the one the app acts on,
                            // then rescan. Without the rescan, a wallet whose
                            // history records did not survive the trip (blank
                            // export, pruned-node migration, watch-only source)
                            // shows a zero balance with no explanation.
                            settings.setActiveWallet(outcome.walletName)
                            // The wallet's own records, straight from the file:
                            // this is the ground truth that separates "the app
                            // fails to show it" from "the file never had it".
                            val facts = runCatching { walletRepo.info(outcome.walletName) }
                            val factText = facts.getOrNull()?.let {
                                "\n\nOn file in this wallet: ${it.txcount} transactions, " +
                                    "format ${it.format.ifBlank { "legacy" }}" +
                                    (it.birthtime?.takeIf { t -> t > 0 }?.let { t ->
                                        ", created ${
                                            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
                                                .format(java.util.Date(t * 1000))
                                        }"
                                    } ?: "")
                            } ?: ""
                            val warning = if ((facts.getOrNull()?.txcount ?: -1) == 0L) {
                                "\n\nThis wallet file contains NO transaction records — an " +
                                    "empty history and zero balance are what Core itself " +
                                    "reports for it. That usually means the file picked was " +
                                    "a fresh/blank wallet or not the one holding the funds. " +
                                    "Re-pick the wallet.dat from your full node's wallet " +
                                    "directory."
                            } else ""
                            _state.update {
                                it.copy(
                                    busy = false,
                                    notice = "Opened \"${outcome.walletName}\" as a legacy " +
                                        "wallet and a rescan is running — balances fill in " +
                                        "as it progresses. Nothing was converted and your " +
                                        "file is unchanged." + factText + warning,
                                )
                            }
                            viewModelScope.launch {
                                rescanWithUnlockRetry(
                                    outcome.walletName,
                                    passphrase.takeIf { it.isNotBlank() },
                                )
                            }
                        }
                        is WalletRepository.LegacyImport.Migrated -> {
                            val r = outcome.result
                            settings.setActiveWallet(r.walletName)
                            _state.update {
                                it.copy(
                                    busy = false,
                                    migration = r,
                                    notice = buildString {
                                        append("This node cannot open legacy wallets, so it ")
                                        append("was converted to a descriptor wallet named \"")
                                        append(r.walletName).append("\".")
                                        if (r.watchonlyName != null || r.solvablesName != null) {
                                            append(" Extra wallets were created for scripts ")
                                            append("it tracked without keys — check them ")
                                            append("before assuming anything is missing.")
                                        }
                                        append(" A rescan is running — balances fill in as it ")
                                        append("progresses.")
                                    },
                                )
                            }
                            viewModelScope.launch {
                                rescanWithUnlockRetry(
                                    r.walletName,
                                    passphrase.takeIf { it.isNotBlank() },
                                )
                            }
                        }
                        is WalletRepository.LegacyImport.NeedsPassphrase -> {
                            _state.update {
                                it.copy(
                                    busy = false,
                                    error = "This wallet is encrypted, and this node has to " +
                                        "convert it rather than open it. Enter its passphrase " +
                                        "above and try again.",
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: RpcError) {
            // A wallet file staged for the *selected* network while the node
            // still runs the previous one (the setting applies at next start)
            // fails as "not found". Naming the likely cause turns a baffling
            // failure into a two-tap fix.
            val hint = if (e.userMessage().contains("not found", ignoreCase = true) ||
                e.userMessage().contains("does not exist", ignoreCase = true)
            ) {
                "\n\nThe node may still be running on a different network than the " +
                    "one selected in Settings — that change only takes effect after " +
                    "the node restarts. Restart the node and import again."
            } else ""
            _state.update { it.copy(busy = false, error = e.userMessage() + hint) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message ?: "Import failed") }
        }
    }

    // -- encryption ----------------------------------------------------------

    fun encrypt(passphrase: String) = run("Encrypting") {
        walletRepo.encryptWallet(wallet, passphrase)
        "Wallet encrypted. Take a fresh backup now — any backup made before this " +
            "will not open it."
    }

    fun changePassphrase(oldPass: String, newPass: String) = run("Changing") {
        walletRepo.changePassphrase(wallet, oldPass, newPass)
        "Passphrase changed. Older backups still open with the previous passphrase."
    }

    // -- backup --------------------------------------------------------------

    fun backup() = run("Backing up") {
        val dest = importer.backupDestination(wallet)
        walletRepo.backupWallet(wallet, dest.absolutePath)
        _state.update { it.copy(lastBackupPath = dest.absolutePath) }
        "Backup written."
    }

    /**
     * Saves a wallet.dat backup to a user-picked location (typically Documents
     * via the system picker). The node writes to its private dir first; the
     * copy to the picked URI is the only extra step.
     */
    fun backupToDocuments(uri: Uri) = run("Saving") {
        val dest = importer.backupDestination(wallet)
        walletRepo.backupWallet(wallet, dest.absolutePath)
        importer.copyToUri(dest, uri)
        "Backup saved to the location you picked."
    }

    /**
     * Exports wallet keys/descriptors as text — dumpwallet for legacy wallets,
     * listdescriptors (private) for descriptor wallets — to a picked location.
     */
    fun exportDumpToDocuments(uri: Uri, legacy: Boolean) = run("Exporting") {
        if (legacy) {
            val dest = importer.dumpDestination(wallet)
            walletRepo.dumpWallet(wallet, dest.absolutePath)
            importer.copyToUri(dest, uri)
        } else {
            importer.writeTextToUri(walletRepo.dumpDescriptorsPrivate(wallet), uri)
        }
        "Key dump saved to the location you picked."
    }

    /** Imports a dumpwallet text file into this (legacy) wallet. */
    fun importDump(uri: Uri) = run("Importing dump") {
        val staged = importer.stageDumpFile(uri, "import-${System.currentTimeMillis()}.dump")
        walletRepo.importWallet(wallet, staged.absolutePath)
        "Wallet dump imported. A rescan is running — balances will fill in."
    }

    fun importPrivateKey(key: String, label: String) = run("Importing key") {
        walletRepo.importPrivKey(wallet, key.trim(), label)
        "Private key imported and rescan started."
    }

    fun importWatchAddress(address: String, label: String) = run("Importing address") {
        walletRepo.importAddress(wallet, address.trim(), label)
        "Address imported as watch-only and rescan started."
    }

    // -- messages ------------------------------------------------------------

    fun signMessage(address: String, message: String) = viewModelScope.launch {
        // Checked before the call so the user gets the real reason rather than
        // Core's bare "Invalid address" for what is actually an unsupported
        // address type.
        if (address.startsWith("bc1") || address.startsWith("tb1")) {
            _state.update {
                it.copy(
                    error = "Message signing only works with legacy addresses starting " +
                        "with 1. SegWit and Taproot addresses cannot be used here — that " +
                        "is a limit of the message signing standard, not of this app.",
                )
            }
            return@launch
        }
        _state.update { it.copy(busy = true, busyLabel = "Signing", error = null, verifyResult = null) }
        try {
            val sig = walletRepo.signMessage(wallet, address, message)
            _state.update { it.copy(busy = false, signature = sig, notice = "Message signed.") }
        } catch (e: RpcError) {
            _state.update { it.copy(busy = false, error = e.userMessage()) }
        }
    }

    fun verifyMessage(address: String, signature: String, message: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, busyLabel = "Verifying", error = null) }
        try {
            val ok = walletRepo.verifyMessage(address, signature, message)
            _state.update { it.copy(busy = false, verifyResult = ok) }
        } catch (e: RpcError) {
            _state.update { it.copy(busy = false, error = e.userMessage(), verifyResult = false) }
        }
    }

    // -- maintenance ---------------------------------------------------------

    /**
     * Maintenance rescan. Uses the same smart path as imports: start at the
     * prune height (Core rejects a range below it entirely) and explain a
     * locked-wallet refusal instead of surfacing a bare -13.
     */
    fun rescan(passphrase: String? = null) = viewModelScope.launch {
        val outcome = walletRepo.rescanImportedWallet(wallet)
        if (outcome is WalletRepository.RescanOutcome.NeedsUnlock && !passphrase.isNullOrBlank()) {
            runCatching { walletRepo.unlock(wallet, passphrase, seconds = 600) }
                .onSuccess {
                    val retried = walletRepo.rescanImportedWallet(wallet)
                    runCatching { walletRepo.lock(wallet) }
                    surfaceRescan(retried)
                    return@launch
                }
        }
        surfaceRescan(outcome)
    }
}
