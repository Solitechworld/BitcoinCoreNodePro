package com.solitech.bitcoincorenode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.solitech.bitcoincorenode.ui.components.CyberBottomBar
import com.solitech.bitcoincorenode.ui.nav.Dest
import com.solitech.bitcoincorenode.ui.nav.TopLevel
import com.solitech.bitcoincorenode.ui.screens.console.ConsoleScreen
import com.solitech.bitcoincorenode.ui.screens.dashboard.DashboardScreen
import com.solitech.bitcoincorenode.ui.screens.mempool.MempoolScreen
import com.solitech.bitcoincorenode.ui.screens.node.NodeControlScreen
import com.solitech.bitcoincorenode.ui.screens.peers.PeersScreen
import com.solitech.bitcoincorenode.ui.screens.receive.ReceiveScreen
import com.solitech.bitcoincorenode.ui.screens.send.SendScreen
import com.solitech.bitcoincorenode.ui.screens.settings.SettingsScreen
import com.solitech.bitcoincorenode.ui.screens.wallet.WalletListScreen
import com.solitech.bitcoincorenode.ui.screens.wallet.WalletScreen
import com.solitech.bitcoincorenode.ui.screens.wallet.WalletToolsScreen
import com.solitech.bitcoincorenode.ui.theme.CyberColors
import com.solitech.bitcoincorenode.ui.theme.cyberGrid
import com.solitech.bitcoincorenode.ui.theme.vignette

/**
 * The navigation host and the persistent chrome around it.
 *
 * The grid and vignette live here rather than on each screen so they are
 * continuous across navigation — a background that redraws per destination
 * reads as a series of pages, and the intent is a single instrument panel you
 * are moving around inside.
 */
@Composable
fun BitcoinCoreNodeRoot(initialIntentData: String? = null) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()

    // Match on the route's LAST path segment rather than a substring anywhere
    // in it. A substring match makes tab highlighting depend on class names not
    // being prefixes of each other, which is a trap waiting for the next
    // destination someone adds.
    val currentRoute = backStack?.destination?.route
    val currentTopLevel = TopLevel.entries.firstOrNull { top ->
        currentRoute?.substringBefore('/')?.substringAfterLast('.') ==
            top.dest.javaClass.simpleName
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberColors.Void)
            .cyberGrid()
            .vignette()
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = {
                // The ad slot is part of the chrome, not any screen: it sits
                // above the navigation bar in the one composable that outlives
                // navigation, so it is present everywhere and no screen can
                // avoid it. Slim by design (anchored adaptive banner).
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    com.solitech.bitcoincorenode.ads.AdBanner(
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CyberBottomBar(
                        selected = currentTopLevel,
                        onSelect = { top ->
                            // popUpTo MUST target the graph's start destination by
                            // id, not the destination being navigated to. The old
                            // code did popUpTo(Dest.Dashboard) while also
                            // navigating to Dashboard: popUpTo left Dashboard on
                            // top, then launchSingleTop saw it already there and
                            // no-opped, so the Home tab did nothing once you had
                            // navigated away from it. This is the canonical
                            // bottom-bar pattern and it is canonical for a reason.
                            navController.navigate(top.dest) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Dest.Dashboard,
                modifier = Modifier.padding(padding),
            ) {
                composable<Dest.Dashboard> {
                    DashboardScreen(
                        onOpenNode = { navController.navigate(Dest.NodeControl) },
                        onOpenWallets = { navController.navigate(Dest.WalletList) },
                        onSend = { w -> navController.navigate(Dest.Send(w)) },
                        onReceive = { w -> navController.navigate(Dest.Receive(w)) },
                        onOpenImportExport = { navController.navigate(Dest.ImportExport) },
                    )
                }
                composable<Dest.ImportExport> {
                    com.solitech.bitcoincorenode.ui.screens.wallet.ImportExportScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Dest.NodeControl> {
                    NodeControlScreen(
                        onOpenPeers = { navController.navigate(Dest.Peers) },
                        onOpenConsole = { navController.navigate(Dest.Console) },
                        onOpenSnapshot = { navController.navigate(Dest.SnapshotBootstrap) },
                        onOpenMempool = { navController.navigate(Dest.Mempool) },
                    )
                }
                composable<Dest.Peers> { PeersScreen(onBack = { navController.popBackStack() }) }
                composable<Dest.Mempool> { MempoolScreen() }
                composable<Dest.SnapshotBootstrap> {
                    com.solitech.bitcoincorenode.ui.screens.node.SnapshotBootstrapScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Dest.Transactions> {
                    com.solitech.bitcoincorenode.ui.screens.transactions.TransactionsScreen()
                }
                composable<Dest.Console> { ConsoleScreen(onBack = { navController.popBackStack() }) }
                composable<Dest.Settings> {
                    SettingsScreen(onOpenRemoteNode = { navController.navigate(Dest.RemoteNode) })
                }
                composable<Dest.RemoteNode> {
                    com.solitech.bitcoincorenode.ui.screens.settings.RemoteNodeScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Dest.WalletList> {
                    WalletListScreen(onOpen = { name -> navController.navigate(Dest.Wallet(name)) })
                }
                composable<Dest.Wallet> { entry ->
                    val args = entry.arguments
                    WalletScreen(
                        walletName = args?.getString("name").orEmpty(),
                        onSend = { w -> navController.navigate(Dest.Send(w)) },
                        onReceive = { w -> navController.navigate(Dest.Receive(w)) },
                        onBack = { navController.popBackStack() },
                        onTools = { w -> navController.navigate(Dest.WalletTools(w)) },
                    )
                }
                composable<Dest.WalletTools> { entry ->
                    WalletToolsScreen(
                        walletName = entry.arguments?.getString("wallet").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Dest.Send> { entry ->
                    SendScreen(
                        walletName = entry.arguments?.getString("wallet").orEmpty(),
                        prefillUri = entry.arguments?.getString("prefillUri") ?: initialIntentData,
                        onDone = { navController.popBackStack() },
                    )
                }
                composable<Dest.Receive> { entry ->
                    ReceiveScreen(
                        walletName = entry.arguments?.getString("wallet").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
