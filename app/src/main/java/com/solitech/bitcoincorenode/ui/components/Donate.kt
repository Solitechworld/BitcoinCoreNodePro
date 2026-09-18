package com.solitech.bitcoincorenode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.solitech.bitcoincorenode.core.util.Donation
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.CyberType

/**
 * The donation footer that sits at the bottom of every scrolling screen.
 *
 * Collapsed by default to a single quiet row. That restraint is not modesty for
 * its own sake: a donation prompt competing for attention with a balance or a
 * send button trains people to scroll past it, and an ever-present ask in a
 * wallet reads as pressure. One line, expandable, at the end of the content —
 * where someone who has finished what they came to do will actually read it.
 *
 * It is deliberately styled in the neutral surface colour rather than the
 * network accent, so it never looks like a control that does something to the
 * user's money.
 */
@Composable
fun DonateFooter(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(modifier.fillMaxWidth()) {
        CyberDivider()
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = CyberColors.Magenta,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                "Support this project".uppercase(),
                style = CyberType.HudLabel,
                color = CyberColors.Magenta,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "HIDE" else "DONATE",
                style = CyberType.HudLabel,
                color = CyberColors.TextTertiary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    Donation.SHORT_PITCH,
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberColors.TextTertiary,
                )
                Spacer(Modifier.height(12.dp))
                DonatePanel(compact = true)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The full donation panel: address, QR, and the two things a payer needs — a
 * way to copy it and a way to check it.
 *
 * Uses [AddressText], so the donation address gets the same four-character
 * grouping as any other address in the app. Someone about to send money to a
 * hardcoded address deserves the same verification affordance as someone
 * sending it to a stranger — arguably more, since they cannot ask the recipient
 * to confirm it.
 */
@Composable
fun DonatePanel(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    CyberPanel(modifier.fillMaxWidth(), accent = CyberColors.Magenta, glow = !compact) {
        if (!compact) {
            HudSectionHeader("Support the project", accent = CyberColors.Magenta)
            Spacer(Modifier.height(12.dp))
            Text(
                Donation.LONG_PITCH,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberColors.TextSecondary,
            )
            Spacer(Modifier.height(18.dp))
            QrCode(content = Donation.URI, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
        }

        Text("Bitcoin address".uppercase(), style = CyberType.HudLabel, color = CyberColors.TextTertiary)
        Spacer(Modifier.height(7.dp))
        AddressText(Donation.ADDRESS)
        Spacer(Modifier.height(8.dp))
        Text(
            Donation.VERIFY_HINT,
            style = CyberType.Terminal,
            color = CyberColors.TextTertiary,
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CyberButton(
                text = if (copied) "Copied" else "Copy address",
                onClick = { copyDonationAddress(context); copied = true },
                style = CyberButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f),
            )
            CyberButton(
                text = "Share",
                onClick = { shareDonationAddress(context) },
                style = CyberButtonStyle.GHOST,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun copyDonationAddress(context: Context) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Bitcoin Core Node donation address", Donation.ADDRESS))
}

private fun shareDonationAddress(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Support Bitcoin Core Node")
        putExtra(Intent.EXTRA_TEXT, "${Donation.SHORT_PITCH}\n\n${Donation.ADDRESS}")
    }
    context.startActivity(Intent.createChooser(intent, "Share donation address"))
}
