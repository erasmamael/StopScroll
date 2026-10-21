package fr.brion.stopscroll.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Écran affiché AVANT l'activation : ce que le service lit, pourquoi, ce
 * qu'il fait, et comment l'activer (y compris les "paramètres restreints").
 */
@Composable
fun ExplanationScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    ScreenScaffold(title = "Avant d'activer", onBack = onBack) {
        InfoCard(title = "Ce que fait StopScroll") {
            Paragraph("• L'onglet Reels d'Instagram devient inaccessible.")
            Paragraph(
                "• Un Reel que tu ouvres (message, fil, Explorer, profil) reste visible, " +
                    "mais impossible de passer au suivant ou au précédent.",
            )
            Paragraph("• Le reste d'Instagram fonctionne normalement.")
        }

        InfoCard(title = "Ce que le service lit, et pourquoi") {
            Paragraph(
                "Android appelle ce type de service un « service d'accessibilité ». " +
                    "Il permet à StopScroll de lire la structure de l'écran d'Instagram " +
                    "(boutons, identifiants, textes affichés) pour reconnaître l'onglet " +
                    "Reels et le lecteur de Reels.",
            )
            Paragraph(
                "Le service est limité à Instagram : il ne reçoit rien des autres apps, " +
                    "sauf, pendant qu'un blocage est affiché, le simple fait que tu " +
                    "changes d'app (pour retirer le blocage aussitôt).",
            )
            Paragraph(
                "Pour bloquer le défilement, il pose un calque transparent sur le Reel " +
                    "et retransmet tes appuis (tap, double-tap, boutons) à Instagram.",
            )
        }

        InfoCard(title = "Confidentialité") {
            Paragraph(
                "• Aucune donnée ne quitte le téléphone : l'app n'a même pas la " +
                    "permission d'accéder à Internet.",
            )
            Paragraph("• Rien de ce qui est lu n'est enregistré. Seuls tes réglages sont stockés.")
            Paragraph("• Aucune statistique, aucune publicité, aucun compte.")
        }

        InfoCard(title = "Comment l'activer") {
            Paragraph("1. Appuie sur le bouton ci-dessous.")
            Paragraph("2. Choisis « StopScroll – blocage des Reels » et active l'interrupteur.")
            Paragraph("3. Accepte l'avertissement d'Android (il est standard pour ce type de service).")
            Paragraph(
                "Interrupteur grisé ou « Paramètre restreint » ? Android protège les apps " +
                    "installées hors Play Store. Ouvre « Infos de l'appli », menu ⋮ en haut " +
                    "à droite → « Autoriser les paramètres restreints », puis recommence.",
            )
        }

        Button(
            onClick = { SystemShortcuts.openAccessibilitySettings(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ouvrir les paramètres d'accessibilité")
        }
        OutlinedButton(
            onClick = { SystemShortcuts.openAppInfo(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Infos de l'appli (paramètres restreints)")
        }
    }
}
