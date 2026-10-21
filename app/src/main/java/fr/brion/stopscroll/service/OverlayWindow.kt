package fr.brion.stopscroll.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Une fenêtre transparente posée PAR-DESSUS Instagram, à une position précise.
 *
 * Notion : TYPE_ACCESSIBILITY_OVERLAY est un type de fenêtre réservé aux
 * services d'accessibilité. Il ne demande aucune permission supplémentaire
 * (contrairement aux bulles "par-dessus les autres apps") et Android le
 * considère comme fiable : il reçoit les appuis avant l'app en dessous.
 *
 * Doit être créée avec le service lui-même comme contexte (c'est lui qui
 * "possède" le droit d'afficher ce type de fenêtre).
 */
class OverlayWindow(service: AccessibilityService, private val view: View) {

    private val windowManager = service.getSystemService(WindowManager::class.java)

    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // NOT_FOCUSABLE : ne vole jamais le clavier ni le focus à Instagram.
        // LAYOUT_IN_SCREEN + NO_LIMITS : x/y sont des coordonnées absolues de l'écran,
        // les mêmes que celles lues dans l'arbre d'accessibilité.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Autorise la fenêtre à s'étendre près de l'encoche de la caméra.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    var isShown = false
        private set

    /** Rectangle actuellement occupé par la fenêtre. */
    val bounds get() = Rect(params.x, params.y, params.x + params.width, params.y + params.height)

    /**
     * Affiche la fenêtre sur ce rectangle (ou la déplace si elle est déjà affichée).
     *
     * @param onApplied appelé quand le changement est RÉELLEMENT appliqué à
     *   l'écran. Notion : un changement de taille n'est pas immédiat ; Android
     *   attend que la fenêtre soit redessinée à sa nouvelle taille, ce qui peut
     *   prendre de quelques ms à quelques centaines de ms selon la charge.
     */
    fun show(bounds: Rect, onApplied: (() -> Unit)? = null) {
        val unchanged = params.x == bounds.left && params.y == bounds.top &&
            params.width == bounds.width() && params.height == bounds.height()
        if (isShown && unchanged) {
            onApplied?.invoke()
            return
        }
        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width()
        params.height = bounds.height()
        if (isShown) {
            windowManager.updateViewLayout(view, params)
        } else {
            windowManager.addView(view, params)
            isShown = true
        }
        if (onApplied != null) notifyWhenApplied(onApplied)
    }

    /**
     * Prévient quand la prochaine image de la fenêtre a été affichée par le
     * système (Android 10+ : "frame commit"), c'est-à-dire quand la nouvelle
     * taille est en vigueur, y compris pour les appuis. Avant Android 10 :
     * simple délai prudent.
     */
    private fun notifyWhenApplied(callback: () -> Unit) {
        // view.post : attend que la fenêtre soit attachée / que le changement soit pris en compte.
        view.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.viewTreeObserver.registerFrameCommitCallback(callback)
                // Force un nouveau dessin, pour être sûr qu'une image soit produite.
                view.invalidate()
            } else {
                view.postDelayed(callback, LEGACY_APPLY_DELAY_MS)
            }
        }
    }

    private companion object {
        const val LEGACY_APPLY_DELAY_MS = 150L
    }

    fun hide() {
        if (!isShown) return
        windowManager.removeView(view)
        isShown = false
        setTouchable(true) // repart d'un état propre au prochain affichage
    }

    /**
     * touchable = false : les appuis TRAVERSENT la fenêtre et vont à Instagram.
     * Utilisé le temps de rejouer un tap vers Instagram.
     */
    fun setTouchable(touchable: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val newFlags = if (touchable) params.flags and flag.inv() else params.flags or flag
        if (newFlags == params.flags) return
        params.flags = newFlags
        if (isShown) windowManager.updateViewLayout(view, params)
    }
}
