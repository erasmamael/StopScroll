package fr.brion.stopscroll.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import fr.brion.stopscroll.BuildConfig

/**
 * Règle 3, filet de sécurité : si malgré tout on passe à un autre Reel,
 * on ferme le lecteur (touche Retour).
 *
 * Comment on détecte un changement de Reel : le lecteur est une liste
 * paginée ; à chaque défilement il envoie l'index du Reel affiché
 * (0, 1, 2…). Si l'index change, on a changé de Reel.
 *
 * Piège relevé lors des tests : à l'OUVERTURE, le lecteur se positionne
 * tout seul (ex. index 5 depuis un profil) et envoie un défilement. On
 * ignore donc les défilements pendant une courte "période d'installation"
 * après l'apparition du lecteur ; ils servent seulement à mémoriser l'index de départ.
 */
class ScrollSafetyNet(private val service: AccessibilityService) {

    private var lastIndex: Int? = null
    private var viewerVisibleSince: Long? = null
    private var lastCloseAt = 0L

    /** À appeler après chaque analyse d'écran : le lecteur est-il visible ? */
    fun onViewerVisibility(visible: Boolean) {
        if (visible && viewerVisibleSince == null) viewerVisibleSince = SystemClock.uptimeMillis()
        if (!visible) viewerVisibleSince = null
    }

    /** À appeler à chaque défilement du lecteur, avec l'index du Reel affiché. */
    fun onViewerScrolled(index: Int) {
        val now = SystemClock.uptimeMillis()
        val previous = lastIndex
        lastIndex = index

        val since = viewerVisibleSince ?: return
        if (now - since < SETTLE_MS) return              // ouverture en cours
        if (previous == null || index == previous) return // pas de changement
        if (now - lastCloseAt < CLOSE_COOLDOWN_MS) return // déjà en train de fermer

        lastCloseAt = now
        if (BuildConfig.DEBUG) Log.i(TAG, "Filet de sécurité : Reel $previous → $index, fermeture du lecteur")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private companion object {
        const val TAG = "StopScroll"
        const val SETTLE_MS = 700L
        const val CLOSE_COOLDOWN_MS = 1_000L
    }
}
