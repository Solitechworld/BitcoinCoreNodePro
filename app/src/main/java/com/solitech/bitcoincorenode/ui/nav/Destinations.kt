package com.solitech.bitcoincorenode.ui.nav

import kotlinx.serialization.Serializable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Type-safe navigation routes.
 *
 * Serializable objects rather than string routes with `{placeholders}`: a
 * mistyped argument name in a string route is a runtime crash discovered by a
 * user, whereas these are checked at compile time. In a wallet, "navigate to
 * the send screen with the wrong txid" is not a class of bug worth keeping
 * available.
 */
sealed interface Dest {

    @Serializable data object Onboarding : Dest
    @Serializable data object Dashboard : Dest
    @Serializable data object NodeControl : Dest
    @Serializable data object Peers : Dest
    @Serializable data object Mempool : Dest
    @Serializable data object Console : Dest
    @Serializable data object Settings : Dest
    @Serializable data object RemoteNode : Dest
    @Serializable data object Security : Dest
    @Serializable data object WalletList : Dest
    @Serializable data object ImportExport : Dest
    @Serializable data object CreateWallet : Dest
    @Serializable data object Psbt : Dest
    @Serializable data object SnapshotBootstrap : Dest

    @Serializable data class Wallet(val name: String) : Dest
    @Serializable data class WalletTools(val wallet: String) : Dest
    @Serializable data class Send(val wallet: String, val prefillUri: String? = null) : Dest
    @Serializable data class Receive(val wallet: String) : Dest
    @Serializable data object Transactions : Dest
    @Serializable data class TransactionDetail(val wallet: String, val txid: String) : Dest
    @Serializable data class CoinControl(val wallet: String) : Dest
    @Serializable data class BlockDetail(val hashOrHeight: String) : Dest
}

/** The five bottom-bar destinations, in order. */
enum class TopLevel(val label: String, val dest: Dest, val icon: ImageVector) {
    HOME("Home", Dest.Dashboard, Icons.Rounded.Home),
    NODE("Node", Dest.NodeControl, Icons.Rounded.Bolt),
    WALLET("Wallet", Dest.WalletList, Icons.Rounded.AccountBalanceWallet),
    CHAIN("Activity", Dest.Transactions, Icons.Rounded.ReceiptLong),
    // "Set" was a truncation forced by a bottom bar with no icons and no room.
    // With icons carrying the recognition, the label can be the real word.
    SETTINGS("Settings", Dest.Settings, Icons.Rounded.Settings),
}
