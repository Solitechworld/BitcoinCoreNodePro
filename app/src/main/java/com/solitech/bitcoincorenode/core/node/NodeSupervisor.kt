package com.solitech.bitcoincorenode.core.node

import android.content.Context
import android.util.Log
import com.solitech.bitcoincorenode.core.rpc.CookieAuthProvider
import com.solitech.bitcoincorenode.core.rpc.HttpRpcClient
import com.solitech.bitcoincorenode.core.rpc.RpcClient
import com.solitech.bitcoincorenode.core.rpc.RpcEndpoint
import com.solitech.bitcoincorenode.core.rpc.RpcError
import com.solitech.bitcoincorenode.core.rpc.awaitWarmup
import com.solitech.bitcoincorenode.core.rpc.params
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the `bitcoind` child process: spawn, supervise, shut down cleanly.
 *
 * ## The shutdown contract
 *
 * This is the most important thing in the file, so it goes first.
 *
 * `bitcoind` must be stopped by calling its `stop` RPC and then *waiting* for
 * the process to exit on its own. It flushes the UTXO cache — potentially
 * hundreds of megabytes of dirty state — during that window. `Process.destroy()`
 * sends SIGTERM, which Core does handle, but `destroyForcibly()` sends SIGKILL,
 * which it cannot, and a SIGKILL mid-flush leaves a corrupt chainstate and a
 * user facing a multi-day resync.
 *
 * So: RPC stop, wait generously, SIGTERM only as a fallback, SIGKILL only after
 * that has also failed. And the foreground service exists precisely so Android
 * does not SIGKILL us behind our own back.
 */
