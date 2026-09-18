package com.solitech.bitcoincorenode.ui.screens.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.model.DisplayUnit
import com.solitech.bitcoincorenode.core.model.FeeEstimate
import com.solitech.bitcoincorenode.core.model.PsbtAnalysis
import com.solitech.bitcoincorenode.core.model.Sats
import com.solitech.bitcoincorenode.core.model.Utxo
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.core.rpc.RpcProvider
import com.solitech.bitcoincorenode.core.rpc.params
import com.solitech.bitcoincorenode.core.util.Bip21
import com.solitech.bitcoincorenode.data.repo.ChainRepository
import com.solitech.bitcoincorenode.data.repo.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * The send flow, as an explicit state machine.
 *
 * Composing → Reviewing → Signing → Broadcasting → Sent
 *
 * The Reviewing step is not skippable and that is the whole design. Core's
 * `sendtoaddress` would do all of this in one call, but then the user's first
 * sight of the real fee, the change output and the chosen inputs is *after* the
 * money has left. Here they see exactly what will be signed while it is still
 * only a PSBT.
 */
enum class SendStage { COMPOSING, REVIEWING, SIGNING, BROADCASTING, SENT }

data class SendState(
    val stage: SendStage = SendStage.COMPOSING,
    val address: String = "",
    val addressValid: Boolean? = null,
    val addressError: String? = null,
    val amountInput: String = "",
    val amount: Sats? = null,
    val sendMax: Boolean = false,
    val unit: DisplayUnit = DisplayUnit.BTC,
    val feeEstimates: Map<Int, FeeEstimate> = emptyMap(),
    val feeRateSatPerVb: Double = 0.0,
    val feeRateManual: Boolean = false,
    val minRelaySatPerVb: Double = 1.0,
    val utxos: List<Utxo> = emptyList(),
    val selectedOutpoints: Set<String> = emptySet(),
    val coinControlOpen: Boolean = false,
    val spendable: Sats = Sats.ZERO,
    // review
    val psbt: String? = null,
    val analysis: PsbtAnalysis? = null,
    val fee: Sats? = null,
    val vsize: Long? = null,
    val changePosition: Int = -1,
    // outcome
    val needsPassphrase: Boolean = false,
    val broadcastTxid: String? = null,
    val error: String? = null,
) {
    val selectedTotal: Sats
        get() = utxos.filter { it.outpoint in selectedOutpoints }
            .fold(Sats.ZERO) { acc, u -> acc + u.amount }

    val canBuild: Boolean
        get() = addressValid == true &&
            (sendMax || (amount != null && !amount.isZero)) &&
            feeRateSatPerVb > 0
}

