package com.solitech.bitcoincorenode.core.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every conversation this app has with Bitcoin Core goes through here.
 *
 * There is one implementation and two transports (loopback, Tor/LAN) because
 * the whole hybrid-node design rests on the embedded node and a remote node
 * being indistinguishable above this line. If you find yourself adding an
 * `if (isEmbedded)` to a screen or a repository, something has gone wrong at
 * this layer instead.
 */
interface RpcClient {

    val endpoint: RpcEndpoint

    /**
     * Raw call. [wallet] selects a loaded wallet via Core's `/wallet/<name>`
     * endpoint path; null uses the node-level endpoint.
     */
    suspend fun call(
        method: String,
        params: JsonArray = JsonArray(emptyList()),
        wallet: String? = null,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): JsonElement

    /**
     * Several calls, one round trip. Use this anywhere a screen needs more than
     * one fact at once — the dashboard alone would otherwise make five separate
     * HTTP requests every refresh.
     */
    suspend fun batch(
        calls: List<Pair<String, JsonArray>>,
        wallet: String? = null,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): List<Result<JsonElement>>

    /** Cheap liveness probe. Never throws. */
    suspend fun ping(): Boolean

    companion object {
        val DEFAULT_TIMEOUT: Duration = 30.seconds

        /**
         * Calls that legitimately take minutes to hours and must not be timed
         * out. `loadtxoutset` reads an 11 GB snapshot; `rescanblockchain` walks
         * the pruned window; `dumptxoutset` writes one. Timing any of these out
         * mid-flight leaves the node doing work whose result gets discarded.
         */
        val LONG_RUNNING: Set<String> = setOf(
            "loadtxoutset",
            "dumptxoutset",
            "rescanblockchain",
            "importdescriptors",
            "importmulti",
            "createwallet",
            "restorewallet",
            "unloadwallet",
            "gettxoutsetinfo",
        )

        val NO_TIMEOUT: Duration = Duration.INFINITE
    }
}

class HttpRpcClient(
    override val endpoint: RpcEndpoint,
    private val httpClient: OkHttpClient,
    /** Supplies auth per request — cookies rotate on every node restart. */
    private val authProvider: AuthProvider,
) : RpcClient {

    private val ids = AtomicLong(0)
    private val jsonMedia = "application/json".toMediaType()

    private fun nextId() = ids.incrementAndGet().toString()

    private fun urlFor(wallet: String?): String {
        val base = when (val e = endpoint) {
            is RpcEndpoint.Embedded -> e.url
            is RpcEndpoint.Onion -> e.url
            is RpcEndpoint.Lan -> e.url
        }
        // Core exposes per-wallet RPC at /wallet/<name>. The name has to be URL
        // encoded: wallet names are user-chosen and may contain spaces.
        // URLEncoder is form-encoding — it turns a space into '+', which in a
        // URL *path* is a literal plus sign. Core then looks for a wallet
        // called "my+wallet", the request fails with -18, and the user sees a
        // wallet that "has no balance". '+' must become %20.
        // null = node-level endpoint; "" = Core's default wallet (/wallet/),
        // which is NOT the same endpoint once more than one wallet exists.
        return if (wallet == null) base
        else base.trimEnd('/') + "/wallet/" +
            java.net.URLEncoder.encode(wallet, "UTF-8").replace("+", "%20")
    }

    override suspend fun call(
        method: String,
        params: JsonArray,
        wallet: String?,
        timeout: Duration,
    ): JsonElement = withContext(Dispatchers.IO) {
        val effective = if (method in RpcClient.LONG_RUNNING) RpcClient.NO_TIMEOUT else timeout
        val request = RpcRequest(method = method, params = params, id = nextId())
        val body = BitcoinJson.encodeToString(RpcRequest.serializer(), request)
        val raw = execute(body, wallet, method, effective)

        val response = try {
            BitcoinJson.decodeFromString(RpcResponse.serializer(), raw)
        } catch (e: Exception) {
            throw RpcError.Malformed(method, "not valid JSON-RPC", e)
        }
        response.error?.let { err ->
            // -28 is warmup, which is a phase and not a failure. Surfacing it
            // as its own type lets callers wait instead of showing red.
            if (err.code == -28) throw RpcError.Warmup(err.message)
            throw RpcError.Rpc(err.code, err.message, method)
        }
        response.result
    }

    override suspend fun batch(
        calls: List<Pair<String, JsonArray>>,
        wallet: String?,
        timeout: Duration,
    ): List<Result<JsonElement>> = withContext(Dispatchers.IO) {
        if (calls.isEmpty()) return@withContext emptyList()

        val requests = calls.map { (m, p) -> RpcRequest(method = m, params = p, id = nextId()) }
        val byId = requests.associateBy { it.id }
        val body = BitcoinJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(RpcRequest.serializer()), requests
        )
        val raw = execute(body, wallet, "batch[${calls.size}]", timeout)

        val responses = try {
            BitcoinJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(RpcResponse.serializer()), raw
            )
        } catch (e: Exception) {
            throw RpcError.Malformed("batch", "not a valid JSON-RPC batch response", e)
        }

        // A batch response may come back in any order, so match on id and
        // rebuild the caller's ordering. Getting this wrong would silently
        // attribute one call's result to a different call — the kind of bug
        // that shows a wrong balance rather than an error.
        val byIdResult = responses.associateBy { it.id }
        requests.map { req ->
            val r = byIdResult[req.id]
            val method = byId[req.id]?.method ?: "?"
            when {
                r == null -> Result.failure(RpcError.Malformed(method, "no response for id ${req.id}"))
                r.error != null -> Result.failure(
                    if (r.error.code == -28) RpcError.Warmup(r.error.message)
                    else RpcError.Rpc(r.error.code, r.error.message, method)
                )
                else -> Result.success(r.result)
            }
        }
    }

    override suspend fun ping(): Boolean = try {
        call("uptime", timeout = 5.seconds)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun execute(
        body: String,
        wallet: String?,
        methodLabel: String,
        timeout: Duration,
    ): String {
        val url = urlFor(wallet)
        val auth = authProvider.authorization()
            ?: throw RpcError.Unauthorized(endpoint.displayName)

        val client = if (timeout == Duration.INFINITE) {
            httpClient.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        } else {
            httpClient.newBuilder()
                .readTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .callTimeout(timeout.inWholeMilliseconds + 5_000, TimeUnit.MILLISECONDS)
                .build()
        }

        val req = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .header("Connection", "keep-alive")
            .post(body.toRequestBody(jsonMedia))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 -> {
                        // Cookie files are rewritten on every node restart, so a
                        // 401 usually means ours is stale rather than wrong.
                        authProvider.invalidate()
                        throw RpcError.Unauthorized(endpoint.displayName)
                    }
                    // 500 with a JSON body is how Core reports application-level
                    // RPC errors, including warmup. Let the caller parse it.
                    resp.code == 500 && text.isNotBlank() -> return text
                    resp.code == 503 -> throw RpcError.Warmup("node is loading")
                    !resp.isSuccessful && text.isBlank() ->
                        throw RpcError.Malformed(methodLabel, "HTTP ${resp.code} with no body")
                    else -> return text
                }
            }
        } catch (e: SocketTimeoutException) {
            throw RpcError.Timeout(methodLabel, timeout.inWholeSeconds)
        } catch (e: RpcError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw RpcError.NotReachable(endpoint.displayName, e)
        }
    }
}