class NodeSupervisor(
    private val context: Context,
    private val paths: NodePaths,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private val _state = MutableStateFlow<NodeState>(NodeState.Stopped)
    val state: StateFlow<NodeState> = _state.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var tailer: DebugLogTailer? = null
    @Volatile private var currentConfig: NodeConfig? = null

    /** RPC client for the embedded node. Valid only while it is running. */
    @Volatile private var _rpc: RpcClient? = null
    val rpc: RpcClient? get() = _rpc

    // -----------------------------------------------------------------------
    // Start
    // -----------------------------------------------------------------------

    /**
     * Publishes a start failure that happened outside `start()` itself.
     *
     * Without this the failure had nowhere to go: the exception propagated out
     * of the service's coroutine, reached the default handler, and killed the
     * process. The user saw the app vanish and learned nothing. Now the same
     * failure lands in the state machine, where the Node screen already knows
     * how to render a [NodeState.Crashed] with its cause and log tail.
     */
    fun reportStartFailure(error: Throwable) {
        val detail = listOfNotNull(
            error.javaClass.simpleName + ": " + (error.message ?: "no message"),
            error.cause?.let { "caused by ${it.javaClass.simpleName}: ${it.message}" },
        )
        _state.value = NodeState.Crashed(
            exitCode = -1,
            logTail = _logLines.value.takeLast(200) + detail,
            likelyCause = CrashCause.infer(-1, detail),
        )
    }

    suspend fun start(config: NodeConfig): Result<Unit> = stateMutex.withLock {
        if (process?.isAlive == true) return Result.success(Unit)

        try {
            _state.value = NodeState.Starting(StartupStage.VERIFYING_BINARY)
            val binary = resolveBinary()
                ?: return Result.failure(
                    IllegalStateException(
                        "libbitcoind.so is not in this build's native library directory. " +
                            "Run native/scripts/build-all.sh and rebuild the APK."
                    )
                )

            _state.value = NodeState.Starting(StartupStage.PREPARING_DATADIR)
            val dataDir = paths.dataDir(config.network).apply { mkdirs() }
            if (!dataDir.isDirectory) {
                return Result.failure(IllegalStateException("Cannot create data directory"))
            }

            _state.value = NodeState.Starting(StartupStage.WRITING_CONFIG)
            config.writeTo(dataDir)
            currentConfig = config

            // Wipe any stale cookie: if the last run died hard, the file on disk
            // is a credential for a node that no longer exists, and using it
            // produces a confusing 401 instead of a clean "not started yet".
            paths.cookieFile(config.network).delete()

            _state.value = NodeState.Starting(StartupStage.SPAWNING)
            val proc = spawn(binary, dataDir, config)
            process = proc

            startLogTailer(dataDir)
            watchForExit(proc)

            _rpc = HttpRpcClient(
                endpoint = RpcEndpoint.Embedded(
                    port = config.rpcPort,
                    cookiePath = paths.cookieFile(config.network).absolutePath,
                ),
                httpClient = loopbackHttpClient(),
                authProvider = CookieAuthProvider(paths.cookieFile(config.network)),
            )

            _state.value = NodeState.Starting(StartupStage.AWAITING_RPC)

            // The app-level mirror of Core's own init step: bitcoind loads the
            // wallets recorded in settings.json while it boots (LoadWallets in
            // src/wallet/load.cpp). This coroutine guarantees the same outcome
            // even when that record is missing or incomplete — every wallet in
            // the node's wallet directory is loaded as soon as the RPC server
            // answers, before the UI starts drawing conclusions from empty
            // lists. Without it, a wallet that Core did not auto-load reads as
            // "deleted" on the wallet screen and its balance as permanently 0.
            scope.launch { ensureWalletsLoaded() }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            _state.value = NodeState.Crashed(-1, _logLines.value.takeLast(200), CrashCause.UNKNOWN)
            Result.failure(e)
        }
    }

    /**
     * Locates the node binary.
     *
     * `nativeLibraryDir` is the one directory an app may exec from on modern
     * Android — see the comment block in native/scripts/30-package-jnilibs.sh
     * for the full explanation of why an executable is named `.so`.
     */
    private fun resolveBinary(): File? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val f = File(dir, BITCOIND_SO)
        if (!f.exists()) {
            Log.e(TAG, "missing $BITCOIND_SO in $dir; contents=${File(dir).list()?.toList()}")
            return null
        }
        if (!f.canExecute()) {
            // Should never happen — the installer sets this — but a clear
            // failure here beats an opaque IOException from ProcessBuilder.
            Log.e(TAG, "$BITCOIND_SO exists but is not executable")
            @Suppress("SetWorldReadable")
            f.setExecutable(true, true)
            if (!f.canExecute()) return null
        }
        return f
    }

    private fun spawn(binary: File, dataDir: File, config: NodeConfig): Process {
        val args = buildList {
            add(binary.absolutePath)
            add("-datadir=${dataDir.absolutePath}")
            add("-conf=${File(dataDir, "bitcoin.conf").absolutePath}")
            // Never daemonize. We need to be the parent so we can observe the
            // exit code; a self-daemonizing child is a process we have lost.
            add("-daemon=0")
            addAll(config.extraArgs.filter { it.startsWith("-") })
        }
        Log.i(TAG, "spawning: ${args.joinToString(" ")}")

        return ProcessBuilder(args)
            .directory(dataDir)
            .redirectErrorStream(true)
            // stdout is near-silent (printtoconsole=0); real logging goes to
            // debug.log, which the tailer reads. Draining stdout to a file
            // rather than a pipe means nothing blocks if nobody reads it —
            // a full pipe buffer would hang the node.
            .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.stdoutFile()))
            .apply {
                environment()["HOME"] = dataDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()
    }

    private fun startLogTailer(dataDir: File) {
        tailer?.stop()
        val t = DebugLogTailer(
            logFile = File(dataDir, "debug.log"),
            scope = scope,
            onLine = { line ->
                _logLines.update { (it + line).takeLast(MAX_LOG_LINES) }
                interpretLogLine(line)
            },
        )
        t.start()
        tailer = t
    }

    /**
     * Turns debug.log lines into UI state during the window before RPC is up.
     *
     * This is what makes the first twenty seconds of a cold start feel alive.
     * Without it the user stares at a spinner while Core loads a multi-gigabyte
     * block index, and there is no way for them to tell that from a hang.
     */
    private fun interpretLogLine(line: String) {
        val current = _state.value
        if (current !is NodeState.Starting && current !is NodeState.Loading) return
        when {
            "Loading block index" in line ->
                _state.value = NodeState.Loading("Loading block index")
            "Verifying blocks" in line ->
                _state.value = NodeState.Loading("Verifying recent blocks")
            "Loading wallet" in line ->
                _state.value = NodeState.Loading("Loading wallets")
            "Rewinding blocks" in line ->
                _state.value = NodeState.Loading("Rewinding blocks")
            "Reindexing" in line || "Reindexing block file" in line ->
                _state.value = NodeState.Reindexing("Rebuilding the block index", null)
            "init message:" in line -> {
                val msg = line.substringAfter("init message:").trim()
                if (msg.isNotBlank()) _state.value = NodeState.Loading(msg)
            }
            "Done loading" in line ->
                _state.value = NodeState.Loading("Connecting to peers")
        }
    }

    /**
     * Loads every wallet found in the node's wallet directory.
     *
     * Core does this itself during init (LoadWallets, src/wallet/load.cpp)
     * from the `wallet` list in settings.json — wallets created through
     * `createwallet`/`loadwallet` with `load_on_startup=true` are remembered
     * there. This is the safety net for everything else: a wallet created by
     * an older app build, a settings.json that didn't get written, or a
     * wallet staged on disk by the import flow. It runs as soon as the RPC
     * server answers — the same phase of the node's life Core loads wallets
     * in — so the wallet is present and scanning before block download
     * resumes. A wallet that loads late, mid-sync, misses the blocks that
     * already passed while it was absent, and its balance reads zero
     * forever.
     */
    private suspend fun ensureWalletsLoaded() {
        val rpc = _rpc ?: return

        // The RPC server answers -28 (warming up) for as long as the block
        // index load takes — minutes with a large pruned datadir on phone
        // storage. Wait it out; nothing here is urgent beyond being done
        // before the UI settles.
        runCatching {
            awaitWarmup(attempts = 360, intervalMs = 1_000) { rpc.call("uptime") }
        }.getOrElse {
            Log.w(TAG, "wallet autoload skipped: RPC never became ready")
            return
        }

        runCatching {
            val loaded = rpc.call("listwallets").jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .toSet()
            val onDisk = rpc.call("listwalletdir").jsonObject["wallets"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()

            (onDisk - loaded).forEach { name ->
                // load_on_startup=true writes the name into settings.json, so
                // Core itself takes over auto-loading from the next start.
                runCatching {
                    rpc.call("loadwallet", params { add(name); add(true) },
                        timeout = RpcClient.NO_TIMEOUT)
                }.onFailure {
                    Log.w(TAG, "autoload: could not load wallet \"$name\": $it")
                }.onSuccess {
                    Log.i(TAG, "autoload: loaded wallet \"$name\"")
                }
            }
        }.onFailure {
            Log.w(TAG, "wallet autoload failed: $it")
        }
    }

    private fun watchForExit(proc: Process) {        scope.launch {
            val exit = withContext(Dispatchers.IO) { proc.waitFor() }
            if (_state.value is NodeState.Stopping || _state.value is NodeState.Stopped) {
                _state.value = NodeState.Stopped
            } else {
                val tail = _logLines.value.takeLast(200)
                Log.e(TAG, "node exited unexpectedly: code=$exit")
                _state.value = NodeState.Crashed(exit, tail, CrashCause.infer(exit, tail))
            }
            _rpc = null
            tailer?.stop()
        }
    }

    // -----------------------------------------------------------------------
    // Stop
    // -----------------------------------------------------------------------

    /**
     * Stops the node cleanly. Read the class-level shutdown contract first.
     *
     * @param gracePeriodSeconds how long to let Core flush before escalating.
     *   The default is generous on purpose: flushing a large dirty UTXO cache
     *   on slow phone storage genuinely can take most of a minute, and being
     *   impatient here is how data directories get corrupted.
     */
    suspend fun stop(gracePeriodSeconds: Long = 90): Result<Unit> = stateMutex.withLock {
        val proc = process ?: return Result.success(Unit)
        if (!proc.isAlive) {
            _state.value = NodeState.Stopped
            return Result.success(Unit)
        }

        _state.value = NodeState.Stopping

        // 1. Ask nicely. This is the only path that guarantees a clean flush.
        val rpcStopped = try {
            _rpc?.call("stop", params { })
            true
        } catch (e: RpcError) {
            Log.w(TAG, "stop RPC failed (${e.message}); falling back to signals")
            false
        }

        // 2. Wait for it to actually finish. Core writes "Shutdown: done" last.
        val exited = withTimeoutOrNull(gracePeriodSeconds * 1000) {
            withContext(Dispatchers.IO) {
                while (proc.isAlive) delay(250)
                true
            }
        } ?: false

        if (!exited) {
            Log.w(TAG, "node did not exit after ${gracePeriodSeconds}s; sending SIGTERM")
            // 3. SIGTERM. Core installs a handler and shuts down properly.
            proc.destroy()
            val exitedAfterTerm = withTimeoutOrNull(30_000) {
                withContext(Dispatchers.IO) {
                    while (proc.isAlive) delay(250)
                    true
                }
            } ?: false

            if (!exitedAfterTerm) {
                // 4. Last resort. This risks chainstate corruption and we say so.
                Log.e(TAG, "SIGTERM ignored; SIGKILL — chainstate may need reindexing")
                proc.destroyForcibly()
            }
        }

        tailer?.stop()
        process = null
        _rpc = null
        _state.value = NodeState.Stopped
        Result.success(Unit).also {
            if (!rpcStopped && !exited) {
                Log.w(TAG, "unclean shutdown; next start may trigger a consistency check")
            }
        }
    }

    suspend fun shutdown() {
        stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * HTTP client for loopback.
     *
     * No proxy (even if the device has one configured system-wide — routing
     * 127.0.0.1 through a proxy would be both broken and a privacy leak), no
     * retries on connection failure (the caller's warmup loop handles that and
     * understands the difference between "not up yet" and "died"), and a
     * single connection kept alive.
     */
    private fun loopbackHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(2, 5, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val TAG = "NodeSupervisor"
        const val BITCOIND_SO = "libbitcoind.so"
        const val BITCOIN_CLI_SO = "libbitcoincli.so"
        private const val MAX_LOG_LINES = 2000
    }
}

/** Where everything lives on disk. One place, so nothing guesses a path. */
class NodePaths(private val context: Context) {

    /**
     * The datadir sits in `filesDir`, not `getExternalFilesDir`.
     *
     * External storage is world-readable by anything with the right permission
     * on older devices and survives uninstall. A wallet.dat does not belong
     * there under any circumstances.
     */
    private val root: File get() = File(context.filesDir, "bitcoin").apply { mkdirs() }

    fun dataDir(network: com.solitech.bitcoincorenode.core.model.BitcoinNetwork): File =
        File(root, network.chainName)

    /**
     * Core writes the cookie into a chain-specific subdirectory for every
     * network except mainnet, which uses the datadir root. Getting this wrong
     * produces a permanent 401 that looks like a credentials bug.
     */
    fun cookieFile(network: com.solitech.bitcoincorenode.core.model.BitcoinNetwork): File {
        val dd = dataDir(network)
        return when (network) {
            com.solitech.bitcoincorenode.core.model.BitcoinNetwork.MAIN -> File(dd, ".cookie")
            com.solitech.bitcoincorenode.core.model.BitcoinNetwork.TEST -> File(dd, "testnet3/.cookie")
            com.solitech.bitcoincorenode.core.model.BitcoinNetwork.TEST4 -> File(dd, "testnet4/.cookie")
            com.solitech.bitcoincorenode.core.model.BitcoinNetwork.SIGNET -> File(dd, "signet/.cookie")
            com.solitech.bitcoincorenode.core.model.BitcoinNetwork.REGTEST -> File(dd, "regtest/.cookie")
        }
    }

    fun stdoutFile(): File = File(context.cacheDir, "bitcoind-stdout.log")

    fun exportDir(): File = File(context.filesDir, "exports").apply { mkdirs() }

    fun snapshotDir(): File = File(context.cacheDir, "snapshots").apply { mkdirs() }
}