@HiltViewModel
class SendViewModel @Inject constructor(
    private val walletRepo: WalletRepository,
    private val chainRepo: ChainRepository,
    private val rpcProvider: RpcProvider,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state.asStateFlow()

    private var wallet: String = ""

    fun bind(walletName: String, prefillUri: String?) {
        if (wallet == walletName) return
        wallet = walletName

        viewModelScope.launch {
            settings.displayUnit.collectLatest { u -> _state.update { it.copy(unit = u) } }
        }
        viewModelScope.launch { loadContext() }

        prefillUri?.let { uri ->
            Bip21.parse(uri)?.let { parsed ->
                onAddressChanged(parsed.address)
                parsed.amount?.let { onAmountChanged(it.toBtcStringTrimmed()) }
            }
        }
    }

    private suspend fun loadContext() {
        runCatching {
            val estimates = chainRepo.feeEstimates()
            val snapshot = chainRepo.snapshot()
            val coins = walletRepo.utxos(wallet, minConf = 0)
            val balances = walletRepo.balances(wallet)
            _state.update {
                it.copy(
                    feeEstimates = estimates,
                    // Default to the 3-block target rather than 1. Next-block
                    // fees are frequently several times the 30-minute rate, and
                    // silently defaulting a mobile user to the most expensive
                    // option is not a neutral choice.
                    feeRateSatPerVb = if (it.feeRateManual) it.feeRateSatPerVb
                    else estimates[3]?.satPerVb ?: estimates[1]?.satPerVb ?: 2.0,
                    minRelaySatPerVb = snapshot.mempool?.minRelaySatPerVb ?: 1.0,
                    utxos = coins,
                    spendable = balances.spendable,
                )
            }
        }.onFailure { e ->
            _state.update { it.copy(error = (e as? RpcError)?.userMessage() ?: e.message) }
        }
    }

    // -- composing -----------------------------------------------------------

    fun onAddressChanged(value: String) {
        val trimmed = value.trim()
        _state.update { it.copy(address = trimmed, addressValid = null, addressError = null) }
        if (trimmed.length < 14) return

        viewModelScope.launch {
            // Validation goes through the node, not a bech32 implementation in
            // this app. Core already has a reviewed, fuzzed decoder that knows
            // about bech32m, the current network, and every address type it
            // supports. Reimplementing that here would add a novel way to
            // accept an address Core would reject -- or worse, the reverse.
            runCatching {
                val rpc = rpcProvider.active.value ?: return@runCatching null
                rpc.call("validateaddress", params { add(trimmed) }).jsonObject
            }.onSuccess { obj ->
                val valid = obj?.get("isvalid")?.jsonPrimitive?.content == "true"
                _state.update {
                    it.copy(
                        addressValid = valid,
                        addressError = if (valid) null else
                            "Not a valid address for this network. Check you haven't pasted a " +
                                "testnet address into a mainnet wallet, or vice versa.",
                    )
                }
            }
        }
    }

    fun onAmountChanged(value: String) {
        val parsed = when (_state.value.unit) {
            DisplayUnit.SATS -> Sats.parseSats(value)
            else -> Sats.parseBtc(value)
        }
        _state.update { it.copy(amountInput = value, amount = parsed, sendMax = false) }
    }

    fun toggleSendMax() {
        _state.update { it.copy(sendMax = !it.sendMax, amountInput = "", amount = null) }
    }

    fun setFeeRate(rate: Double, manual: Boolean = true) {
        _state.update { it.copy(feeRateSatPerVb = rate, feeRateManual = manual) }
    }

    fun toggleCoinControl() {
        _state.update { it.copy(coinControlOpen = !it.coinControlOpen) }
    }

    fun toggleUtxo(outpoint: String) {
        _state.update {
            val next = it.selectedOutpoints.toMutableSet()
            if (!next.add(outpoint)) next.remove(outpoint)
            it.copy(selectedOutpoints = next)
        }
    }

    // -- build ---------------------------------------------------------------

    fun build() {
        val s = _state.value
        if (!s.canBuild) return

        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            try {
                val amount = if (s.sendMax) s.selectedTotal.takeIf { !it.isZero } ?: s.spendable
                else s.amount!!

                val funded = walletRepo.buildTransaction(
                    wallet = wallet,
                    recipients = listOf(s.address to amount),
                    feeRateSatPerVb = s.feeRateSatPerVb,
                    selectedInputs = s.selectedOutpoints.map { op ->
                        val (txid, vout) = op.split(":")
                        txid to vout.toInt()
                    },
                    // "Send max" means the fee comes out of the recipient's
                    // amount. Without this the wallet tries to fund
                    // (everything + fee) and fails with insufficient funds --
                    // which is a confusing thing to be told when you asked to
                    // send everything.
                    subtractFeeFrom = if (s.sendMax) listOf(0) else emptyList(),
                    replaceable = true,
                )

                val analysis = runCatching { walletRepo.analyzePsbt(funded.psbt) }.getOrNull()

                _state.update {
                    it.copy(
                        stage = SendStage.REVIEWING,
                        psbt = funded.psbt,
                        fee = funded.fee,
                        changePosition = funded.changepos,
                        analysis = analysis,
                        vsize = analysis?.estimatedVsize,
                    )
                }
            } catch (e: RpcError) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    fun backToComposing() {
        _state.update {
            it.copy(stage = SendStage.COMPOSING, psbt = null, analysis = null, fee = null, error = null)
        }
    }

    // -- sign and broadcast --------------------------------------------------

    fun confirmAndSend(passphrase: String? = null) {
        val psbt = _state.value.psbt ?: return

        viewModelScope.launch {
            _state.update { it.copy(stage = SendStage.SIGNING, error = null, needsPassphrase = false) }
            try {
                if (passphrase != null) {
                    // 60 seconds is enough to sign one transaction. See the note
                    // on WalletRepository.unlock about why it is not longer.
                    walletRepo.unlock(wallet, passphrase, seconds = 60)
                }

                val signed = walletRepo.signPsbt(wallet, psbt)
                if (!signed.complete) {
                    _state.update {
                        it.copy(
                            stage = SendStage.REVIEWING,
                            error = "This wallet could not fully sign the transaction. If it is " +
                                "watch-only or part of a multisig, export the PSBT and sign it " +
                                "on the device that holds the keys.",
                        )
                    }
                    return@launch
                }

                val finalized = walletRepo.finalizePsbt(signed.psbt)
                val hex = finalized.hex
                if (!finalized.complete || hex == null) {
                    _state.update {
                        it.copy(stage = SendStage.REVIEWING, error = "The transaction could not be finalized.")
                    }
                    return@launch
                }

                _state.update { it.copy(stage = SendStage.BROADCASTING) }
                val txid = walletRepo.broadcast(hex)
                _state.update { it.copy(stage = SendStage.SENT, broadcastTxid = txid) }

                // Lock again immediately. The 60-second window exists to sign,
                // not to leave the wallet open afterwards.
                runCatching { walletRepo.lock(wallet) }
            } catch (e: RpcError.Rpc) {
                when (e.kind) {
                    RpcError.Rpc.Kind.WALLET_LOCKED ->
                        _state.update {
                            it.copy(stage = SendStage.REVIEWING, needsPassphrase = true, error = null)
                        }
                    RpcError.Rpc.Kind.WALLET_PASSPHRASE_BAD ->
                        _state.update {
                            it.copy(
                                stage = SendStage.REVIEWING,
                                needsPassphrase = true,
                                error = e.userMessage(),
                            )
                        }
                    else ->
                        _state.update { it.copy(stage = SendStage.REVIEWING, error = e.userMessage()) }
                }
            } catch (e: RpcError) {
                _state.update { it.copy(stage = SendStage.REVIEWING, error = e.userMessage()) }
            }
        }
    }
}