/** Supplies the HTTP Authorization header, and can drop a cached value. */
interface AuthProvider {
    fun authorization(): String?
    fun invalidate() {}
}

/**
 * Cookie auth for the embedded node.
 *
 * bitcoind writes `<datadir>/[chain/].cookie` at startup containing
 * `__cookie__:<random>`. It is regenerated on every start, so we re-read it
 * whenever it changes on disk rather than caching it for the process lifetime.
 * That is what makes "restart the node" work without the app needing to know
 * it happened.
 */
class CookieAuthProvider(private val cookieFile: File) : AuthProvider {
    @Volatile private var cached: String? = null
    @Volatile private var cachedAt: Long = 0

    override fun authorization(): String? {
        val stamp = if (cookieFile.exists()) cookieFile.lastModified() else 0L
        val hit = cached
        if (hit != null && stamp == cachedAt) return hit
        if (!cookieFile.exists()) return null
        return try {
            val raw = cookieFile.readText().trim()
            val idx = raw.indexOf(':')
            if (idx <= 0) return null
            val value = Credentials.basic(raw.substring(0, idx), raw.substring(idx + 1))
            cached = value
            cachedAt = stamp
            value
        } catch (_: IOException) {
            null
        }
    }

    override fun invalidate() {
        cached = null
        cachedAt = 0
    }
}

/** Static user/password, for remote nodes. */
class BasicAuthProvider(user: String, password: String) : AuthProvider {
    private val header = Credentials.basic(user, password)
    override fun authorization(): String = header
}

/**
 * Retries a call while the node says it is warming up.
 *
 * Startup is the single most common source of "the app is broken" reports for
 * node software: bitcoind accepts the TCP connection and answers -28 for
 * anywhere from two seconds to several minutes while it loads the block index.
 * Treating that window as an error would make the app look broken every launch.
 */
suspend fun <T> awaitWarmup(
    attempts: Int = 120,
    intervalMs: Long = 1_000,
    onProgress: (String) -> Unit = {},
    block: suspend () -> T,
): T {
    var last: RpcError? = null
    repeat(attempts) {
        try {
            return block()
        } catch (e: RpcError.Warmup) {
            last = e
            onProgress(e.detail)
            delay(intervalMs)
        } catch (e: RpcError.NotReachable) {
            last = e
            onProgress("waiting for RPC server")
            delay(intervalMs)
        }
    }
    throw last ?: RpcError.Timeout("warmup", attempts * intervalMs / 1000)
}
