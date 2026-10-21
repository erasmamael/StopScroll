package fr.brion.stopscroll.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import fr.brion.stopscroll.service.StopScrollService

/**
 * Petits raccourcis vers le système Android, utilisés par les écrans.
 */
object SystemShortcuts {

    private fun serviceComponent(context: Context) = ComponentName(context, StopScrollService::class.java)

    /**
     * Le service est-il activé dans Paramètres > Accessibilité ?
     * Android stocke la liste des services activés dans un réglage système
     * texte ("paquet/classe:paquet/classe…") ; on y cherche le nôtre.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = serviceComponent(context)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /**
     * Ouvre directement la page de NOTRE service dans les paramètres
     * d'accessibilité (Android 13+), sinon la liste générale.
     */
    fun openAccessibilitySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Page existante dans Android mais non publiée dans le SDK : on
            // l'appelle par son nom, avec repli si le téléphone la refuse.
            val details = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, serviceComponent(context).flattenToString())
            try {
                context.startActivity(details)
                return
            } catch (_: ActivityNotFoundException) {
                // Page inconnue sur ce téléphone : repli ci-dessous.
            } catch (_: SecurityException) {
                // Page protégée sur ce téléphone : repli ci-dessous.
            }
        }
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /**
     * Ouvre la page "Infos de l'appli" de StopScroll. C'est là, via le menu ⋮,
     * qu'on autorise les "paramètres restreints" (Android 13+).
     */
    fun openAppInfo(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}
