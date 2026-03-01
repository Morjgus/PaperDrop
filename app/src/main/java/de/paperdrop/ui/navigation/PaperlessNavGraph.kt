package de.paperdrop.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import de.paperdrop.ui.history.HistoryScreen
import de.paperdrop.ui.settings.SettingsScreen

@Composable
fun PaperlessNavGraph(navController: NavHostController) {
    NavHost(
        navController    = navController,
        startDestination = Screen.History.route
    ) {
        composable(Screen.History.route)  { HistoryScreen() }
        composable(Screen.Settings.route) { SettingsScreen() }
    }
}
