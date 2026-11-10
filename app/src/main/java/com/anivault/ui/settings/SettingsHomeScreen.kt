package com.anivault.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anivault.ui.common.SettingNavRow
import com.anivault.ui.common.bottomTabContentPadding
import com.anivault.ui.theme.dim

private data class Category(val title: String, val subtitle: String, val icon: ImageVector, val route: String)

@Composable
fun SettingsHomeScreen(onOpen: (String) -> Unit) {
    val cats = listOf(
        Category("Account & Sync", "MAL · AniList · Kitsu", Icons.Default.Person, "account"),
        Category("API Sources", "Manage anime data providers", Icons.Default.Dns, "api"),
        Category("Library & Tracking", "Progress, status, and updates", Icons.Default.Bookmarks, "library"),
        Category("Reader / Player", "Display, gestures, and behavior", Icons.Default.PlayCircle, "player"),
        Category("Backup & Restore", "Export or import your data", Icons.Default.Backup, "backup"),
        Category("H-Vault", "Biometric lock and privacy", Icons.Default.Lock, "vault"),
        Category("Appearance", "Theme, Material You, and UI", Icons.Default.Palette, "appearance"),
        Category("Advanced", "Network, cache, and debugging", Icons.Default.Tune, "advanced")
    ) + (if (com.anivault.BuildConfig.DEBUG) listOf(Category("Debug log", "View, save and share this app's logcat", Icons.Default.BugReport, "debuglog")) else emptyList())
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(title = { Text("Settings", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 16.dp, bottom = bottomTabContentPadding(28.dp)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("PERSONALIZE YOUR VAULT", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            cats.forEach { c ->
                item(key = c.route) { SettingNavRow(c.icon, c.title, c.subtitle, { onOpen(c.route) }, highlight = c.route == "api") }
            }
            item { Text("AniVault · Anime Tracker & Vault", color = MaterialTheme.colorScheme.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp)) }
        }
    }
}
