package com.solitech.bitcoincorenode.core.prefs

import android.content.Context
import android.app.ActivityManager
import android.os.StatFs
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.solitech.bitcoincorenode.core.model.BitcoinNetwork
import com.solitech.bitcoincorenode.core.model.DisplayUnit
import com.solitech.bitcoincorenode.core.node.DeviceTuning
import com.solitech.bitcoincorenode.core.node.NodeConfig
import com.solitech.bitcoincorenode.service.NodeConfigProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "bitcoincorenode_settings")

/** User-entered external node connection, applied via RpcProvider.useRemote. */
data class RemoteRpcConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 8332,
    val user: String = "",
    val password: String = "",
    val overTor: Boolean = false,
) {
    val isComplete: Boolean get() = host.isNotBlank() && user.isNotBlank() && password.isNotBlank()
    val isOnion: Boolean get() = host.endsWith(".onion")
}

/**
 * User settings, and the place where node configuration is assembled.
 *
 * Defaults are computed from the device rather than hardcoded.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : NodeConfigProvider {

    private object Keys {
        val NETWORK = stringPreferencesKey("network")
        val DISPLAY_UNIT = stringPreferencesKey("display_unit")
        val PRUNE_MB = intPreferencesKey("prune_mb")
        val DBCACHE_MB = intPreferencesKey("dbcache_mb")
        val SCRIPT_THREADS = intPreferencesKey("script_threads")
        val MAX_CONNECTIONS = intPreferencesKey("max_connections")
        val BLOCKS_ONLY = booleanPreferencesKey("blocks_only")
        val BLOCK_FILTERS = booleanPreferencesKey("block_filters")
        val USE_TOR = booleanPreferencesKey("use_tor")
        val TOR_ONLY = booleanPreferencesKey("tor_only")
        val TOR_PORT = intPreferencesKey("tor_socks_port")
        val KEEP_AWAKE = booleanPreferencesKey("keep_cpu_awake")
        val EXTRA_ARGS = stringPreferencesKey("extra_args")
        val SNAPSHOT_URL = stringPreferencesKey("snapshot_url")
        val SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        val REQUIRE_BIOMETRIC = booleanPreferencesKey("require_biometric")
        val EXPLORER_ENABLED = booleanPreferencesKey("explorer_enabled")
        val EXPLORER_URL = stringPreferencesKey("explorer_url")
        val EXPLORER_OVER_TOR = booleanPreferencesKey("explorer_over_tor")
        val EXPLORER_XPUB_OK = booleanPreferencesKey("explorer_xpub_consent")
        val ACTIVE_WALLET = stringPreferencesKey("active_wallet")
        val REMOTE_ENABLED = booleanPreferencesKey("remote_rpc_enabled")
        val REMOTE_HOST = stringPreferencesKey("remote_rpc_host")
        val REMOTE_PORT = intPreferencesKey("remote_rpc_port")
        val REMOTE_USER = stringPreferencesKey("remote_rpc_user")
        val REMOTE_PASSWORD = stringPreferencesKey("remote_rpc_password")
        val REMOTE_OVER_TOR = booleanPreferencesKey("remote_rpc_over_tor")
        val PASSCODE_HASH = stringPreferencesKey("passcode_hash")
        val PASSCODE_SALT = stringPreferencesKey("passcode_salt")
        val LOCK_CONFIGURED = booleanPreferencesKey("lock_configured")
        val NODE_WAS_RUNNING = booleanPreferencesKey("node_was_running")
    }

    val network: Flow<BitcoinNetwork> = context.dataStore.data.map { p ->
        BitcoinNetwork.fromChainName(p[Keys.NETWORK] ?: BitcoinNetwork.MAIN.chainName)
    }

    val displayUnit: Flow<DisplayUnit> = context.dataStore.data.map { p ->
        runCatching { DisplayUnit.valueOf(p[Keys.DISPLAY_UNIT] ?: "BTC") }.getOrDefault(DisplayUnit.BTC)
    }

    val screenSecurity: Flow<Boolean> = context.dataStore.data.map { it[Keys.SCREEN_SECURITY] ?: true }
    val requireBiometric: Flow<Boolean> = context.dataStore.data.map { it[Keys.REQUIRE_BIOMETRIC] ?: true }
    val snapshotUrl: Flow<String> = context.dataStore.data.map { it[Keys.SNAPSHOT_URL] ?: "" }

    val explorerEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.EXPLORER_ENABLED] ?: false }

    val explorerUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.EXPLORER_URL] ?: "" }

    val explorerOverTor: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.EXPLORER_OVER_TOR] ?: false }

    val explorerXpubConsent: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.EXPLORER_XPUB_OK] ?: false }

    suspend fun setNetwork(value: BitcoinNetwork) =
        context.dataStore.edit { it[Keys.NETWORK] = value.chainName }

    suspend fun setDisplayUnit(value: DisplayUnit) =
        context.dataStore.edit { it[Keys.DISPLAY_UNIT] = value.name }

    suspend fun isLockConfigured(): Boolean =
        context.dataStore.data.first()[Keys.LOCK_CONFIGURED] ?: false

    suspend fun passcodeHash(): String? = context.dataStore.data.first()[Keys.PASSCODE_HASH]
    suspend fun passcodeSalt(): String? = context.dataStore.data.first()[Keys.PASSCODE_SALT]

    suspend fun setPasscode(hashHex: String, saltHex: String) = context.dataStore.edit {
        it[Keys.PASSCODE_HASH] = hashHex
        it[Keys.PASSCODE_SALT] = saltHex
        it[Keys.LOCK_CONFIGURED] = true
    }

    /** Removes the passcode: nothing to verify, nothing to lock with. */
    suspend fun clearPasscode() = context.dataStore.edit {
        it.remove(Keys.PASSCODE_HASH)
        it.remove(Keys.PASSCODE_SALT)
        it.remove(Keys.LOCK_CONFIGURED)
    }

    suspend fun setActiveWallet(name: String?) = context.dataStore.edit { prefs ->
        if (name.isNullOrBlank()) prefs.remove(Keys.ACTIVE_WALLET) else prefs[Keys.ACTIVE_WALLET] = name
    }

    // -- remote RPC node -----------------------------------------------------

    val remoteRpcEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.REMOTE_ENABLED] ?: false }

    /** One snapshot of the remote-node configuration, changing as a unit. */
    val remoteRpc: Flow<RemoteRpcConfig> = context.dataStore.data.map { p ->
        RemoteRpcConfig(
            enabled = p[Keys.REMOTE_ENABLED] ?: false,
            host = p[Keys.REMOTE_HOST] ?: "",
            port = p[Keys.REMOTE_PORT] ?: 8332,
            user = p[Keys.REMOTE_USER] ?: "",
            password = p[Keys.REMOTE_PASSWORD] ?: "",
            overTor = p[Keys.REMOTE_OVER_TOR] ?: false,
        )
    }

    suspend fun setRemoteRpc(config: RemoteRpcConfig) = context.dataStore.edit { p ->
        p[Keys.REMOTE_ENABLED] = config.enabled
        p[Keys.REMOTE_HOST] = config.host.trim()
        p[Keys.REMOTE_PORT] = config.port
        p[Keys.REMOTE_USER] = config.user.trim()
        p[Keys.REMOTE_PASSWORD] = config.password
        p[Keys.REMOTE_OVER_TOR] = config.overTor
    }

    suspend fun setRemoteRpcEnabled(value: Boolean) = context.dataStore.edit {
        it[Keys.REMOTE_ENABLED] = value
    }

    suspend fun setPruneMb(value: Int) = context.dataStore.edit { it[Keys.PRUNE_MB] = value }
    suspend fun setDbCacheMb(value: Int) = context.dataStore.edit { it[Keys.DBCACHE_MB] = value }
    suspend fun setBlocksOnly(value: Boolean) = context.dataStore.edit { it[Keys.BLOCKS_ONLY] = value }
    suspend fun setUseTor(value: Boolean) = context.dataStore.edit { it[Keys.USE_TOR] = value }
    suspend fun setTorOnly(value: Boolean) = context.dataStore.edit { it[Keys.TOR_ONLY] = value }
    suspend fun setKeepAwake(value: Boolean) = context.dataStore.edit { it[Keys.KEEP_AWAKE] = value }
    suspend fun setSnapshotUrl(value: String) = context.dataStore.edit { it[Keys.SNAPSHOT_URL] = value }
    suspend fun setScreenSecurity(value: Boolean) = context.dataStore.edit { it[Keys.SCREEN_SECURITY] = value }
    suspend fun setRequireBiometric(value: Boolean) = context.dataStore.edit { it[Keys.REQUIRE_BIOMETRIC] = value }
    suspend fun setExplorerEnabled(value: Boolean) = context.dataStore.edit { it[Keys.EXPLORER_ENABLED] = value }
    suspend fun setExplorerUrl(value: String) = context.dataStore.edit { it[Keys.EXPLORER_URL] = value.trim() }
    suspend fun setExplorerOverTor(value: Boolean) = context.dataStore.edit { it[Keys.EXPLORER_OVER_TOR] = value }
    suspend fun setExplorerXpubConsent(value: Boolean) = context.dataStore.edit { it[Keys.EXPLORER_XPUB_OK] = value }

    override suspend fun current(): NodeConfig {
        val prefs = context.dataStore.data.first()
        val net = BitcoinNetwork.fromChainName(prefs[Keys.NETWORK] ?: BitcoinNetwork.MAIN.chainName)

        val am = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalRamMb = memInfo.totalMem / (1024 * 1024)

        return NodeConfig(
            network = net,
            rpcPort = net.defaultRpcPort,
            // Default prune is 20 GB, with options from 15 to 50 GB.
            pruneMb = prefs[Keys.PRUNE_MB] ?: DEFAULT_PRUNE_MB,
            dbCacheMb = prefs[Keys.DBCACHE_MB]
                ?: DeviceTuning.recommendedDbCacheMb(totalRamMb, am.isLowRamDevice),
            parallelScriptChecks = prefs[Keys.SCRIPT_THREADS]
                ?: DeviceTuning.recommendedScriptThreads(Runtime.getRuntime().availableProcessors()),
            maxConnections = prefs[Keys.MAX_CONNECTIONS] ?: 12,
            // Core's own default (blocksonly=0). The old default of true kept
            // the mempool empty: incoming payments never showed as pending
            // and fee estimation had nothing to work with — which reads, on
            // a wallet screen, exactly like "balance not syncing".
            blocksOnly = prefs[Keys.BLOCKS_ONLY] ?: false,
            blockFilterIndex = prefs[Keys.BLOCK_FILTERS] ?: false,
            useTor = prefs[Keys.USE_TOR] ?: false,
            torSocksPort = prefs[Keys.TOR_PORT] ?: 9050,
            torOnly = prefs[Keys.TOR_ONLY] ?: false,
            listen = false,
            extraArgs = (prefs[Keys.EXTRA_ARGS] ?: "").lines().filter { it.isNotBlank() },
        )
    }

    override suspend fun setWasRunning(running: Boolean) {
        context.dataStore.edit { it[Keys.NODE_WAS_RUNNING] = running }
    }

    override suspend fun wasRunning(): Boolean =
        context.dataStore.data.first()[Keys.NODE_WAS_RUNNING] ?: false

    override suspend fun keepCpuAwakeWhileSyncing(): Boolean =
        context.dataStore.data.first()[Keys.KEEP_AWAKE] ?: true

    val activeWallet: Flow<String?> =
        context.dataStore.data.map { it[Keys.ACTIVE_WALLET]?.takeIf(String::isNotBlank) }

    val lockConfigured: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.LOCK_CONFIGURED] ?: false }

    val pruneMb: Flow<Int> =
        context.dataStore.data.map { it[Keys.PRUNE_MB] ?: DEFAULT_PRUNE_MB }

    fun freeStorageBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    companion object {
        /** 20 GB default prune — user requested, with options from 15 to 50 GB. */
        const val DEFAULT_PRUNE_MB = 20_000

        /** Available prune options in GB. */
        val PRUNE_OPTIONS_MB = listOf(15_000, 20_000, 25_000, 30_000, 40_000, 50_000)

        const val PRUNE_OVERHEAD_MB = 12_000
    }

    fun storageFitsPrune(pruneMb: Int): Boolean =
        freeStorageBytes() >= (pruneMb + PRUNE_OVERHEAD_MB) * 1024L * 1024L

    fun canRunEmbeddedNode(): Boolean =
        freeStorageBytes() >= DeviceTuning.MINIMUM_FREE_BYTES_FOR_EMBEDDED
}
