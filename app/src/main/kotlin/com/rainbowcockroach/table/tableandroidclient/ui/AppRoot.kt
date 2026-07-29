package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rainbowcockroach.table.tableandroidclient.AppContainer

private enum class Screen { MAIN, SETTINGS }

/** The two screens of DESIGN §5; too few to be worth a navigation library. */
@Composable
fun AppRoot(container: AppContainer) {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    val factory = remember(container) {
        viewModelFactory {
            initializer { MainViewModel(container) }
            initializer { SettingsViewModel(container) }
        }
    }
    when (screen) {
        Screen.MAIN -> MainScreen(
            viewModel = viewModel<MainViewModel>(factory = factory),
            onOpenSettings = { screen = Screen.SETTINGS },
        )

        Screen.SETTINGS -> {
            BackHandler { screen = Screen.MAIN }
            SettingsScreen(
                viewModel = viewModel<SettingsViewModel>(factory = factory),
                onBack = { screen = Screen.MAIN },
            )
        }
    }
}
