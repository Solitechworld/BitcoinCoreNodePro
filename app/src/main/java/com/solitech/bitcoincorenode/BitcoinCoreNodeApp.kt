package com.solitech.bitcoincorenode

import android.app.Application
import androidx.work.Configuration
import com.solitech.bitcoincorenode.core.diag.CrashLog
import com.solitech.bitcoincorenode.data.explorer.ExplorerConfigurator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BitcoinCoreNodeApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    /** Keeps the block-explorer client in step with its settings. */
    @Inject lateinit var explorerConfigurator: ExplorerConfigurator

    /** Applies the saved remote-node RPC configuration, if any. */
    @Inject lateinit var remoteRpcConfigurator: com.solitech.bitcoincorenode.core.rpc.RemoteRpcConfigurator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // First, before anything else can fail. A crash during the rest of
        // startup is exactly the one we most need a trace for.
        CrashLog.install(this)
        explorerConfigurator.start()
        remoteRpcConfigurator.start()
        // Off the main path: the banner loads when it loads, and the SDK's
        // one-time init on a background thread is what Google recommends.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            com.solitech.bitcoincorenode.ads.Ads.initialize(this)
        }
        if (BuildConfig.DEBUG) {
            // Catches an RPC call that has wandered onto the main thread. On a
            // loopback connection that mistake is easy to make and hard to
            // notice -- localhost is fast enough that it only janks under load,
            // which is exactly when you can least afford it.
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .penaltyLog()
                    .build()
            )
        }
    }
}
