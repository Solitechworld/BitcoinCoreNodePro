package com.solitech.bitcoincorenode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.solitech.bitcoincorenode.MainActivity
import com.solitech.bitcoincorenode.R
import com.solitech.bitcoincorenode.core.diag.CrashLog
import com.solitech.bitcoincorenode.core.node.NodeConfig
import com.solitech.bitcoincorenode.core.node.NodeState
import com.solitech.bitcoincorenode.core.node.NodeSupervisor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the node alive and tells the user it is running.
 *
 * ## Why a foreground service is not optional
 *
 * A background process on modern Android is a process on borrowed time. Doze,
 * App Standby and the low-memory killer will all reclaim it, and for most apps
 * that is fine — they lose a network request. Here, being killed mid-write
 * means a corrupted chainstate and a user facing hours or days of resync.
 *
 * A foreground service with an ongoing notification is the only supported way
 * to say "this work must finish". The notification is not a nag; it is the
 * contract with the OS, and it is also honest: something on this phone really
 * is using CPU and network on the user's behalf, and they deserve to see it and
 * be one tap from stopping it.
 *
 * ## Wakelock policy
 *
 * Off by default. A partial wakelock during IBD genuinely does cut a multi-day
 * sync down, because the CPU stops being suspended every time the screen turns
 * off — but it also visibly eats battery, and taking one without asking is the
 * kind of thing that gets an app uninstalled. It is a Settings toggle, worded
 * plainly, and it is released the moment the node reaches the tip.
 */
@AndroidEntryPoint
class NodeService : LifecycleService() {

    @Inject lateinit var supervisor: NodeSupervisor
    @Inject lateinit var configProvider: NodeConfigProvider

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationText: String? = null
    private var lastTitle: String = NOTIF_STARTING
    private var lastText: String? = null

    /**
     * True once [startForeground] has actually been called for this instance.
     *
     * Nothing may call `stopSelf()` before this flips. The service is launched
     * with `startForegroundService()`, which hands Android a promise that
     * `startForeground()` will follow within five seconds; a service that dies
     * first breaks that promise and Android answers by killing the whole
     * process with ForegroundServiceDidNotStartInTimeException.
     */
    private var foregrounded = false

