package com.solitech.bitcoincorenode.data.explorer

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP clients for the block explorer, with and without Tor.
 *
 * Kept separate from the RPC clients in [com.solitech.bitcoincorenode.core.rpc.RpcProvider]
 * because the timeouts and the trust posture are different: an explorer is a
 * third party on the public internet, so it gets real TLS verification, tight
 * timeouts, and no retry storm when it rate-limits us.
 */
@Singleton
class DefaultExplorerHttpClientProvider @Inject constructor() : ExplorerHttpClientProvider {

    override fun client(overTor: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(if (overTor) 60 else 15, TimeUnit.SECONDS)
            .readTimeout(if (overTor) 90 else 30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // One retry at the socket layer is fine; anything more turns a
            // rate-limit into a self-inflicted ban.
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // A Blockbook that redirects us to a different host is not a
            // Blockbook we should follow: it would silently move the user's
            // address queries to an operator they never chose.
            .followSslRedirects(false)

        return if (overTor) {
            builder
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT)))
                // Same reasoning as RpcProvider.NoLocalDns: with a SOCKS proxy,
                // Java resolves the hostname locally first. For an explorer on a
                // normal domain that still leaks the lookup to the device's DNS
                // resolver -- which tells that resolver the user is running a
                // Bitcoin wallet, defeating the point of using Tor at all.
                // Handing an unresolved name to the proxy makes Tor do the
                // resolution instead.
                .dns(NoLocalDns)
                .build()
        } else {
            builder.proxy(Proxy.NO_PROXY).build()
        }
    }

    private object NoLocalDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
    }

    companion object {
        /** Orbot's default. Configurable once a bundled Tor lands. */
        const val TOR_SOCKS_PORT = 9050
    }
}
