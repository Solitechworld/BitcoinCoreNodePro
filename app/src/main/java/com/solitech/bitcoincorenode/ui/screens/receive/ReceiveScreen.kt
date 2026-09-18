package com.solitech.bitcoincorenode.ui.screens.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.Sats
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.core.util.Bip21
import com.solitech.bitcoincorenode.data.repo.WalletRepository
import com.solitech.bitcoincorenode.ui.components.AddressText
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberButtonStyle
import com.solitech.bitcoincorenode.ui.components.CyberPanel
import com.solitech.bitcoincorenode.ui.components.CyberTextField
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.components.DonateFooter
import com.solitech.bitcoincorenode.ui.components.ErrorPanel
import com.solitech.bitcoincorenode.ui.components.QrCode
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReceiveState(
    val address: String = "",
    val addressType: String = "bech32m",
    val label: String = "",
    val amountInput: String = "",
    val amount: Sats? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /**
     * Set when the shown address came from the wallet's own history rather
     * than fresh generation — the only option for an encrypted-and-locked
     * wallet with an empty keypool.
     */
    val fallbackNote: String? = null,
) {
    val uri: String get() = if (address.isEmpty()) "" else Bip21.build(address, amount, label)
}

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val walletRepo: WalletRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ReceiveState())
    val state: StateFlow<ReceiveState> = _state.asStateFlow()
    private var wallet = ""

    fun bind(walletName: String) {
        if (wallet == walletName) return
        wallet = walletName
        viewModelScope.launch {
            // A legacy Berkeley-DB wallet cannot produce bech32m (Taproot)
            // addresses at all — Core refuses with its own error. Defaulting
            // old wallets to P2PKH (1…) sidesteps that wall; the type picker
            // still lets the user try bech32, which legacy wallets do support.
            val legacy = runCatching { walletRepo.info(wallet) }.getOrNull()
                ?.let { it.format == "bdb" || !it.descriptors } == true
            if (legacy && _state.value.addressType == "bech32m") {
                _state.update { it.copy(addressType = "legacy") }
            }
            newAddress()
        }
    }

    fun newAddress() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, fallbackNote = null) }
            try {
                val addr = generate(_state.value.addressType)
                _state.update { it.copy(address = addr, loading = false) }
            } catch (e: RpcError) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    private suspend fun generate(type: String): String {
        return try {
            walletRepo.newAddress(wallet, _state.value.label, type)
        } catch (e: RpcError) {
            val msg = (e as? RpcError.Rpc)?.rpcMessage?.lowercase() ?: ""
            when {
                // Core's exact refusal: "Legacy wallets cannot provide
                // bech32m addresses". Retry as P2PKH rather than dead-ending.
                "bech32m" in msg ->
                    walletRepo.newAddress(wallet, _state.value.label, "legacy")
                        .also { _state.update { st -> st.copy(addressType = "legacy") } }

                // "Keypool ran out, please call keypoolrefill first" — the
                // normal state of a 2011 wallet. Refill needs the passphrase
                // when encrypted; without it, fall back to the wallet's own
                // existing addresses.
                "keypool ran out" in msg -> {
                    try {
                        walletRepo.keypoolRefill(wallet)
                        walletRepo.newAddress(wallet, _state.value.label, type)
                    } catch (refill: RpcError) {
                        throwIfNotKeypoolLock(refill)
                        usedAddressFallback()
                    }
                }

                else -> throw e
            }
        }
    }

    /** Only the locked-encrypted case falls back; anything else is real. */
    private fun throwIfNotKeypoolLock(e: RpcError) {
        val msg = (e as? RpcError.Rpc)?.rpcMessage?.lowercase() ?: ""
        val locked = e is RpcError.Rpc &&
            (e.code == -13 || "passphrase" in msg || "wallet passphrase" in msg)
        if (!locked) throw e
    }

    private suspend fun usedAddressFallback(): String {
        val used = runCatching { walletRepo.usedAddresses(wallet) }.getOrDefault(emptyList())
        val best = used.firstOrNull() ?: throw RpcError.Malformed(
            "getnewaddress", "keypool empty and no existing addresses on file",
        )
        _state.update {
            it.copy(
                fallbackNote = "This wallet is encrypted and locked, and its keypool is " +
                    "empty, so a new address cannot be generated without the wallet " +
                    "passphrase. This is an existing address from the wallet's own " +
                    "history — it receives payments normally. Unlock the wallet in " +
                    "Wallet tools to generate fresh addresses.",
            )
        }
        return best.first
    }

    fun setAddressType(type: String) {
        _state.update { it.copy(addressType = type) }
        newAddress()
    }

    fun setLabel(v: String) = _state.update { it.copy(label = v) }

    fun setAmount(v: String) =
        _state.update { it.copy(amountInput = v, amount = Sats.parseBtc(v)) }
}

