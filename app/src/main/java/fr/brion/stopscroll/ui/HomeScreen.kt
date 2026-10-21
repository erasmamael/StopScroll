package fr.brion.stopscroll.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import fr.brion.stopscroll.data.Settings

/**
 * Écran d'accueil : état du blocage + accès aux autres écrans.
 *
 * @param serviceEnabled le service d'accessibilité est-il activé ?
 * @param settings       réglages actuels (pour le récapitulatif des règles).
 */
@Composable
fun HomeScreen(
    serviceEnabled: Boolean,
    settings: Settings,
    onOpenExplanation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current

    ScreenScaffold(title = "StopScroll") {
        if (serviceEnabled) {
            InfoCard(
                title = "Blocage actif ✅",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Paragraph("Onglet Reels : " + if (settings.blockReelsTab) "bloqué" else "autorisé")
                Paragraph(
                    "Passage au Reel suivant : " + when {
                        !settings.blockReelScroll -> "autorisé"
                        settings.useSwipeOverlay -> "bloqué (le swipe est ignoré)"
                        else -> "bloqué (le lecteur se ferme)"
                    },
                )
                Paragraph("Les Reels que tu ouvres toi-même ou qu'on t'envoie restent visibles.")
            }
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Réglages")
            }
            OutlinedButton(
                onClick = { SystemShortcuts.openAccessibilitySettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Désactiver le service (paramètres Android)")
            }
        } else {
            InfoCard(
                title = "Blocage inactif",
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
                Paragraph(
                    "Le service d'accessibilité de StopScroll n'est pas activé : " +
                        "Instagram fonctionne normalement, sans aucun blocage.",
                )
            }
            Button(onClick = onOpenExplanation, modifier = Modifier.fillMaxWidth()) {
                Text("Activer le blocage")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Réglages")
            }
        }
    }
}
