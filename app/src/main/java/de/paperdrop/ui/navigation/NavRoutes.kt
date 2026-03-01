package de.paperdrop.ui.navigation

sealed class Screen(val route: String) {
    object History  : Screen("history")
    object Settings : Screen("settings")
}
