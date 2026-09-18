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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solitech.bitcoincorenode.ui.components.CyberButton
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

/**
 * Wallet maintenance: import a legacy wallet.dat, manage encryption, back up,
 * restore, sign and verify messages, rescan.
 *
 * Everything here is a Bitcoin Core RPC with a explanation attached. The
 * explanations are the point: these are the operations where a user acting on
 * a wrong mental model loses money, and every one of them carries a real
 * caveat that Core's own docs state and most wallet UIs quietly drop.
 */
@Composable
fun WalletToolsScreen(
    walletName: String,
    onBack: () -> Unit,
    viewModel: WalletToolsViewModel = hiltViewModel(),
) {
    LaunchedEffect(walletName) { viewModel.bind(walletName) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().imePadding()) {
        CyberTopBar(
            title = "Wallet tools",
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

            ImportLegacyPanel(state, viewModel)
            EncryptionPanel(state, viewModel)
            BackupPanel(state, viewModel)
            ImportKeysPanel(state, viewModel)
            MessagePanel(state, viewModel)
            MaintenancePanel(state, viewModel)

            DonateFooter()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ── import a legacy wallet.dat ─────────────────────────────────────────── */

@Composable
private fun ImportLegacyPanel(state: WalletToolsState, vm: WalletToolsViewModel) {
    var newName by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        // Any MIME type: wallet.dat has no registered type, and providers
        // report it inconsistently as octet-stream, empty, or nothing at all.
        // Filtering here would hide the user's file from their own picker.
        ActivityResultContracts.OpenDocument(),
    ) { uri -> picked = uri }

    CyberPanel(Modifier.fillMaxWidth()) {
        HudSectionHeader("Import a legacy wallet.dat")
        Spacer(Modifier.height(10.dp))
        Text(
            "This build ships Bitcoin Core 28.3, which opens legacy Berkeley DB " +
                "wallets directly — no conversion, no passphrase needed just to open " +
                "one. Your file is left exactly as it is.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your original file is never modified. Core also writes its own " +
                "timestamped .legacy.bak before converting, and this screen tells you " +
                "where it put it.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextTertiary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "If this node ever has to convert instead of open — which happens on Core 29 " +
                "and newer — one migration can produce up to three wallets: your keys, a " +
                "watch-only wallet for scripts it tracked without keys, and a \"solvables\" " +
                "wallet. That is normal; funds are not missing if they land in the second.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.Amber,
        )

        Spacer(Modifier.height(14.dp))
        CyberButton(
            text = picked?.let { "File selected" } ?: "Choose wallet.dat",
            onClick = { picker.launch(arrayOf("*/*")) },
            style = if (picked == null) CyberButtonStyle.PRIMARY else CyberButtonStyle.SECONDARY,
            fillWidth = true,
        )
        picked?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.lastPathSegment ?: it.toString(), style = CyberType.Terminal,
                color = CyberColors.TextTertiary)
        }

        Spacer(Modifier.height(12.dp))
        CyberTextField(
            value = newName,
            onValueChange = { newName = it },
            label = "New wallet name",
            placeholder = "imported",
            monospace = true,
            supportingText = "Letters, digits, dash and underscore. Becomes a folder name.",
        )
        Spacer(Modifier.height(10.dp))
        CyberTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = "Passphrase (optional)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = "Leave this empty. A passphrase protects spending, not " +
                "opening, so it is not needed to import. It is only asked for if the " +
                "node turns out to need a conversion and the wallet is encrypted.",
        )

        Spacer(Modifier.height(14.dp))
        CyberButton(
            text = "Import wallet",
            onClick = { picked?.let { vm.importLegacy(it, newName.trim(), passphrase) } },
            enabled = picked != null && newName.isNotBlank() && !state.busy,
            fillWidth = true,
        )

        state.migration?.let { m ->
            Spacer(Modifier.height(14.dp))
            CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Green, glow = false) {
                Text("Migrated".uppercase(), style = CyberType.HudLabel, color = CyberColors.Green)
                Spacer(Modifier.height(8.dp))
                DataRow("Wallet", value = m.walletName)
                m.watchonlyName?.let { DataRow("Watch-only", value = it) }
                m.solvablesName?.let { DataRow("Solvables", value = it) }
                Spacer(Modifier.height(8.dp))
                Text("Backup of your original:", style = CyberType.HudLabel,
                    color = CyberColors.TextTertiary)
                Text(m.backupPath, style = CyberType.Terminal, color = CyberColors.TextSecondary)
            }
        }
    }
}

/* ── encryption ─────────────────────────────────────────────────────────── */

