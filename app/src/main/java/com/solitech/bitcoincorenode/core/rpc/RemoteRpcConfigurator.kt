package com.solitech.bitcoincorenode.core.rpc

import com.solitech.bitcoincorenode.core.prefs.RemoteRpcConfig
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [RpcProvider] in step with the saved remote-node settings.
 *
 * Same pattern as ExplorerConfigurator: the settings flow is the single source
 * of truth, and this translates it into provider calls. That means "connect to
 * my node at home" survives an app restart without any screen doing anything.
 */
@Singleton
class RemoteRpcConfigurator @Inject constructor(
    private val settings: SettingsStore,
    private val rpcProvider: RpcProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            settings.remoteRpc.distinctUntilChanged().collect { config ->
                if (config.enabled && config.isComplete) {
                    rpcProvider.useRemote(config.toEndpoint())
                } else {
                    rpcProvider.useEmbedded()
                }
            }
        }
    }
}

private fun String.stripSchemeAndSlashes(): String =
    removePrefix("https://").removePrefix("http://").trimEnd('/')

fun RemoteRpcConfig.toEndpoint(): RpcEndpoint = if (isOnion || overTor) {
    RpcEndpoint.Onion(
        onionHost = host.stripSchemeAndSlashes(),
        port = port,
        user = user,
        password = password,
        label = host,
    )
} else {
    RpcEndpoint.Lan(
        host = host.stripSchemeAndSlashes(),
        port = port,
        user = user,
        password = password,
        label = host,
    )
}
