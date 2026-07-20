//
// MainActivity.kt
// Single-activity Compose app: bottom navigation with the three iOS tabs
// (New Note / Sent / Settings). ViewModels come from the hand-wired
// AppContainer via a small factory.
//

package com.burnpony.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.burnpony.app.theme.BurnPonyMaterialTheme
import com.burnpony.app.theme.BurnPonyTheme
import com.burnpony.app.ui.compose.ComposeScreen
import com.burnpony.app.ui.compose.ComposeViewModel
import com.burnpony.app.ui.sent.SentNotesScreen
import com.burnpony.app.ui.sent.SentNotesViewModel
import com.burnpony.app.ui.settings.SettingsScreen
import com.burnpony.app.ui.settings.SettingsViewModel

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab("compose", R.string.tab_new_note, Icons.Filled.LocalFireDepartment),
    Tab("sent", R.string.tab_sent, Icons.Filled.Send),
    Tab("settings", R.string.tab_settings, Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BurnPonyApp).container
        setContent {
            BurnPonyMaterialTheme {
                MainScaffold(ViewModelFactory(container))
            }
        }
    }
}

class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        ComposeViewModel::class.java -> ComposeViewModel(container.repository) as T
        SentNotesViewModel::class.java -> SentNotesViewModel(container.repository) as T
        SettingsViewModel::class.java -> SettingsViewModel(container.settings) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}

@Composable
private fun MainScaffold(factory: ViewModelFactory) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    Scaffold(
        containerColor = BurnPonyTheme.background,
        bottomBar = {
            NavigationBar(containerColor = BurnPonyTheme.panel) {
                for (tab in TABS) {
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BurnPonyTheme.ember,
                            selectedTextColor = BurnPonyTheme.ember,
                            unselectedIconColor = BurnPonyTheme.dim,
                            unselectedTextColor = BurnPonyTheme.dim,
                            indicatorColor = BurnPonyTheme.fieldBackground,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "compose",
            modifier = Modifier.padding(padding),
        ) {
            composable("compose") {
                ComposeScreen(viewModel(factory = factory))
            }
            composable("sent") {
                SentNotesScreen(viewModel(factory = factory))
            }
            composable("settings") {
                SettingsScreen(viewModel(factory = factory))
            }
        }
    }
}