@Composable
private fun EncryptionPanel(state: WalletToolsState, vm: WalletToolsViewModel) {
    var oldPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val mismatch = confirm.isNotEmpty() && confirm != newPass
    val encrypted = state.isEncrypted

    CyberPanel(Modifier.fillMaxWidth()) {
        HudSectionHeader(if (encrypted) "Change passphrase" else "Encrypt this wallet")
        Spacer(Modifier.height(10.dp))

        if (!encrypted) {
            Text(
                "This wallet has no passphrase. Anyone who unlocks this phone can spend " +
                    "from it.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.Red,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Encrypting is one-way — Core has no decrypt operation — and it replaces " +
                    "the keypool, so any backup taken before this will not open the wallet " +
                    "afterwards. Take a fresh backup once it is done.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.Amber,
            )
        } else {
            Text(
                "Changing the passphrase re-encrypts the keys. Older backups still open " +
                    "with the OLD passphrase — keep track of which is which, or replace " +
                    "them.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.TextSecondary,
            )
        }

        Spacer(Modifier.height(14.dp))
        if (encrypted) {
            CyberTextField(
                value = oldPass,
                onValueChange = { oldPass = it },
                label = "Current passphrase",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(10.dp))
        }
        CyberTextField(
            value = newPass,
            onValueChange = { newPass = it },
            label = if (encrypted) "New passphrase" else "Passphrase",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = "There is no recovery. If you forget this, the coins are gone.",
        )
        Spacer(Modifier.height(10.dp))
        CyberTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = "Confirm",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = mismatch,
            supportingText = if (mismatch) "These do not match." else null,
        )

        Spacer(Modifier.height(14.dp))
        CyberButton(
            text = if (encrypted) "Change passphrase" else "Encrypt wallet",
            onClick = {
                if (encrypted) vm.changePassphrase(oldPass, newPass)
                else vm.encrypt(newPass)
            },
            enabled = !state.busy && newPass.isNotBlank() && newPass == confirm &&
                (!encrypted || oldPass.isNotBlank()),
            fillWidth = true,
        )
    }
}

/* ── backup / restore ───────────────────────────────────────────────────── */

@Composable
private fun BackupPanel(state: WalletToolsState, vm: WalletToolsViewModel) {
    // SAF pickers for saving into Documents (or anywhere the user chooses).
    // The app cannot write Documents/ directly on modern Android; the system
    // picker grants exactly the file the user picked, which is also the
    // honest UI: the user sees where their keys are going.
    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { vm.backupToDocuments(it) } }

    val dumpPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { vm.exportDumpToDocuments(it, legacy = state.isLegacy) } }

    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        HudSectionHeader("Backup")
        Spacer(Modifier.height(10.dp))
        Text(
            "Writes a copy of the wallet database. It holds your descriptors and, " +
                "for a hot wallet, the encrypted private keys.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This is not a substitute for your seed phrase. A file on the same device " +
                "as the wallet protects you from a mistake, not from losing the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.Amber,
        )
        Spacer(Modifier.height(14.dp))
        CyberButton(
            text = "Save backup to Documents",
            onClick = { backupPicker.launch("${state.walletName}-backup.dat") },
            enabled = !state.busy,
            fillWidth = true,
        )
        Spacer(Modifier.height(8.dp))
        CyberButton(
            text = if (state.isLegacy) "Export keys (dumpwallet)" else "Export descriptors with keys",
            onClick = { dumpPicker.launch("${state.walletName}-keys.txt") },
            style = CyberButtonStyle.SECONDARY,
            enabled = !state.busy,
            fillWidth = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The key export contains everything needed to spend this wallet. Move it " +
                "somewhere safe and delete it from anything it passes through.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.Red,
        )
        state.lastBackupPath?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = CyberType.Terminal, color = CyberColors.TextTertiary)
        }
    }
}

/* ── import keys, addresses, wallet dumps ───────────────────────────────── */

@Composable
private fun ImportKeysPanel(state: WalletToolsState, vm: WalletToolsViewModel) {
    var key by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    val dumpPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importDump(it) } }

    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        HudSectionHeader("Import keys and addresses")
        Spacer(Modifier.height(10.dp))
        Text(
            "Sweeps a single private key into this wallet (importprivkey), adds an " +
                "address to watch without its key, or loads a whole dumpwallet text " +
                "file (importwallet).",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )

        Spacer(Modifier.height(14.dp))
        CyberTextField(
            value = key,
            onValueChange = { key = it },
            label = "Private key (WIF)",
            placeholder = "5… / L… / K…",
            monospace = true,
            supportingText = if (state.isLegacy)
                "Only on legacy wallets — descriptor wallets import via descriptors."
            else "This wallet is a descriptor wallet; use a descriptor import instead.",
        )
        Spacer(Modifier.height(8.dp))
        CyberTextField(
            value = label,
            onValueChange = { label = it },
            label = "Label (optional)",
        )
        Spacer(Modifier.height(8.dp))
        CyberButton(
            text = "Import private key",
            onClick = { vm.importPrivateKey(key, label); key = "" },
            enabled = !state.busy && state.isLegacy && key.isNotBlank(),
            fillWidth = true,
        )

        Spacer(Modifier.height(16.dp))
        CyberTextField(
            value = address,
            onValueChange = { address = it },
            label = "Address to watch",
            placeholder = "bc1…",
            monospace = true,
            supportingText = "Watch-only: shows incoming coins, cannot spend them.",
        )
        Spacer(Modifier.height(8.dp))
        CyberButton(
            text = "Import watch-only address",
            onClick = { vm.importWatchAddress(address, label); address = "" },
            style = CyberButtonStyle.SECONDARY,
            enabled = !state.busy && address.isNotBlank(),
            fillWidth = true,
        )

        Spacer(Modifier.height(16.dp))
        CyberButton(
            text = "Import dumpwallet file",
            onClick = { dumpPicker.launch(arrayOf("*/*")) },
            style = CyberButtonStyle.GHOST,
            enabled = !state.busy && state.isLegacy,
            fillWidth = true,
        )
    }
}

