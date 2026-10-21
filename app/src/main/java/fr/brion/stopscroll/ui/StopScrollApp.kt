package fr.brion.stopscroll.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.brion.stopscroll.data.Settings
import fr.brion.stopscroll.data.SettingsRepository

/** Les écrans de l'app. */
private enum class Screen { HOME, EXPLANATION, SETTINGS }

/**
 * Racine de l'interface : choisit l'écran à afficher.
 *
 * Navigation volontairement simple : une variable "écran courant" et le
 * bouton Retour d'Android pour revenir à l'accueil. Pas besoin de
 * bibliothèque de navigation pour 3 écrans.
 */
@Composable
fun StopScrollApp() {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }

    // rememberSaveable : l'écran courant survit à une rotation de l'écran.
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    // Réglages : flux DataStore transformé en "état" Compose. L'interface se
    // redessine toute seule quand un réglage change.
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = Settings())

    // État du service, revérifié à chaque retour dans l'app (ex. après être
    // passé par les paramètres d'accessibilité).
    var serviceEnabled by remember { mutableStateOf(SystemShortcuts.isServiceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        serviceEnabled = SystemShortcuts.isServiceEnabled(context)
        // Service activé depuis l'écran d'explication : on revient à l'accueil.
        if (serviceEnabled && screen == Screen.EXPLANATION) screen = Screen.HOME
    }

    // Bouton Retour d'Android : revient à l'accueil depuis les autres écrans.
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    StopScrollTheme {
        when (screen) {
            Screen.HOME -> HomeScreen(
                serviceEnabled = serviceEnabled,
                settings = settings,
                onOpenExplanation = { screen = Screen.EXPLANATION },
                onOpenSettings = { screen = Screen.SETTINGS },
            )
            Screen.EXPLANATION -> ExplanationScreen(onBack = { screen = Screen.HOME })
            Screen.SETTINGS -> SettingsScreen(
                settings = settings,
                repository = repository,
                serviceEnabled = serviceEnabled,
                onBack = { screen = Screen.HOME },
            )
        }
    }
}
