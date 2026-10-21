package fr.brion.stopscroll.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import fr.brion.stopscroll.service.StopScrollService
import kotlinx.coroutines.delay
import java.io.File

/**
 * Outils de diagnostic (version debug uniquement).
 *
 * Export de l'arbre d'accessibilité : quand une mise à jour d'Instagram casse
 * la détection, on exporte l'arbre de l'écran concerné pour trouver les
 * nouveaux identifiants à mettre dans InstagramSelectors.kt.
 *
 * Comme l'export doit capturer INSTAGRAM (et pas cet écran), il se déclenche
 * après 5 secondes : le temps d'ouvrir Instagram sur l'écran voulu.
 */
@Composable
fun DebugSection(serviceEnabled: Boolean) {
    val context = LocalContext.current
    var secondsLeft by remember { mutableIntStateOf(0) }
    var exports by remember { mutableStateOf(listExports(context)) }

    // On rafraîchit la liste à chaque retour sur l'écran.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exports = listExports(context) }

    // Compte à rebours : LaunchedEffect lance une tâche qui se relance quand
    // "secondsLeft" change, et s'arrête si l'écran disparaît.
    LaunchedEffect(secondsLeft) {
        if (secondsLeft > 0) {
            delay(1_000)
            secondsLeft -= 1
        }
    }

    InfoCard(title = "Debug", containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
        Paragraph(
            "Exporte l'arbre d'accessibilité de l'écran Instagram affiché, pour mettre à jour " +
                "les sélecteurs après une mise à jour d'Instagram. Après l'appui, tu as " +
                "$EXPORT_DELAY_S secondes pour ouvrir Instagram sur l'écran voulu.",
        )
        Paragraph(
            "Le fichier contient les textes affichés à l'écran : évite d'exporter une " +
                "conversation privée.",
        )
        Button(
            onClick = {
                // setPackage : le message ne peut être reçu QUE par notre propre service.
                context.sendBroadcast(
                    Intent(StopScrollService.ACTION_DUMP_TREE)
                        .setPackage(context.packageName)
                        .putExtra(StopScrollService.EXTRA_DELAY_MS, EXPORT_DELAY_S * 1_000L),
                )
                secondsLeft = EXPORT_DELAY_S
            },
            enabled = serviceEnabled && secondsLeft == 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    !serviceEnabled -> "Service inactif : export impossible"
                    secondsLeft > 0 -> "Export dans $secondsLeft s… ouvre Instagram !"
                    else -> "Exporter l'arbre dans $EXPORT_DELAY_S s"
                },
            )
        }

        val latest = exports.firstOrNull()
        if (latest == null) {
            Paragraph("Aucun export pour l'instant.")
        } else {
            Paragraph("${exports.size} export(s). Dernier : ${latest.name}")
            OutlinedButton(
                onClick = { shareFile(context, latest) },
                colors = cardButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Partager le dernier export")
            }
            OutlinedButton(
                onClick = {
                    exports.forEach(File::delete)
                    exports = listExports(context)
                },
                colors = cardButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Supprimer tous les exports")
            }
        }
    }
}

private const val EXPORT_DELAY_S = 5

/** Bouton à contour lisible sur le fond coloré de la carte : il reprend la couleur du texte de la carte. */
@Composable
private fun cardButtonColors() = ButtonDefaults.outlinedButtonColors(contentColor = LocalContentColor.current)

/** Exports existants, du plus récent au plus ancien (le nom contient la date). */
private fun listExports(context: Context): List<File> =
    File(context.filesDir, "dumps").listFiles()
        ?.filter { it.name.endsWith(".txt") }
        ?.sortedByDescending { it.name }
        .orEmpty()

/**
 * Ouvre le menu de partage Android avec le fichier.
 * Notion : un fichier privé de l'app n'est lisible par aucune autre app. Le
 * FileProvider (déclaré dans le manifeste debug) crée un lien temporaire
 * que seule l'app choisie dans le menu pourra lire.
 */
private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.debugfiles", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Partager l'export"))
}
