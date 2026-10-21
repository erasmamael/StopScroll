package fr.brion.stopscroll.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.brion.stopscroll.BuildConfig
import fr.brion.stopscroll.data.Settings
import fr.brion.stopscroll.data.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Réglages : chaque règle s'active / se désactive séparément.
 * Les changements sont enregistrés aussitôt (DataStore) et le service les
 * applique immédiatement, sans redémarrage.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    repository: SettingsRepository,
    serviceEnabled: Boolean,
    onBack: () -> Unit,
) {
    // Les écritures DataStore sont asynchrones ("suspend") : on les lance
    // dans une coroutine liée à cet écran.
    val scope = rememberCoroutineScope()

    ScreenScaffold(title = "Réglages", onBack = onBack) {
        InfoCard {
            SwitchRow(
                title = "Bloquer l'onglet Reels",
                subtitle = "Appuyer sur l'onglet Reels n'a plus d'effet.",
                checked = settings.blockReelsTab,
                onCheckedChange = { scope.launch { repository.setBlockReelsTab(it) } },
            )
        }

        InfoCard {
            SwitchRow(
                title = "Bloquer le passage au Reel suivant",
                subtitle = "Depuis un Reel ouvert, impossible de passer au suivant ou au précédent.",
                checked = settings.blockReelScroll,
                onCheckedChange = { scope.launch { repository.setBlockReelScroll(it) } },
            )
            if (settings.blockReelScroll) {
                Text("Méthode", style = MaterialTheme.typography.labelLarge)
                RadioRow(
                    title = "Ignorer le swipe (recommandé)",
                    subtitle = "Le Reel reste affiché, le swipe ne fait rien.",
                    selected = settings.useSwipeOverlay,
                    onSelect = { scope.launch { repository.setUseSwipeOverlay(true) } },
                )
                RadioRow(
                    title = "Fermer le lecteur",
                    subtitle = "Plus simple et plus robuste : le swipe passe, puis le lecteur se " +
                        "ferme aussitôt. Utile si l'autre méthode pose problème après une mise " +
                        "à jour d'Instagram.",
                    selected = !settings.useSwipeOverlay,
                    onSelect = { scope.launch { repository.setUseSwipeOverlay(false) } },
                )
            }
        }

        // Outils de diagnostic : seulement dans la version debug.
        if (BuildConfig.DEBUG) {
            DebugSection(serviceEnabled = serviceEnabled)
        }
    }
}

/** Une ligne "titre + description + interrupteur", entièrement cliquable. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        // onCheckedChange = null : c'est la ligne entière qui gère le clic.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Une option à choix unique (bouton radio). */
@Composable
private fun RadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
