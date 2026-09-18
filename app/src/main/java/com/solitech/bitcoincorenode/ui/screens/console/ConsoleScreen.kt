package com.solitech.bitcoincorenode.ui.screens.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.solitech.bitcoincorenode.core.rpc.BitcoinJson
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.core.rpc.RpcProvider
import com.solitech.bitcoincorenode.core.rpc.params
import com.solitech.bitcoincorenode.ui.components.CyberButton
import com.solitech.bitcoincorenode.ui.components.CyberTextField
import com.solitech.bitcoincorenode.ui.components.CyberTopBar
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

sealed interface ConsoleLine {
    data class Command(val text: String) : ConsoleLine
    data class Output(val text: String) : ConsoleLine
    data class Failure(val text: String) : ConsoleLine
    data class Note(val text: String) : ConsoleLine
}

@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val rpcProvider: RpcProvider,
    private val settings: com.solitech.bitcoincorenode.core.prefs.SettingsStore,
    private val walletRepo: com.solitech.bitcoincorenode.data.repo.WalletRepository,
) : ViewModel() {

    private val _lines = MutableStateFlow<List<ConsoleLine>>(
        listOf(
            ConsoleLine.Note(
                "Connected to your node's JSON-RPC interface. Commands run exactly as " +
                    "bitcoin-cli would run them. `help` lists what this node supports."
            )
        )
    )
    val lines: StateFlow<List<ConsoleLine>> = _lines.asStateFlow()

    /** Wallets available for the tray; refreshed with the screen. */
    private val _loadedWallets = MutableStateFlow<List<String>>(emptyList())
    val loadedWallets: StateFlow<List<String>> = _loadedWallets.asStateFlow()

    /**
     * The wallet commands are routed to — the console's -rpcwallet. Null means
     * node-level only. Defaults to the app's active wallet; the tray can
     * override it per session.
     */
    private val _selectedWallet = MutableStateFlow<String?>(null)
    val selectedWallet: StateFlow<String?> = _selectedWallet.asStateFlow()

    init {
        viewModelScope.launch {
            settings.activeWallet.collectLatest { a ->
                _selectedWallet.value = a
            }
        }
        viewModelScope.launch { refreshWallets() }
    }

    /** Suspends until the loaded list is fresh — callers may rely on it. */
    suspend fun refreshWallets() {
        runCatching { _loadedWallets.value = walletRepo.listLoadedWallets() }
    }

    fun selectWallet(name: String?) {
        _selectedWallet.value = name
    }

    private val history = mutableListOf<String>()
    private var historyIndex = -1

    fun run(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        history.add(trimmed)
        historyIndex = history.size
        _lines.update { it + ConsoleLine.Command(trimmed) }

        viewModelScope.launch {
            val rpc = rpcProvider.active.value
            if (rpc == null) {
                _lines.update { it + ConsoleLine.Failure("No node connected.") }
                return@launch
            }

            // Split on whitespace, honouring quotes, so a JSON argument or a
            // label with a space survives. bitcoin-cli does the same thing and
            // people paste bitcoin-cli commands straight in here.
            val parts = tokenize(trimmed)
            val method = parts.first()
            val args = parts.drop(1)

            // Route through the selected wallet's endpoint, like
            // `bitcoin-cli -rpcwallet=<name>`. Node-level commands work there
            // too, and wallet commands without it fail with -18, which is the
            // single most confusing error a multi-wallet console can produce.
            // The loaded-wallet list can go stale (node just restarted, wallets
            // still loading) — refresh it, and if the selection still isn't
            // loaded SAY so instead of silently dropping to the node endpoint,
            // which turns every wallet command into -32601/-19.
            val wallet = run {
                refreshWallets()
                val sel = _selectedWallet.value
                if (sel != null && sel !in _loadedWallets.value) {
                    _lines.update {
                        it + ConsoleLine.Note(
                            "Wallet \"$sel\" is not in the node's loaded list — running " +
                                "node-level. Load it from the Wallet tab or pick another " +
                                "in the tray above."
                        )
                    }
                    null
                } else {
                    sel
                }
            }

            try {
                val result = rpc.call(
                    method = method,
                    params = JsonArray(args.map { coerce(it) }),
                    wallet = wallet,
                    // Long-running RPCs (rescans, snapshots) keep no timeout;
                    // everything else gets the normal one so a hung command
                    // fails visibly instead of freezing the console forever.
                    timeout = if (method in com.solitech.bitcoincorenode.core.rpc.RpcClient.LONG_RUNNING)
                        com.solitech.bitcoincorenode.core.rpc.RpcClient.NO_TIMEOUT
                    else com.solitech.bitcoincorenode.core.rpc.RpcClient.DEFAULT_TIMEOUT,
                )
                val pretty = prettyJson.encodeToString(
                    kotlinx.serialization.json.JsonElement.serializer(), result
                )
                _lines.update { it + ConsoleLine.Output(pretty) }
            } catch (e: RpcError.Rpc) {
                _lines.update {
                    it + ConsoleLine.Failure("error code: ${e.code}\n${e.rpcMessage}")
                }
            } catch (e: RpcError) {
                _lines.update { it + ConsoleLine.Failure(e.userMessage()) }
            }
        }
    }

    fun previous(): String? {
        if (history.isEmpty()) return null
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        return history.getOrNull(historyIndex)
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

    /**
     * Best-effort typing of a bare console argument.
     *
     * Core's RPC is strict: `getblock <hash> 2` wants a number for the second
     * argument and rejects the string "2". Sending everything as a string makes
     * half the useful commands fail with an unhelpful -3, so numbers, booleans
     * and JSON literals are recognised and anything else stays a string.
     */
    private fun coerce(token: String): kotlinx.serialization.json.JsonElement = when {
        token == "true" -> JsonPrimitive(true)
        token == "false" -> JsonPrimitive(false)
        token == "null" -> kotlinx.serialization.json.JsonNull
        token.toLongOrNull() != null -> JsonPrimitive(token.toLong())
        token.toDoubleOrNull() != null && token.contains('.') -> JsonPrimitive(token.toDouble())
        token.startsWith("{") || token.startsWith("[") ->
            runCatching { BitcoinJson.parseToJsonElement(token) }.getOrElse { JsonPrimitive(token) }
        else -> JsonPrimitive(token)
    }

    private fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var depth = 0
        for (c in input) {
            when {
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                c == '{' || c == '[' -> { depth++; sb.append(c) }
                c == '}' || c == ']' -> { depth--; sb.append(c) }
                c.isWhitespace() && !inQuotes && depth == 0 -> {
                    if (sb.isNotEmpty()) { out.add(sb.toString().trim('"')); sb.clear() }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString().trim('"'))
        return out
    }
}

@Composable
fun ConsoleScreen(
    onBack: () -> Unit,
    viewModel: ConsoleViewModel = hiltViewModel(),
) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val selected by viewModel.selectedWallet.collectAsStateWithLifecycle()
    val wallets by viewModel.loadedWallets.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        CyberTopBar(
            title = "RPC Console",
            onBack = onBack,
            trailing = {
                Text(
                    "CLEAR",
                    style = CyberType.HudLabel,
                    color = CyberColors.TextTertiary,
                    modifier = Modifier.clickable { viewModel.clear() },
                )
            },
        )

        // Wallet tray: which loaded wallet wallet-scoped commands hit, the
        // console equivalent of `bitcoin-cli -rpcwallet=<name>`. "Node" runs
        // against the node-level endpoint instead.
        LaunchedEffect(Unit) { viewModel.refreshWallets() }
        var trayOpen by remember { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .background(CyberColors.Surface)
                .clickable { trayOpen = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                "WALLET",
                style = CyberType.HudLabel,
                color = CyberColors.TextTertiary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                selected ?: "Node (no wallet)",
                style = CyberType.Terminal,
                color = if (selected != null) CyberColors.Cyan else CyberColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text("▾", style = CyberType.HudLabel, color = CyberColors.Cyan)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = trayOpen,
            onDismissRequest = { trayOpen = false },
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        "Node (no wallet)",
                        style = CyberType.Terminal,
                        color = if (selected == null) CyberColors.Cyan else CyberColors.TextPrimary,
                    )
                },
                onClick = {
                    trayOpen = false
                    viewModel.selectWallet(null)
                },
            )
            wallets.forEach { name ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            style = CyberType.Terminal,
                            color = if (name == selected) CyberColors.Cyan else CyberColors.TextPrimary,
                        )
                    },
                    trailingIcon = if (name == selected) {
                        { Text("●", style = CyberType.HudLabel, color = CyberColors.Cyan) }
                    } else null,
                    onClick = {
                        trayOpen = false
                        viewModel.selectWallet(name)
                    },
                )
            }
        }

        // Autocomplete: command chips matching what is typed so far. Shown only
        // while typing the first word; once args start, chips would just be noise.
        val firstWord = input.trim().split(' ').firstOrNull().orEmpty()
        if (firstWord.length >= 1 && !input.contains(' ')) {
            val matches = CONSOLE_COMMANDS.filter { it.startsWith(firstWord) }.take(6)
            if (matches.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(CyberColors.Surface)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    matches.forEach { cmd ->
                        Text(
                            cmd,
                            style = CyberType.Terminal,
                            color = CyberColors.Cyan,
                            modifier = Modifier
                                .background(CyberColors.SurfaceElevated)
                                .clickable { input = cmd }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF03050A))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(lines) { line ->
                when (line) {
                    is ConsoleLine.Command -> Row {
                        Text("> ", style = CyberType.Terminal, color = CyberColors.Cyan)
                        Text(line.text, style = CyberType.Terminal, color = CyberColors.TextPrimary)
                    }
                    is ConsoleLine.Output ->
                        Text(line.text, style = CyberType.Terminal, color = CyberColors.Green)
                    is ConsoleLine.Failure ->
                        Text(line.text, style = CyberType.Terminal, color = CyberColors.Red)
                    is ConsoleLine.Note ->
                        Text(line.text, style = CyberType.Terminal, color = CyberColors.TextTertiary)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(CyberColors.Surface)
                .navigationBarsPadding()
                .padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            CyberTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = "getblockchaininfo",
                monospace = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            CyberButton("Run", { viewModel.run(input); input = "" })
        }
    }
}

/**
 * The commands a person actually types, for autocomplete. Not the full RPC
 * surface — `help` lists that — just enough that the common ones are two taps.
 */
private val CONSOLE_COMMANDS = listOf(
    "getblockchaininfo", "getnetworkinfo", "getpeerinfo", "getmempoolinfo",
    "getblockstats", "getblockhash", "getblock", "getrawtransaction",
    "gettxoutsetinfo", "getmempoolentry", "estimatesmartfee", "getindexinfo",
    "getwalletinfo", "getbalances", "getnewaddress", "getaddressinfo",
    // Address listing in Core 28.3: getaddressesbylabel / listaddressgroupings.
    // `listaddresses` does not exist until a later Core — suggesting it here
    // would teach the user a command their node answers with -32601.
    "getaddressesbylabel", "listaddressgroupings", "listreceivedbyaddress",
    "listunspent", "listtransactions", "listsinceblock",
    "gettransaction", "sendtoaddress", "sendmany", "createrawtransaction",
    "fundrawtransaction", "signrawtransactionwithwallet", "sendrawtransaction",
    "walletcreatefundedpsbt", "walletprocesspsbt", "finalizepsbt", "analyzepsbt",
    "bumpfee", "psbtbumpfee", "abandontransaction", "rescanblockchain",
    "createwallet", "loadwallet", "unloadwallet", "listwallets", "listwalletdir",
    "backupwallet", "restorewallet", "dumpwallet", "importwallet",
    "importprivkey", "importpubkey", "importaddress", "importdescriptors",
    "listdescriptors", "deriveaddresses", "encryptwallet", "walletpassphrase",
    "walletpassphrasechange", "walletlock", "signmessage", "verifymessage",
    "setlabel", "migratewallet", "addnode", "disconnectnode", "listbanned",
    "setban", "clearbanned", "getnodeaddresses", "ping", "uptime",
    "getbestblockhash", "getblockcount", "getdifficulty", "getconnectioncount",
    "getnettotals", "getmemoryinfo", "getrpcinfo", "logging", "stop",
    "savemempool", "preciousblock", "pruneblockchain", "dumptxoutset",
    "loadtxoutset", "getchainstates", "getdeploymentinfo", "getblockfilter",
)
