package com.anivault

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anivault.core.AppPrefs
import com.anivault.core.VaultSession
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.sync.AccountManager
import com.anivault.data.sync.SyncWorker
import com.anivault.ui.detail.EntryDetailSheet
import com.anivault.ui.discover.DiscoverScreen
import com.anivault.ui.home.HomeScreen
import com.anivault.ui.library.LibraryScreen
import com.anivault.ui.settings.*
import com.anivault.ui.stats.StatsScreen
import com.anivault.ui.theme.AniVaultTheme
import com.anivault.ui.vault.VaultScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

private object R_ {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val LIBRARY_ARG = "library?status={status}"
    const val DISCOVER = "discover"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val CATEGORY = "settings/{category}"
    const val API = "settings/api"
    const val VAULT = "vault"
    fun library(s: WatchStatus?) = "library?status=${s?.name ?: "ALL"}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/** Single-activity host. FragmentActivity so BiometricPrompt can attach. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var accounts: AccountManager
    @Inject lateinit var vaultSession: VaultSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handleIntent(intent)
        setContent {
            var theme by remember { mutableStateOf(prefs.theme) }
            var palette by remember { mutableStateOf(prefs.palette) }
            // Own listener (strongly held for the lifetime of the composition) so a change always reaches the theme.
            DisposableEffect(Unit) {
                val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> theme = prefs.theme; palette = prefs.palette }
                prefs.sp.registerOnSharedPreferenceChangeListener(l)
                onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(l) }
            }
            AniVaultTheme(theme = theme, palette = com.anivault.ui.theme.Palette.parse(palette)) { AppRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() { super.onStart(); vaultSession.onForegrounded() }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vaultSession.onBackgrounded()
    }

    /** OAuth redirect (anivault://auth/<provider>). */
    private fun handleIntent(i: Intent?) {
        val data = i?.dataString ?: return
        if (!data.startsWith("anivault://auth")) return
        lifecycleScope.launch {
            accounts.handleRedirect(data)?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            SyncWorker.runSoon(this@MainActivity)
        }
        i.data = null
    }

    @Composable
    private fun AppRoot() {
        val nav = rememberNavController()
        val tabs = remember {
            listOf(
                Tab(R_.HOME, "Home", Icons.Default.Home), Tab(R_.LIBRARY_ARG, "Library", Icons.Default.VideoLibrary),
                Tab(R_.DISCOVER, "Discover", Icons.Default.Explore), Tab(R_.SETTINGS, "Settings", Icons.Default.Settings)
            )
        }
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val showBar = route in setOf(R_.HOME, R_.LIBRARY_ARG, R_.DISCOVER, R_.SETTINGS)
        var detailId by remember { mutableStateOf<String?>(null) }
        var catalogItem by remember { mutableStateOf<com.anivault.data.provider.NormalizedAnime?>(null) }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route, icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                            onClick = { goTab(nav, if (t.route == R_.LIBRARY_ARG) R_.library(null) else t.route) }
                        )
                    }
                }
            }
        ) { pad ->
            NavHost(nav, startDestination = R_.HOME, modifier = Modifier.padding(pad)) {
                composable(R_.HOME) {
                    HomeScreen(
                        onOpenLibrary = { s -> goTab(nav, R_.library(s)) },
                        onOpenDiscover = { goTab(nav, R_.DISCOVER) },
                        onOpenStats = { nav.navigate(R_.STATS) },
                        onOpenEntry = { detailId = it },
                        onOpenCatalog = { catalogItem = it }
                    )
                }
                composable(R_.LIBRARY_ARG, arguments = listOf(navArgument("status") { type = NavType.StringType; defaultValue = "ALL" })) {
                    LibraryScreen(onOpenEntry = { detailId = it })
                }
                composable(R_.DISCOVER) { DiscoverScreen(onOpenSources = { nav.navigate(R_.API) }) }
                composable(R_.STATS) { StatsScreen(onBack = { nav.popBackStack() }) }
                composable(R_.SETTINGS) { SettingsHomeScreen(onOpen = { nav.navigate("settings/$it") }) }
                composable(R_.API) { ApiSourcesScreen(onBack = { nav.popBackStack() }) }
                composable(R_.VAULT) { VaultScreen(onBack = { nav.popBackStack() }) }
                composable(R_.CATEGORY) { be ->
                    val back = { nav.popBackStack(); Unit }
                    when (be.arguments?.getString("category")) {
                        "account" -> AccountScreen(back)
                        "library" -> LibrarySettingsScreen(back)
                        "player" -> PlayerSettingsScreen(back)
                        "backup" -> BackupScreen(back)
                        "vault" -> VaultSettingsScreen(back, onOpenVault = { nav.navigate(R_.VAULT) })
                        "appearance" -> AppearanceScreen(back)
                        "debuglog" -> if (BuildConfig.DEBUG) com.anivault.ui.settings.DebugLogScreen(back) else AdvancedScreen(back)
                        else -> AdvancedScreen(back)
                    }
                }
            }
        }
        detailId?.let { EntryDetailSheet(it, onDismiss = { detailId = null }) }
        catalogItem?.let { com.anivault.ui.discover.CatalogDetailHost(it, onDismiss = { catalogItem = null }) }
    }

    private fun goTab(nav: NavHostController, route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = false }
            launchSingleTop = true
        }
    }
}
