package fr.brion.stopscroll.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import fr.brion.stopscroll.BuildConfig
import fr.brion.stopscroll.R

/**
 * Règle 1 : l'onglet Reels est inaccessible.
 *
 * Deux niveaux :
 *  1. Un "bouclier" : petit overlay invisible posé exactement sur le bouton
 *     Reels de la barre du bas. Ton appui touche le bouclier, pas Instagram :
 *     l'onglet ne s'ouvre jamais et aucun Reel ne démarre. On affiche juste
 *     le Toast ; tu restes sur l'écran où tu étais.
 *  2. Un repli : si l'onglet Reels s'ouvre malgré tout (bouclier pas encore
 *     posé, changement d'interface…), on clique sur Accueil à ta place.
 */
@SuppressLint("ClickableViewAccessibility")
class ReelsTabBlocker(private val service: AccessibilityService) {

    private val shieldView = View(service).apply {
        // En debug : voile rouge pour VOIR le bouclier. Invisible en release.
        setBackgroundColor(if (BuildConfig.DEBUG) Color.argb(60, 255, 0, 0) else Color.TRANSPARENT)
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) showBlockedToast()
            true // l'appui est consommé : Instagram ne le reçoit pas
        }
    }
    private val shield = OverlayWindow(service, shieldView)
    private val message = MessageOverlay(service)

    // Heure (en ms depuis le démarrage du téléphone) du dernier Toast.
    private var lastToastAt = 0L

    val isShieldShown get() = shield.isShown

    /** Pose le bouclier sur l'onglet Reels (ou le retire si l'onglet n'est pas à l'écran). */
    fun updateShield(tabBounds: Rect?) {
        if (tabBounds == null) shield.hide() else shield.show(tabBounds)
    }

    fun hideShield() = shield.hide()

    /** Retire tout (bouclier + message), ex. quand on quitte Instagram. */
    fun hideAll() {
        shield.hide()
        message.hide()
    }

    /** Repli : l'onglet Reels s'est ouvert, on clique sur Accueil à ta place. */
    fun redirectToHome(root: AccessibilityNodeInfo?) {
        // Anti-rebond : une seule redirection par seconde, même si plusieurs
        // événements signalent l'onglet Reels en même temps.
        if (!showBlockedToast()) return

        // performAction(ACTION_CLICK) = simuler un appui sur cet élément.
        val clicked = root?.let(ScreenDetector::findHomeTab)
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        // Repli si l'onglet Accueil est introuvable : touche Retour, qui ramène
        // normalement Instagram sur l'Accueil depuis un autre onglet.
        if (!clicked) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /** Affiche le message, au plus une fois par seconde. Renvoie false si ignoré. */
    private fun showBlockedToast(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastToastAt < COOLDOWN_MS) return false
        lastToastAt = now
        message.show(service.getString(R.string.toast_reels_tab_blocked))
        return true
    }

    private companion object {
        const val COOLDOWN_MS = 1_000L
    }
}
