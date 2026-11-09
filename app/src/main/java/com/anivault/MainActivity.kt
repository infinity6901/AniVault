package com.anivault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.anivault.ui.calendar.CalendarScreen
import com.anivault.ui.detail.EntryDetailSheet
import com.anivault.ui.discover.DiscoverScreen
import com.anivault.ui.home.HomeScreen
import com.anivault.ui.library.LibraryScreen
import com.anivault.ui.settings.*
import com.anivault.ui.stats.StatsScreen
import com.anivault.ui.theme.AniVaultTheme
import com.anivault.ui.theme.LiquidGlassConfig
import com.anivault.ui.theme.LocalLiquidGlass
import com.anivault.ui.theme.Palette
import com.anivault.ui.theme.liquidGlassPanel
import com.anivault.ui.vault.VaultScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private object R_ {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val LIBRARY_ARG = "library?status={status}"
    const val DISCOVER = "discover"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val CALENDAR = "calendar"
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

    private val deepLinkEntryId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handleIntent(intent)
        setContent {
            var theme by remember { mutableStateOf(prefs.theme) }
            var paletteKey by remember { mutableStateOf(prefs.palette) }
            var pal = Palette.parse(paletteKey)
            var glassCfg by remember { mutableStateOf(LiquidGlassConfig.fromPrefs(prefs, pal)) }
            // Own listener (strongly held for the lifetime of the composition) so a change always reaches the theme.
            DisposableEffect(Unit) {
                val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    theme = prefs.theme
                    paletteKey = prefs.palette
                    val nextPal = Palette.parse(paletteKey)
                    glassCfg = LiquidGlassConfig.fromPrefs(prefs, nextPal)
                }
                prefs.sp.registerOnSharedPreferenceChangeListener(l)
                onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(l) }
            }
            pal = Palette.parse(paletteKey)
            AniVaultTheme(theme = theme, palette = pal, liquidGlass = glassCfg) { AppRoot() }
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
        com.anivault.widget.WidgetUpdater.refreshAll(this)
    }

    /** Handles OAuth redirects (`anivault://auth/<provider>`) and widget/notification deep-links (`anivault://entry/<id>`). */
    private fun handleIntent(i: Intent?) {
        val data = i?.dataString ?: return
        if (data.startsWith("anivault://entry/")) {
            val id = Uri.decode(data.removePrefix("anivault://entry/")).takeIf { it.isNotBlank() }
            if (id != null) deepLinkEntryId.value = id
            i.data = null
            return
        }
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
        val linkedId by deepLinkEntryId.collectAsStateWithLifecycle()
        val glass = LocalLiquidGlass.current

        LaunchedEffect(linkedId) {
            if (!linkedId.isNullOrBlank()) {
                detailId = linkedId
                deepLinkEntryId.value = null
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBar) {
                    if (glass.enabled && glass.floatingNav) {
                        val dockShape = RoundedCornerShape(28.dp)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            glass.tint.baseStart.copy(alpha = 0.78f),
                                            glass.tint.baseStart.copy(alpha = 0.94f)
                                        )
                                    )
                                )
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .liquidGlassPanel(config = glass, shape = dockShape, cornerRadiusDp = 28f, elevated = true)
                        ) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0)
                            ) {
                                tabs.forEach { t ->
                                    NavigationBarItem(
                                        selected = route == t.route,
                                        icon = { Icon(t.icon, null) },
                                        label = { Text(t.label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            indicatorColor = glass.tint.primary.copy(alpha = 0.24f),
                                            selectedIconColor = glass.tint.primary,
                                            selectedTextColor = glass.tint.primary
                                        ),
                                        onClick = { goTab(nav, if (t.route == R_.LIBRARY_ARG) R_.library(null) else t.route) }
                                    )
                                }
                            }
                        }
                    } else {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            tabs.forEach { t ->
                                NavigationBarItem(
                                    selected = route == t.route, icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                                    onClick = { goTab(nav, if (t.route == R_.LIBRARY_ARG) R_.library(null) else t.route) }
                                )
                            }
                        }
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
                        onOpenCalendar = { nav.navigate(R_.CALENDAR) },
                        onOpenEntry = { detailId = it },
                        onOpenCatalog = { catalogItem = it }
                    )
                }
                composable(R_.LIBRARY_ARG, arguments = listOf(navArgument("status") { type = NavType.StringType; defaultValue = "ALL" })) {
                    LibraryScreen(onOpenEntry = { detailId = it })
                }
                composable(R_.DISCOVER) { DiscoverScreen(onOpenSources = { nav.navigate(R_.API) }) }
                composable(R_.STATS) { StatsScreen(onBack = { nav.popBackStack() }) }
                composable(R_.CALENDAR) {
                    CalendarScreen(
                        onBack = { nav.popBackStack() },
                        onOpenEntry = { detailId = it },
                        onOpenCatalog = { catalogItem = it }
                    )
                }
                composable(R_.SETTINGS) { SettingsHomeScreen(onOpen = { nav.navigate("settings/$it") }) }
                composable(R_.API) { ApiSourcesScreen(onBack = { nav.navigateUp() }) }
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
