//
// MainActivity.kt
// Single-activity Compose app: bottom navigation with the three iOS tabs
// (New Note / Sent / Settings). ViewModels come from the hand-wired
// AppContainer via a small factory.
//

package com.burnpony.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.burnpony.app.i18n.LanguageManager
import com.burnpony.app.i18n.LanguageState
import com.burnpony.app.theme.BurnPonyMaterialTheme
import com.burnpony.app.theme.BurnPonyTheme
import com.burnpony.app.ui.compose.ComposeScreen
import com.burnpony.app.ui.compose.ComposeViewModel
import com.burnpony.app.ui.legal.LegalDocument
import com.burnpony.app.ui.legal.LegalScreen
import com.burnpony.app.ui.onboarding.OnboardingScreen
import com.burnpony.app.ui.onboarding.OnboardingViewModel
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

// AppCompatActivity, not ComponentActivity: below API 33 only AppCompat
// activities auto-recreate when AppCompatDelegate.setApplicationLocales
// changes the per-app language, and without that recreation the language
// picker would appear to do nothing on most of our minSdk 24 range.
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-sync the language mirror from THIS Activity's configuration.
        // AppCompatDelegate.getApplicationLocales() is still empty at
        // Application.onCreate on API < 33 (it is populated when the first
        // AppCompatActivity attaches), so the bootstrap in BurnPonyApp can
        // report the device language while AppCompat has already applied the
        // user's saved one - checkmark on English, UI in Spanish. The
        // Activity config reflects what was actually applied, on every API
        // level, and also catches a change made from system Settings.
        LanguageState.current.value = LanguageManager.detectInitialLanguage(this).tag
        // Dark bars unconditionally: bare enableEdgeToEdge() picks icon
        // colour from the SYSTEM light/dark setting, but BurnPony is dark-only
        // on both platforms, so a phone in light mode got dark status-bar
        // icons on a near-black background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as BurnPonyApp).container
        setContent {
            BurnPonyMaterialTheme {
                val factory = remember { ViewModelFactory(container) }
                // The walkthrough is drawn OVER the live scaffold rather than
                // routed to, so skipping it lands the user on a New Note
                // screen that is already composed and already scrolled home.
                val onboardingCompleted by container.settings.onboardingCompleted
                    .collectAsStateWithLifecycle()
                Box(modifier = Modifier.fillMaxSize()) {
                    // The scaffold is hidden from accessibility while the tour
                    // is up. isTraversalGroup on the overlay would not do it:
                    // that only orders traversal within a group, so TalkBack
                    // would still walk through into the tab bar behind.
                    Box(
                        modifier = if (!onboardingCompleted) {
                            Modifier.semantics { hideFromAccessibility() }
                        } else {
                            Modifier
                        }
                    ) {
                        MainScaffold(factory)
                    }
                    if (!onboardingCompleted) {
                        OnboardingScreen(
                            viewModel = viewModel<OnboardingViewModel>(factory = factory),
                            onComplete = { container.settings.setOnboardingCompleted(true) },
                        )
                    }
                }
            }
        }
    }
}

class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        ComposeViewModel::class.java -> ComposeViewModel(container.repository, container.settings) as T
        SentNotesViewModel::class.java -> SentNotesViewModel(container.repository) as T
        SettingsViewModel::class.java -> SettingsViewModel(container.settings, container.appContext) as T
        OnboardingViewModel::class.java -> OnboardingViewModel(container.repository) as T
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
                SettingsScreen(
                    viewModel = viewModel(factory = factory),
                    onOpenLegal = { document ->
                        navController.navigate("legal/" + document.route)
                    },
                )
            }
            // Legal documents are pushed onto the Settings tab rather than
            // opened as a dialog: they are long, they need their own scroll
            // position, and system back should return to Settings, not close
            // the app.
            composable("legal/{doc}") { entry ->
                LegalScreen(
                    document = LegalDocument.fromRoute(entry.arguments?.getString("doc")),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
