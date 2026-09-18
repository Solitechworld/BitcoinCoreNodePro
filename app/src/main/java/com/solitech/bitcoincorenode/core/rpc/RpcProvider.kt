package com.solitech.bitcoincorenode.core.rpc

import com.solitech.bitcoincorenode.core.node.NodePaths
import com.solitech.bitcoincorenode.core.node.NodeSupervisor
import com.solitech.bitcoincorenode.core.model.BitcoinNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The hybrid switch.
 *
 * This is the one place in the app that knows whether we are talking to the
 * node in this phone or to a node somewhere else. Everything above it gets an
 * [RpcClient] and cannot tell the difference — which is the entire point of the
 * architecture, and the reason adding an `if (isEmbedded)` anywhere else should
 * be treated as a design regression rather than a small convenience.
 */
@Singleton
class RpcProvider @Inject constructor(
    private val supervisor: NodeSupervisor,
    private val paths: NodePaths,
) {
    private val _active = MutableStateFlow<RpcClient?>(null)
    val active: StateFlow<RpcClient?> = _active.asStateFlow()

    private val _mode = MutableStateFlow<NodeMode>(NodeMode.Embedded)
    val mode: StateFlow<NodeMode> = _mode.asStateFlow()

    /** Point everything at the node running on this device. */
    fun useEmbedded() {
        _mode.value = NodeMode.Embedded
        _active.value = supervisor.rpc
    }

    /** Point everything at a node elsewhere. */
    fun useRemote(endpoint: RpcEndpoint, torSocksPort: Int = 9050) {
        _mode.value = NodeMode.Remote(endpoint)
        _active.value = when (endpoint) {
            is RpcEndpoint.Embedded -> supervisor.rpc
            else -> clientFor(endpoint, torSocksPort)
        }
    }

    /**
     * Builds a client for [endpoint] WITHOUT switching the app to it — for
     * probes (the Remote screen's "Test" button). Tor endpoints get the same
     * SOCKS + no-local-DNS treatment as the real client; a naive probe would
     * leak the onion name to the system resolver and then fail anyway.
     */
    fun clientFor(endpoint: RpcEndpoint, torSocksPort: Int = 9050): HttpRpcClient = when (endpoint) {
        // The embedded endpoint is cookie-authed and local; probing it makes
        // no sense from the Remote screen, and it has no user/password.
        is RpcEndpoint.Embedded -> throw IllegalArgumentException("clientFor is for remote endpoints")
        is RpcEndpoint.Onion -> HttpRpcClient(
            endpoint = endpoint,
            httpClient = torHttpClient(torSocksPort),
            authProvider = BasicAuthProvider(endpoint.user, endpoint.password),
        )
        is RpcEndpoint.Lan -> HttpRpcClient(
            endpoint = endpoint,
            httpClient = lanHttpClient(),
            authProvider = BasicAuthProvider(endpoint.user, endpoint.password),
        )
    }

    /** Called by the supervisor when the embedded node starts or stops. */
    fun refreshEmbedded() {
        if (_mode.value is NodeMode.Embedded) _active.value = supervisor.rpc
    }

    /**
     * HTTP over Tor's SOCKS5 proxy.
     *
     * ## The `.onion` DNS trap
     *
     * `Proxy(Proxy.Type.SOCKS, ...)` alone is not enough, and getting this
     * wrong fails in the worst possible way: silently, and by leaking.
     *
     * Java resolves the hostname locally *before* handing the connection to a
     * SOCKS proxy. For a `.onion` address there is nothing to resolve — no DNS
     * server on earth knows it — so the connection fails. Worse, on the way to
     * failing, the device has just asked its configured DNS resolver to look up
     * an onion address, which tells that resolver exactly what the user was
     * trying to reach. The privacy tool leaks the thing it was protecting.
     *
     * The fix is the documented OkHttp workaround: supply a [okhttp3.Dns] that
     * does not resolve at all and instead returns a placeholder address
     * carrying the original hostname. OkHttp then passes the hostname through
     * to the SOCKS proxy, and Tor — which is the only party that can — does the
     * resolution.
     */
    private fun torHttpClient(socksPort: Int): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        .dns(NoLocalDns)
        .connectTimeout(60, TimeUnit.SECONDS)   // circuits take a while to build
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun lanHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private object NoLocalDns : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            // 0.0.0.0 with the hostname attached. Never actually dialled: OkHttp
            // sees an unresolved-looking address behind a SOCKS proxy and sends
            // the name to the proxy instead.
            listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
    }

    fun defaultPortFor(network: BitcoinNetwork): Int = network.defaultRpcPort
}

sealed interface NodeMode {
    data object Embedded : NodeMode
    data class Remote(val endpoint: RpcEndpoint) : NodeMode

    val label: String get() = when (this) {
        Embedded -> "This device"
        is Remote -> endpoint.displayName
    }
}
