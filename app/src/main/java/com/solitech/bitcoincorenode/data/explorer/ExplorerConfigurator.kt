package com.solitech.bitcoincorenode.data.explorer

import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [ExplorerRepository] in step with the explorer settings.
 *
 * ## Why this class exists
 *
 * Because without it, the Settings toggles were decoration. The switches wrote
 * to DataStore and nothing ever read those values back into the repository, so
 * `ExplorerRepository.client` stayed null forever and turning the explorer on
 * did precisely nothing.
 *
 * That is a nastier class of bug than a crash: the app reported the state the
 * user asked for while behaving as though they had asked for the opposite. In
 * a privacy-affecting setting, a switch that says "on" while the feature is off
 * is the benign direction — the same bug pointing the other way would have been
 * leaking addresses while the UI said it wasn't.
 *
 * Started once from the Application class so the wiring cannot be forgotten by
 * a screen that happens not to be on the back stack.
 */
@Singleton
class ExplorerConfigurator @Inject constructor(
    private val settings: SettingsStore,
    private val explorer: ExplorerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            combine(
                settings.explorerEnabled,
                settings.explorerUrl,
                settings.explorerOverTor,
            ) { enabled, url, overTor -> Triple(enabled, url.trim(), overTor) }
                .distinctUntilChanged()
                .collect { (enabled, url, overTor) ->
                    if (enabled && url.isNotBlank()) {
                        explorer.configure(url, overTor)
                    } else {
                        // Explicitly tear the client down rather than leaving a
                        // stale one behind. "Off" has to mean no configured
                        // endpoint, not merely an endpoint nobody happens to be
                        // calling right now.
                        explorer.disable()
                    }
                }
        }
    }
}
