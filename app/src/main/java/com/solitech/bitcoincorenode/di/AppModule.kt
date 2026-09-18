package com.solitech.bitcoincorenode.di

import android.content.Context
import com.solitech.bitcoincorenode.core.node.AssumeUtxoManager
import com.solitech.bitcoincorenode.core.node.NodePaths
import com.solitech.bitcoincorenode.core.node.NodeSupervisor
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.data.explorer.DefaultExplorerHttpClientProvider
import com.solitech.bitcoincorenode.data.explorer.ExplorerHttpClientProvider
import com.solitech.bitcoincorenode.service.NodeConfigProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DownloadClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun nodePaths(@ApplicationContext context: Context): NodePaths = NodePaths(context)

    @Provides @Singleton
    fun nodeSupervisor(@ApplicationContext context: Context, paths: NodePaths): NodeSupervisor =
        NodeSupervisor(context, paths)

    /**
     * The client used for snapshot downloads.
     *
     * Separate from the RPC clients on purpose: this one has no read timeout at
     * all, because it streams an 11 GB file over hours and any finite timeout
     * here is a promise to fail halfway. It also gets no proxy of its own — if
     * the user wants the snapshot over Tor they say so, and the caller supplies
     * a Tor-routed client instead.
     */
    @Provides @Singleton @DownloadClient
    fun downloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton
    fun assumeUtxoManager(
        @DownloadClient client: OkHttpClient,
        paths: NodePaths,
    ): AssumeUtxoManager = AssumeUtxoManager(client, paths.snapshotDir())

    @Provides @Singleton
    fun nodeConfigProvider(settings: SettingsStore): NodeConfigProvider = settings

    @Provides @Singleton
    fun explorerHttpClientProvider(
        impl: DefaultExplorerHttpClientProvider,
    ): ExplorerHttpClientProvider = impl
}