    /** True between a successful node start and the node reaching Stopped. */
    private var nodeRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeState()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Unconditionally, and before anything that can fail or suspend.
        // Every path into this service arrives via startForegroundService(),
        // including the null-intent redelivery after START_STICKY, and every
        // one of them owes Android a startForeground() call within five
        // seconds. Doing it once here means no branch can forget.
        // Reuses whatever the notification currently says, so a second command
        // arriving at a running service (the Stop action, or a redelivery) does
        // not flash "Starting Bitcoin Core" over a node already at the tip.
        startForegroundCompat(buildNotification(lastTitle, lastText))
        foregrounded = true

        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch {
                    updateNotification("Shutting down cleanly…", "Do not force-close the app")
                    // Deliberately not stopSelf() first. The service has to
                    // outlive the shutdown, because that is when Core flushes
                    // its UTXO cache and the whole point of this class is to
                    // survive long enough for that to finish.
                    // An explicit Stop means "stay stopped" -- do not resume
                    // on next launch.
                    runCatching { configProvider.setWasRunning(false) }
                    nodeRequested = false
                    supervisor.stop()
                    releaseWakeLock()
                    stopForegroundCompat()
                    stopSelf()
                }
            }

            // ACTION_START, and the null intent Android redelivers when it
            // restarts a START_STICKY service after killing it, are the same
            // job: get the node running.
            else -> launchNode(resumeOnly = intent?.action != ACTION_START)
        }

        // START_STICKY: if the system kills us anyway, come back. The node's
        // own consistency checks handle whatever state it was left in.
        return START_STICKY
    }

    /**
     * Starts the node, and never lets a failure reach the default uncaught
     * handler.
     *
     * A throw on this path used to take the whole app down with it, which from
     * the outside looks exactly like "the app closes when I press Start" and
     * says nothing about why. Anything that goes wrong is turned into a visible
     * [NodeState.Crashed] with the message attached, so the Node screen can
     * show it and the user is left with a running app and an explanation.
     */
    private fun launchNode(resumeOnly: Boolean) = lifecycleScope.launch {
        try {
            // A redelivered start only makes sense if the node was meant to be
            // running. Without this, a system restart of the service would
            // start a node the user had deliberately stopped.
            if (resumeOnly && !configProvider.wasRunning()) {
                stopForegroundCompat()
                stopSelf()
                return@launch
            }

            nodeRequested = true
            val config = configProvider.current()
            val result = supervisor.start(config)
            result.exceptionOrNull()?.let { throw it }

            configProvider.setWasRunning(true)
            if (configProvider.keepCpuAwakeWhileSyncing()) acquireWakeLock()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            nodeRequested = false
            releaseWakeLock()
            CrashLog.record(this@NodeService, "Starting the node", e)
            supervisor.reportStartFailure(e)
            updateNotification(
                "Node could not start",
                e.message?.take(120) ?: e.javaClass.simpleName,
            )
            // Nothing is running, so holding a foreground service open would be
            // a lie to the OS and a battery-shaped question mark for the user.
            // The failure itself is not lost: the supervisor is now in
            // NodeState.Crashed and the Node screen renders the cause and log.
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            supervisor.state.collectLatest { state ->
                when (state) {
                    is NodeState.Starting ->
                        updateNotification(state.stage.label, state.detail.ifBlank { null })

                    is NodeState.Loading ->
                        updateNotification("Loading", state.message)

                    is NodeState.SnapshotLoading ->
                        updateNotification(
                            state.phase.label,
                            "${(state.fraction * 100).toInt()}%",
                        )

                    is NodeState.Syncing -> {
                        val behind = state.info.blocksBehind
                        updateNotification(
                            "Syncing — ${state.info.blocks} blocks",
                            if (behind > 0) "$behind behind" else "catching up",
                        )
                    }

                    is NodeState.Synced -> {
                        // At the tip the CPU no longer needs to be pinned.
                        releaseWakeLock()
                        updateNotification(
                            "Synced at block ${state.info.blocks}",
                            "${state.network?.connections ?: 0} peers",
                        )
                    }

                    is NodeState.Reindexing ->
                        updateNotification("Reindexing", state.message)

                    is NodeState.Crashed -> {
                        releaseWakeLock()
                        updateNotification("Node stopped", state.likelyCause.summary)
                    }

                    NodeState.Stopping -> updateNotification("Shutting down cleanly…", null)

                    NodeState.Stopped -> {
                        releaseWakeLock()
                        // Both guards matter.
                        //
                        // `foregrounded` : this collector receives the flow's
                        // CURRENT value the instant it subscribes, and that
                        // value is Stopped, in onCreate, before onStartCommand
                        // has run. Calling stopSelf() there killed the service
                        // before it could ever call startForeground() -- which
                        // is precisely what Android kills the whole process
                        // for, and it looked like the app closing the moment
                        // Start was pressed.
                        //
                        // `nodeRequested` : only stop for a node that actually
                        // ran and has now finished, not for one that never
                        // started.
                        if (foregrounded && nodeRequested) {
                            nodeRequested = false
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    // ---- notification plumbing ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bitcoin node",
            // LOW: ongoing and silent. This notification exists to satisfy the
            // foreground-service contract and to give the user a stop button,
            // not to interrupt them.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the status of the Bitcoin Core node running on this device"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_node_status)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop node", stop)
            .build()
    }

    private fun updateNotification(title: String, text: String?) {
        lastTitle = title
        lastText = text
        val key = "$title|$text"
        if (key == lastNotificationText) return   // avoid redundant IPC every tick
        lastNotificationText = key
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- wakelock ----

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            // Always time-bounded. An untimed wakelock that leaks past a crash
            // drains the battery flat with nothing left running to release it.
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * The user swiped the app away. Shut the node down cleanly and stop.
     *
     * This is the "pause when closed" half of the background policy. Minimising
     * the app does NOT come through here -- the foreground service keeps syncing
     * exactly as intended. Only removing the task does.
     *
     * The work is done here rather than by letting Android kill us
     * (stopWithTask="true") because Core needs to flush its UTXO cache. A
     * process killed mid-flush leaves a corrupt chainstate and a user facing a
     * resync, which is a far worse outcome than a few seconds of shutdown.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        lifecycleScope.launch {
            nodeRequested = false
            updateNotification("Stopping node…", "The app was closed")
            // Remember it was running, so reopening resumes rather than
            // silently leaving the node off.
            runCatching { configProvider.setWasRunning(true) }
            supervisor.stop()
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "node_status"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.solitech.bitcoincorenode.START_NODE"
        const val ACTION_STOP = "com.solitech.bitcoincorenode.STOP_NODE"
        private const val NOTIF_STARTING = "Starting Bitcoin Core"
        private const val WAKELOCK_TAG = "BitcoinCoreNode::sync"
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000  // 6 hours

        fun start(context: Context) {
            val i = Intent(context, NodeService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, NodeService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}

/** Supplies the node configuration the service should start with. */
interface NodeConfigProvider {
    suspend fun current(): NodeConfig
    suspend fun keepCpuAwakeWhileSyncing(): Boolean

    /**
     * Remembers whether the node was running when the app was closed, so
     * reopening resumes the sync instead of leaving it silently stopped.
     */
    suspend fun setWasRunning(running: Boolean)
    suspend fun wasRunning(): Boolean
}
