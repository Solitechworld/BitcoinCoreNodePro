package com.solitech.bitcoincorenode

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.solitech.bitcoincorenode.core.model.BitcoinNetwork
import com.solitech.bitcoincorenode.core.prefs.AppLock
import com.solitech.bitcoincorenode.core.prefs.SettingsStore
import com.solitech.bitcoincorenode.service.NodeAutoResume
import com.solitech.bitcoincorenode.ui.BitcoinCoreNodeRoot
import com.solitech.bitcoincorenode.ui.screens.security.AppLockScreen
import com.solitech.bitcoincorenode.ui.theme.BitcoinCoreNodeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Extends [FragmentActivity], not ComponentActivity, because BiometricPrompt
 * requires a FragmentActivity to host its dialog. FragmentActivity is itself a
 * ComponentActivity, so `setContent` and the Compose integration are unchanged.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var autoResume: NodeAutoResume

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // FLAG_SECURE blocks screenshots, screen recording, and the thumbnail
        // Android puts in the recents switcher. That last one is why it is set
        // on the whole window rather than per-screen: the recents snapshot is
        // taken when the app backgrounds, which can happen while a seed phrase
        // is on screen, and there is no reliable hook to strip the flag first.
        //
        // Collecting the flow (not reading once) so the toggle applies the
        // instant it flips, in both directions: setFlags alone could never be
        // undone, which left screenshots blocked after the user allowed them.
        lifecycleScope.launch {
            settings.screenSecurity.collect { secure ->
                if (secure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        lifecycleScope.launch { appLock.initialise() }

        // Pick the sync back up if the app was closed while the node was
        // running. Deliberately not gated on the lock screen: resuming a block
        // download exposes nothing -- no balance, no address, no wallet is
        // touched -- and making the user unlock before their node will start
        // downloading again would punish them for having a lock at all.
        lifecycleScope.launch { autoResume.resumeIfNeeded() }

        setContent {
            val network by settings.network.collectAsState(initial = BitcoinNetwork.MAIN)
            val lockState by appLock.state.collectAsState()

            BitcoinCoreNodeTheme(network = network) {
                // The lock gates the whole app, including the navigation host.
                // Rendering the app underneath and overlaying a lock would put
                // real balances one screenshot or one accessibility read away.
                //
                // Debug builds skip the gate while import/sync issues are being
                // worked live: an enforced keypad turns every test cycle into
                // passcode-tapping that verifies nothing about the bug at hand.
                // Release builds always lock.
                if (lockState == AppLock.State.UNLOCKED || BuildConfig.DEBUG) {
                    BitcoinCoreNodeRoot(
                        initialIntentData = intent?.data?.toString(),
                    )
                } else {
                    AppLockScreen()
                }
            }
        }
    }

    /**
     * Re-lock when the app leaves the foreground.
     *
     * onStop rather than onPause: onPause fires for a biometric prompt and for
     * the system share sheet, and re-locking there would fight the user during
     * their own unlock. onStop means genuinely backgrounded.
     */
    override fun onStop() {
        super.onStop()
        appLock.lock()
    }
}
