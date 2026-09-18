package com.solitech.bitcoincorenode.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Wraps "start the node" so the notification permission is asked for first.
 *
 * ## Why the permission is not optional in practice
 *
 * The node runs as a foreground service, and on Android 13+ its ongoing
 * notification is suppressed unless POST_NOTIFICATIONS has been granted. The
 * service still runs — but the user loses the only two things that make a
 * background node acceptable: any sign that it is running, and the **Stop node**
 * button. A node syncing invisibly on someone's phone is not a feature.
 *
 * ## Why it is asked here rather than at launch
 *
 * Asked on first launch it is a dialog with no context, arriving before the
 * user has done anything, and a reflexive "Don't allow" is permanent. Asked on
 * the tap that starts a node, the reason is self-evident from what they just
 * pressed.
 *
 * The start happens either way. A denied permission is a worse experience, not
 * a blocked one, and refusing to honour the button they pressed would be the
 * app punishing them for an answer they were entitled to give.
 */
@Composable
fun rememberNodeStarter(onStart: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val start by rememberUpdatedState(onStart)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { start() }

    return remember(context) {
        {
            val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED

            if (needed) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
        }
    }
}