@Composable
fun ReceiveScreen(
    walletName: String,
    onBack: () -> Unit,
    viewModel: ReceiveViewModel = hiltViewModel(),
) {
    LaunchedEffect(walletName) { viewModel.bind(walletName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        CyberTopBar(title = "Receive", onBack = onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { ErrorPanel("Could not get an address", it) }

            state.fallbackNote?.let {
                CyberPanel(Modifier.fillMaxWidth(), accent = CyberColors.Amber) {
                    Text(
                        "Existing address from this wallet",
                        style = CyberType.HudLabel,
                        color = CyberColors.Amber,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = CyberColors.TextSecondary)
                }
            }

            CyberPanel(Modifier.fillMaxWidth()) {
                QrCode(content = state.uri, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                AddressText(state.address)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CyberButton(
                        "Copy",
                        onClick = { copy(context, state.uri.ifEmpty { state.address }) },
                        style = CyberButtonStyle.SECONDARY,
                        modifier = Modifier.weight(1f),
                    )
                    CyberButton(
                        "New address",
                        onClick = viewModel::newAddress,
                        style = CyberButtonStyle.SECONDARY,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                Text("Request".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
                Spacer(Modifier.height(10.dp))
                CyberTextField(
                    value = state.amountInput,
                    onValueChange = viewModel::setAmount,
                    label = "Amount (optional)",
                    placeholder = "0.00000000",
                    monospace = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(12.dp))
                CyberTextField(
                    value = state.label,
                    onValueChange = viewModel::setLabel,
                    label = "Label (optional)",
                    placeholder = "who this is for",
                    supportingText = "Stored only in your wallet. It is not sent to the payer " +
                        "or written to the chain.",
                )
            }

            CyberPanel(Modifier.fillMaxWidth(), glow = false) {
                Text("Address type".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
                Spacer(Modifier.height(10.dp))
                listOf(
                    "bech32m" to "Taproot — cheapest to spend, best privacy",
                    "bech32" to "SegWit v0 — for senders that reject bc1p",
                    "p2sh-segwit" to "Nested SegWit — very old senders only",
                    "legacy" to "P2PKH (starts with 1) — oldest wallets and paper wallets only",
                ).forEach { (type, blurb) ->
                    val selected = state.addressType == type
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setAddressType(type) }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            type,
                            style = CyberType.Hash,
                            color = if (selected) CyberColors.Cyan else CyberColors.TextPrimary,
                        )
                        Text(blurb, style = CyberType.Terminal, color = CyberColors.TextTertiary)
                    }
                }
            }

            Text(
                // The single most useful piece of privacy guidance a receive
                // screen can give, and the reason "New address" is prominent.
                "Use a fresh address for every payment. Reusing one lets anyone with " +
                    "both payments link them to the same wallet, permanently and publicly.",
                style = MaterialTheme.typography.bodySmall,
                color = CyberColors.TextTertiary,
            )
            DonateFooter()
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // ClipDescription extras mark this sensitive so Android 13+ omits it from
    // the clipboard preview toast. An address in a screenshot-able toast is a
    // small leak, but a free one to avoid.
    val clip = ClipData.newPlainText("bitcoin address", text).apply {
        description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
}
