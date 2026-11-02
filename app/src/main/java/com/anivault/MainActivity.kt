package com.anivault

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anivault.ui.dashboard.DashboardRoot
import com.anivault.ui.search.SearchRoot
import com.anivault.ui.theme.AppMode
import dagger.hilt.android.AndroidEntryPoint

private object Routes {
    const val DASHBOARD = "dashboard"
    const val SEARCH    = "search/{mode}"   // mode = "ANIME" | "H_VAULT"
    fun search(mode: AppMode) = "search/${mode.name}"
}

/**
 * Single-Activity entry point.
 * Uses FragmentActivity (instead of ComponentActivity) so that
 * BiometricPrompt can obtain a FragmentManager reference.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val navController = rememberNavController()

            NavHost(
                navController    = navController,
                startDestination = Routes.DASHBOARD
            ) {
                composable(Routes.DASHBOARD) {
                    DashboardRoot(
                        onSearchClick = { mode ->
                            navController.navigate(Routes.search(mode))
                        }
                    )
                }

                composable(Routes.SEARCH) { backStack ->
                    val modeName = backStack.arguments?.getString("mode") ?: AppMode.ANIME.name
                    val mode     = runCatching { AppMode.valueOf(modeName) }.getOrDefault(AppMode.ANIME)
                    SearchRoot(
                        mode   = mode,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
