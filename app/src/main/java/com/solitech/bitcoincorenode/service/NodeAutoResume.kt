package com.solitech.bitcoincorenode.service

import android.content.Context
import com.solitech.bitcoincorenode.core.node.NodeState
import com.solitech.bitcoincorenode.core.node.NodeSupervisor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the sync back up when the app is reopened.
 *
 * ## The policy this implements
 *
 * Minimising the app must not interrupt the sync; closing it must. Those are
 * two different events on Android and they are handled in two different places:
 *
 *  - **Minimised** — nothing happens here at all. [NodeService] is a foreground
 *    service, so the process stays alive and `bitcoind` keeps downloading while
 *    the app is off screen. This class is not involved.
 *  - **Closed** (swiped out of recents) — `NodeService.onTaskRemoved` shuts the
 *    node down cleanly and records that it *was* running.
 *  - **Reopened** — this class reads that record and starts the service again.
 *
 * ## Why the supervisor is consulted before starting
 *
 * `NodeService` and the UI share one process, so [NodeSupervisor] is a genuine
 * singleton and its state is the truth about whether a node is already running.
 * Checking it means reopening the app from the recents list — where the service
 * never stopped — does not fire a redundant start that would flash "Starting
 * Bitcoin Core" over a node already at the chain tip.
 *
 * ## Why this does not resume after a crash
 *
 * Only [NodeState.Stopped] resumes. A node in [NodeState.Crashed] stays stopped
 * and visible on the Node screen with its cause, because relaunching a binary
 * that just died — on a corrupt datadir, say, or a disk with nothing left on it
 * — turns one failure into a loop the user cannot see the start of.
 */
@Singleton
class NodeAutoResume @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supervisor: NodeSupervisor,
    private val configProvider: NodeConfigProvider,
) {

    /**
     * Starts the node if — and only if — it was running when the app was last
     * closed and nothing is running now.
     *
     * @return true if a start was issued.
     */
    suspend fun resumeIfNeeded(): Boolean {
        if (supervisor.state.value != NodeState.Stopped) return false
        if (!configProvider.wasRunning()) return false
        NodeService.start(context)
        return true
    }
}
