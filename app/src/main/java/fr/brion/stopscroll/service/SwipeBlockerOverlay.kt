package fr.brion.stopscroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import fr.brion.stopscroll.BuildConfig
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Règle 3, stratégie principale : un overlay transparent posé sur le lecteur
 * de Reels, qui trie TES gestes.
 *
 *  - tout glissé (vertical, horizontal, diagonal) → absorbé, rien ne se passe ;
 *    seule exception : un glissé horizontal sur la barre de lecture (avancer
 *    / reculer dans la vidéo), rejoué à plat ;
 *  - geste à deux doigts → absorbé ;
 *  - appui long          → absorbé (voir plus bas pourquoi) ;
 *  - tap sur un bouton   → rejoué immédiatement vers Instagram ;
 *  - tap sur la vidéo    → on attend un court instant un éventuel second tap :
 *      · second tap → on envoie à Instagram un double-tap complet (J'aime) ;
 *      · sinon      → on envoie le tap simple (pause / lecture).
 *
 * Notion : un overlay ne peut pas "laisser passer" un geste après l'avoir
 * reçu. Pour le rejouer, on ouvre un petit trou dans l'overlay à l'endroit
 * voulu, puis on demande à Android de simuler le geste (dispatchGesture)
 * dans ce trou. Le geste simulé atteint alors Instagram, sous l'overlay,
 * tandis que tout le reste du lecteur reste bloqué.
 *
 * Appui long : impossible à transmettre fidèlement. Android annule un geste
 * simulé dès que le vrai doigt bouge (même d'un micro-mouvement) : on ne peut
 * donc pas "tenir" l'appui pendant que tu le tiens. Le rejouer après coup
 * produirait une pause décalée et déroutante ; on préfère l'ignorer.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class SwipeBlockerOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())

    private val config = ViewConfiguration.get(service)

    // Distance (en pixels) au-delà de laquelle un doigt qui bouge n'est plus un tap.
    private val touchSlop = config.scaledTouchSlop

    // Distance maximale entre les deux taps d'un double-tap.
    private val doubleTapSlop = config.scaledDoubleTapSlop

    // Durée au-delà de laquelle un appui immobile est un "appui long" (≈ 400 ms).
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    // Marge autour du point rejoué, en pixels (≈ 4 dp). Le geste simulé tombe au
    // pixel près : un trou minuscule suffit, et limite le risque qu'un swipe y démarre.
    private val holeMargin = (4 * service.resources.displayMetrics.density).toInt()

    /**
     * L'overlay est fait de 4 "panneaux" (4 petites fenêtres). Normalement
     * le premier couvre tout le lecteur et les autres sont masqués. Pour un
     * rejeu, ils se disposent AUTOUR d'un petit trou là où le geste sera
     * rejoué : seul ce trou laisse passer les appuis, tout le reste reste
     * bloquant (un swipe qui démarre ailleurs est toujours absorbé).
     *
     *   ┌───────────────┐
     *   │    dessus     │
     *   ├────┬───┬──────┤
     *   │gau.│ ● │droite│   ● = trou (point rejoué + marge), fermé par le "bouchon"
     *   ├────┴───┴──────┤
     *   │    dessous    │
     *   └───────────────┘
     */
    private val panes = List(4) { OverlayWindow(service, newPaneView()) }

    /**
     * Le "bouchon" : un 5e panneau qui ferme le trou tant que le rejeu n'a pas
     * commencé. Pourquoi ? Juste après un toucher, Android accélère le
     * processeur ; 300 ms plus tard (fin de l'attente du double-tap), notre
     * service en arrière-plan redevient lent à redimensionner ses fenêtres
     * (mesuré : 3 relances systématiques). On dispose donc les panneaux tout
     * de suite, pendant l'accélération, trou bouché ; au moment du rejeu, il
     * suffit de RETIRER le bouchon, opération quasi instantanée.
     */
    private val plug = OverlayWindow(service, newPaneView())

    private fun newPaneView() = object : View(service) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            handleTouch(event)
            return true // true = "j'ai consommé cet appui" : Instagram ne le reçoit pas.
        }
    }.apply {
        // En debug : léger voile vert pour VOIR où est l'overlay. Invisible en release.
        setBackgroundColor(if (BuildConfig.DEBUG) Color.argb(28, 0, 200, 83) else Color.TRANSPARENT)
    }

    // Rectangle total à couvrir, et trou éventuel (préparé ou ouvert).
    private var bounds = Rect()
    private var hole: Rect? = null

    // Zones du lecteur actuel (voir ReelViewer).
    private var buttonAreas: List<Rect> = emptyList()
    private var seekArea: Rect? = null

    // État du geste en cours.
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var kind = GestureKind.UNDECIDED
    private var multiTouch = false

    /** Premier tap sur la vidéo, en attente d'un éventuel second tap. */
    private class PendingTap(val x: Float, val y: Float, val downTime: Long, val upTime: Long)
    // (@Volatile sur les 3 champs suivants : ils sont lus aussi par le fil d'analyse.)
    @Volatile private var pendingTap: PendingTap? = null
    private val flushPendingTap = Runnable { sendPendingTapAlone() }

    // Vrai tant qu'un doigt est posé sur l'overlay.
    @Volatile private var touching = false

    // Vrai pendant qu'un geste simulé est en vol (de l'envoi à sa fin).
    @Volatile private var replaying = false

    /**
     * Vrai dès que tu touches l'overlay et jusqu'à la fin du rejeu. Le service
     * suspend alors son analyse d'écran : les requêtes d'accessibilité passent
     * par le même serveur système que les changements de fenêtres, et les
     * ralentissaient (mesuré : trou parfois ouvert en 300 ms au lieu de 60).
     * Sans risque : tant que ton doigt est sur l'overlay, Instagram ne reçoit
     * rien, donc l'écran ne peut pas changer.
     */
    val isBusy get() = touching || pendingTap != null || replaying

    // Vrai si le geste rejoué est retombé sur l'overlay au lieu d'Instagram.
    private var bounced = false

    val isShown get() = panes.any { it.isShown }

    fun show(viewer: ReelViewer) {
        buttonAreas = viewer.buttonAreas
        seekArea = viewer.seekArea
        bounds = viewer.viewerBounds
        layoutPanes()
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        pendingTap = null
        touching = false
        replaying = false
        hole = null
        panes.forEach { it.hide() }
        plug.hide()
    }

    /** Place les 4 panneaux : un seul plein écran, ou 4 autour du trou. */
    private fun layoutPanes() {
        val b = bounds
        val h = hole?.let { Rect(it).apply { intersect(b) } }
        val rects = if (h == null) {
            listOf(b, Rect(), Rect(), Rect())
        } else {
            listOf(
                Rect(b.left, b.top, b.right, h.top),       // dessus
                Rect(b.left, h.bottom, b.right, b.bottom), // dessous
                Rect(b.left, h.top, h.left, h.bottom),     // gauche
                Rect(h.right, h.top, b.right, h.bottom),   // droite
            )
        }
        // Les 4 panneaux restent TOUJOURS attachés ; ceux qui ne servent pas
        // sont "garés" hors de l'écran (créer une fenêtre est plus lent que la
        // redimensionner).
        //
        // Ordre sûr, en deux temps, pour ne JAMAIS laisser de trou involontaire :
        //  1. on agrandit / place les panneaux qui doivent couvrir ;
        //  2. on attend la confirmation qu'ils sont à l'écran, PLUS une marge,
        //     et seulement ensuite on rétrécit / gare les autres.
        // Mesuré : sans attente, les redimensionnements s'appliquaient dans le
        // désordre ; et la confirmation d'Android arrive un peu AVANT que les
        // zones tactiles soient à jour, d'où la marge. Un swipe pouvait passer
        // par un trou de quelques ms.
        val targets = rects.map { if (it.isEmpty) PARKED else it }
        val growing = targets.indices.filter { !panes[it].isShown || targets[it].area() >= panes[it].bounds.area() }
        val shrinking = targets.indices.filter { it !in growing }

        // Marge après confirmation :
        //  - ouverture d'un trou : courte, c'est elle qui retarde le rejeu ;
        //  - rebouchage : rien ne presse pour garer les panneaux superflus
        //    (ils se superposent sans danger), on prend donc large.
        val margin = if (h == null) SPARE_PARK_DELAY_MS else CONFIRM_MARGIN_MS

        val generation = ++layoutGeneration
        var waiting = growing.size
        var shrunk = false
        val shrinkOthers = {
            // Ignoré si une disposition plus récente a été demandée entre-temps.
            if (!shrunk && generation == layoutGeneration) {
                shrunk = true
                shrinking.forEach { panes[it].show(targets[it]) }
                // Trou rebouché par le panneau principal : le bouchon ne sert plus.
                if (h == null) plug.hide()
            }
        }
        if (shrinking.isEmpty() && (h != null || !plug.isShown)) {
            growing.forEach { panes[it].show(targets[it]) }
        } else if (waiting == 0) {
            handler.postDelayed(shrinkOthers, margin)
        } else {
            growing.forEach { i ->
                panes[i].show(targets[i]) { if (--waiting == 0) handler.postDelayed(shrinkOthers, margin) }
            }
            // Filet : si une confirmation ne venait jamais, on finit quand même.
            handler.postDelayed(shrinkOthers, LAYOUT_CONFIRM_TIMEOUT_MS + margin)
        }
    }

    // Numéro de la dernière disposition demandée (voir layoutPanes).
    private var layoutGeneration = 0

    private fun Rect.area() = width().toLong() * height()

    // ── Tri des gestes ──────────────────────────────────────────────────────

    private enum class GestureKind { UNDECIDED, VERTICAL, HORIZONTAL }

    private fun handleTouch(event: MotionEvent) {
        // Pendant un rejeu, on ne traite aucun nouvel appui :
        //  - dans le trou, c'est le geste simulé qui est retombé sur l'overlay
        //    (le système n'avait pas encore appliqué le trou) → on le relancera ;
        //  - ailleurs, c'est ton doigt → simplement absorbé.
        // Jamais rejoué lui-même (ce qui bouclerait).
        if (replaying) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                hole?.contains(event.rawX, event.rawY) == true
            ) {
                bounced = true
            }
            return
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                // rawX/rawY = coordonnées sur l'ÉCRAN (et non dans l'overlay).
                downX = event.rawX
                downY = event.rawY
                downTime = event.eventTime
                kind = GestureKind.UNDECIDED
                multiTouch = false
                // Un second appui arrive : on attend de savoir si c'est un tap
                // avant d'envoyer le premier.
                if (pendingTap != null) handler.removeCallbacks(flushPendingTap)
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
            MotionEvent.ACTION_MOVE -> {
                if (kind == GestureKind.UNDECIDED) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (hypot(dx, dy) > touchSlop) {
                        kind = if (abs(dy) >= abs(dx)) GestureKind.VERTICAL else GestureKind.HORIZONTAL
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                onGestureEnd(event)
                touching = false
            }
            MotionEvent.ACTION_CANCEL -> {
                sendPendingTapAlone()
                touching = false
            }
        }
    }

    private fun onGestureEnd(up: MotionEvent) {
        val isTap = !multiTouch && kind == GestureKind.UNDECIDED &&
            up.eventTime - downTime < longPressTimeout

        if (!isTap) {
            when {
                !multiTouch && kind == GestureKind.HORIZONTAL && seekArea?.contains(downX, downY) == true ->
                    // Avance / recul dans la vidéo, rejoué à plat (aucune composante verticale).
                    replayAfterPendingTap(
                        listOf(PointF(downX, downY), PointF(up.rawX, downY)),
                        seekStroke(downX, downY, up.rawX),
                    )
                else -> {
                    // Un premier tap en attente n'aura pas de second tap : on l'envoie seul.
                    sendPendingTapAlone()
                    log(if (kind == GestureKind.UNDECIDED && !multiTouch) "Appui long absorbé" else "Glissé absorbé")
                }
            }
            return
        }

        val first = pendingTap
        when {
            // Second tap proche du premier, dans le délai : c'est un double-tap.
            first != null && downTime - first.upTime <= ViewConfiguration.getDoubleTapTimeout() &&
                hypot(downX - first.x, downY - first.y) <= doubleTapSlop -> {
                pendingTap = null
                replayDoubleTap(first, downX, downY, gapMs = downTime - first.downTime)
            }
            // Tap sur un bouton : pas de double-tap possible, on l'envoie tout de suite.
            buttonAreas.any { it.contains(downX, downY) } ->
                replayAfterPendingTap(listOf(PointF(downX, downY)), tapStroke(downX, downY))
            // Tap sur la vidéo : on attend un éventuel second tap.
            else -> {
                sendPendingTapAlone()
                pendingTap = PendingTap(downX, downY, downTime, up.eventTime)
                handler.postDelayed(flushPendingTap, ViewConfiguration.getDoubleTapTimeout().toLong())
                // On prépare le trou dès maintenant, pendant que le système est rapide.
                prepareHole(listOf(PointF(downX, downY)))
            }
        }
    }

    // ── Rejeu vers Instagram ────────────────────────────────────────────────

    /** Récupère (et retire) le premier tap en attente, s'il y en a un. */
    private fun takePendingTap(): PendingTap? {
        handler.removeCallbacks(flushPendingTap)
        return pendingTap.also { pendingTap = null }
    }

    private fun sendPendingTapAlone() {
        val tap = takePendingTap() ?: return
        replay(listOf(PointF(tap.x, tap.y)), listOf(Step(tapStroke(tap.x, tap.y))))
    }

    /**
     * Envoie un geste, précédé du tap en attente s'il y en a un.
     *
     * @param points les points touchés par ce geste (pour placer le trou).
     */
    private fun replayAfterPendingTap(points: List<PointF>, stroke: GestureDescription.StrokeDescription) {
        val pending = takePendingTap()
        if (pending == null) {
            replay(points, listOf(Step(stroke)))
        } else {
            replay(
                points + PointF(pending.x, pending.y),
                listOf(Step(tapStroke(pending.x, pending.y)), Step(stroke, delayAfterPreviousMs = SEQUENCE_GAP_MS)),
            )
        }
    }

    /** Deux taps successifs, espacés comme les tiens. */
    private fun replayDoubleTap(first: PendingTap, x2: Float, y2: Float, gapMs: Long) {
        // gapMs = écart entre tes deux appuis ; on retire la durée du premier tap simulé.
        val gap = gapMs.coerceIn(MIN_DOUBLE_TAP_GAP_MS, MAX_DOUBLE_TAP_GAP_MS) - TAP_STROKE_MS
        replay(
            listOf(PointF(first.x, first.y), PointF(x2, y2)),
            listOf(Step(tapStroke(first.x, first.y)), Step(tapStroke(x2, y2), delayAfterPreviousMs = gap)),
        )
        log("Double-tap rejoué (écart $gap ms)")
    }

    /**
     * Glissé horizontal à plat (même hauteur au départ et à l'arrivée).
     * Rejoué en SEEK_REPLAY_MS quelle que soit ta vitesse : seule la position
     * d'arrivée compte pour la barre de lecture, autant y aller vite.
     */
    private fun seekStroke(fromX: Float, y: Float, toX: Float): GestureDescription.StrokeDescription {
        val path = Path().apply {
            moveTo(fromX, y)
            lineTo(toX, y)
        }
        return GestureDescription.StrokeDescription(path, 0, SEEK_REPLAY_MS)
    }

    private fun tapStroke(x: Float, y: Float) =
        GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, TAP_STROKE_MS)

    /**
     * Une étape du rejeu : un geste simple, envoyé [delayAfterPreviousMs]
     * après la fin de l'étape précédente.
     *
     * Pourquoi des étapes séparées plutôt qu'un seul geste à plusieurs
     * traits ? Si le trou n'est pas encore ouvert au moment de l'envoi, le
     * début du geste retombe sur l'overlay mais la suite peut passer : pour
     * un double-tap, Instagram ne recevait que le second tap (= pause).
     * Ici, chaque étape n'est envoyée qu'une fois la précédente arrivée.
     */
    private class Step(val stroke: GestureDescription.StrokeDescription, val delayAfterPreviousMs: Long = 0)

    // Ceinture et bretelles : si aucun rappel n'arrive, on rétablit quand même l'overlay.
    private val replayTimeout = Runnable { endReplay() }

    /** Le trou nécessaire pour rejouer un geste passant par ces [points]. */
    private fun holeAround(points: List<PointF>) = Rect(
        points.minOf { it.x }.toInt() - holeMargin,
        points.minOf { it.y }.toInt() - holeMargin,
        points.maxOf { it.x }.toInt() + holeMargin,
        points.maxOf { it.y }.toInt() + holeMargin,
    )

    /** Dispose les panneaux autour d'un trou, mais trou BOUCHÉ (voir [plug]). */
    private fun prepareHole(points: List<PointF>) {
        val target = holeAround(points)
        hole = target
        plug.setTouchable(true)
        // Le bouchon d'abord ; on ne dispose les panneaux autour (ce qui
        // rétrécit le panneau principal) qu'une fois le bouchon réellement en place.
        plug.show(target) { if (hole == target && !replaying) layoutPanes() }
    }

    /**
     * Ouvre un trou autour des [points], puis rejoue les [steps] dedans.
     */
    private fun replay(points: List<PointF>, steps: List<Step>) {
        replaying = true
        val target = holeAround(points)
        if (plug.isShown && target == hole) {
            // Trou préparé : on rend le bouchon traversable (simple changement
            // d'option, rapide). layoutPanes() ne change rien si les panneaux
            // sont déjà en place, et les place sinon (préparation inachevée).
            plug.setTouchable(false)
        } else {
            // Pas de trou préparé (bouton, double-tap) : on dispose les panneaux maintenant.
            plug.hide()
            hole = target
        }
        layoutPanes()
        dispatchStep(steps, index = 0, attempt = 1, delayMs = PASS_THROUGH_DELAY_MS)
        handler.postDelayed(replayTimeout, (PASS_THROUGH_DELAY_MS + SEQUENCE_GAP_MS + SEEK_REPLAY_MS) * MAX_ATTEMPTS + 500)
    }

    /**
     * Injecte une étape après [delayMs]. La première attend ≈ 60 ms : le
     * système met ce temps (mesuré sur ton Pixel) à ouvrir le trou. Trop tôt,
     * le geste retombe sur l'overlay ; on relance alors la même étape, jusqu'à
     * MAX_ATTEMPTS fois. Sinon on passe à l'étape suivante.
     */
    private fun dispatchStep(steps: List<Step>, index: Int, attempt: Int, delayMs: Long) {
        handler.postDelayed({
            if (!replaying) return@postDelayed // rejeu abandonné entre-temps (délai dépassé)
            bounced = false
            val gesture = GestureDescription.Builder().addStroke(steps[index].stroke).build()
            val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    when {
                        bounced && attempt < MAX_ATTEMPTS -> {
                            log("Rejeu retombé sur l'overlay, essai ${attempt + 1}")
                            dispatchStep(steps, index, attempt + 1, PASS_THROUGH_DELAY_MS)
                        }
                        !bounced && index + 1 < steps.size ->
                            dispatchStep(steps, index + 1, attempt = 1, steps[index + 1].delayAfterPreviousMs)
                        else -> {
                            if (bounced) log("Rejeu impossible après $attempt essais")
                            endReplay()
                        }
                    }
                }
                override fun onCancelled(g: GestureDescription) = endReplay()
            }, null)
            if (!accepted) endReplay()
        }, delayMs)
    }

    private fun endReplay() {
        if (!replaying) return
        replaying = false
        // On n'annule QUE le délai de sécurité : le minuteur d'un tap en attente doit survivre.
        handler.removeCallbacks(replayTimeout)
        // On rebouche : d'abord le bouchon (rapide), puis le panneau principal
        // reprend toute la place, tranquillement, derrière le bouchon.
        if (plug.isShown) plug.setTouchable(true)
        hole = null
        layoutPanes()
    }

    private fun Rect.contains(x: Float, y: Float) = contains(x.toInt(), y.toInt())

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "StopScroll"
        const val PASS_THROUGH_DELAY_MS = 60L
        const val TAP_STROKE_MS = 20L
        const val MIN_DOUBLE_TAP_GAP_MS = 80L
        const val MAX_DOUBLE_TAP_GAP_MS = 250L
        // Écart entre un tap en attente et le geste qui le suit (assez long
        // pour qu'Instagram n'y voie pas un double-tap).
        const val SEQUENCE_GAP_MS = 400L
        const val SEEK_REPLAY_MS = 100L
        // Délai maximal d'attente de la confirmation d'un redimensionnement.
        const val LAYOUT_CONFIRM_TIMEOUT_MS = 500L
        // Marges après confirmation (voir layoutPanes).
        const val CONFIRM_MARGIN_MS = 50L
        const val SPARE_PARK_DELAY_MS = 300L
        // Position d'un panneau inutilisé : 1 pixel, hors de l'écran.
        val PARKED = Rect(-10, -10, -9, -9)
        const val MAX_ATTEMPTS = 5
    }
}