/* ── sign / verify ──────────────────────────────────────────────────────── */

@Composable
private fun MessagePanel(state: WalletToolsState, vm: WalletToolsViewModel) {
    var address by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf("") }

    // Auto-fill the editable field with a freshly produced signature (for the
    // sign-then-verify roundtrip), without ever freezing it.
    LaunchedEffect(state.signature) {
        state.signature?.let { signature = it }
    }

    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        HudSectionHeader("Sign and verify messages")
        Spacer(Modifier.height(10.dp))
        Text(
            "Proves you control an address without spending from it.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Only works with legacy addresses starting with 1. Core refuses to sign " +
                "messages with SegWit (bc1q) or Taproot (bc1p) addresses through this " +
                "call — that is a limitation of the message-signing standard, not a bug " +
                "here.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.Amber,
        )

        Spacer(Modifier.height(14.dp))
        CyberTextField(
            value = address, onValueChange = { address = it },
            label = "Address", placeholder = "1...", monospace = true,
        )
        Spacer(Modifier.height(10.dp))
        CyberTextField(
            value = message, onValueChange = { message = it },
            label = "Message", singleLine = false,
        )
        Spacer(Modifier.height(10.dp))
        // The signature field is always the local value — one source of truth.
        // It used to freeze after signing (state.signature shadowed edits), so
        // a pasted third-party signature could never be verified.
        CyberTextField(
            value = signature,
            onValueChange = { signature = it },
            label = "Signature", monospace = true, singleLine = false,
        )

        // What the last Sign produced, read-only.
        state.signature?.let { sig ->
            Spacer(Modifier.height(8.dp))
            Text("Last signature produced:", style = CyberType.HudLabel,
                color = CyberColors.TextTertiary)
            Spacer(Modifier.height(3.dp))
            Text(sig, style = CyberType.Terminal, color = CyberColors.Green)
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CyberButton(
                text = "Sign",
                onClick = { vm.signMessage(address.trim(), message) },
                enabled = !state.busy && address.isNotBlank() && message.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
            CyberButton(
                text = "Verify",
                onClick = {
                    vm.verifyMessage(address.trim(), signature.trim(), message)
                },
                style = CyberButtonStyle.SECONDARY,
                enabled = !state.busy && address.isNotBlank() && signature.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        state.verifyResult?.let { ok ->
            Spacer(Modifier.height(10.dp))
            StatusChip(
                if (ok) "Signature valid" else "Signature does NOT match",
                if (ok) CyberColors.Green else CyberColors.Red,
            )
        }
    }
}

/* ── maintenance ────────────────────────────────────────────────────────── */

@Composable
private fun MaintenancePanel(state: WalletToolsState, vm: WalletToolsViewModel) {
    CyberPanel(Modifier.fillMaxWidth(), glow = false) {
        HudSectionHeader("Maintenance")
        Spacer(Modifier.height(10.dp))

        Text(
            "Rescan re-reads the chain looking for transactions belonging to this " +
                "wallet. Needed after importing descriptors, and it can take a long time.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "On a pruned node it can only scan as far back as the blocks still on " +
                "disk. Anything older than the prune horizon cannot be recovered without " +
                "a full resync.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberColors.Amber,
        )
        Spacer(Modifier.height(12.dp))
        CyberButton(
            text = "Rescan blockchain",
            onClick = vm::rescan,
            style = CyberButtonStyle.SECONDARY,
            enabled = !state.busy,
            fillWidth = true,
        )

        Spacer(Modifier.height(16.dp))
        DataRow("Transactions", value = state.txCount?.toString() ?: "—")
        DataRow("Keypool", value = state.keypoolSize?.toString() ?: "—")
        DataRow(
            "Encryption",
            valueColor = if (state.isEncrypted) CyberColors.Green else CyberColors.Red,
            value = if (state.isEncrypted) "passphrase set" else "none",
        )
    }
}
