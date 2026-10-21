package fr.brion.stopscroll.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Un court message en bas de l'écran, à la manière d'un Toast.
 *
 * Pourquoi pas un vrai Toast ? Depuis Android 13, une app sans autorisation
 * de notifications ne peut pas afficher de Toast quand elle n'est pas au
 * premier plan (c'est notre cas : Instagram est au premier plan). Une
 * fenêtre d'overlay d'accessibilité n'a pas cette limite et ne demande
 * aucune permission.
 *
 * La fenêtre est "traversable" : elle n'intercepte aucun appui.
 */
class MessageOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())

    private val textView = TextView(service).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val padH = dp(20)
        val padV = dp(12)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            setColor(Color.argb(230, 40, 40, 40))
            cornerRadius = dp(24).toFloat()
        }
    }

    // Conteneur pleine largeur qui centre la bulle de texte.
    private val container = FrameLayout(service).apply {
        addView(
            textView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
    }

    private val window = OverlayWindow(service, container)

    fun show(message: CharSequence) {
        textView.text = message
        val metrics = service.resources.displayMetrics
        val height = dp(80)
        val top = metrics.heightPixels - dp(BOTTOM_OFFSET_DP) - height
        // Avant show() : hide() remet la fenêtre "touchable" à chaque fois.
        window.setTouchable(false)
        window.show(Rect(0, top, metrics.widthPixels, top + height))

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ window.hide() }, DURATION_MS)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        window.hide()
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val DURATION_MS = 2_000L
        // Au-dessus de la barre de navigation d'Instagram.
        const val BOTTOM_OFFSET_DP = 110
    }
}
