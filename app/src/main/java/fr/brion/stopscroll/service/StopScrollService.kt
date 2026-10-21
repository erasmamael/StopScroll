package fr.brion.stopscroll.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import fr.brion.stopscroll.BuildConfig
import fr.brion.stopscroll.data.Settings
import fr.brion.stopscroll.data.SettingsRepository
import fr.brion.stopscroll.debug.TreeDumper
import fr.brion.stopscroll.policy.BlockingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Le service d'accessibilité : le "cerveau" du blocage.
 *
 * Cycle de vie (géré par Android, pas par nous) :
 *  - onServiceConnected() : appelé quand tu actives le service dans les paramètres.
 *  - onAccessibilityEvent() : appelé à chaque événement qui passe les filtres
 *    déclarés dans accessibility_service_config.xml.
 *  - onInterrupt() : Android demande d'interrompre un retour (voix…). Inutile ici.
 *  - onDestroy() : le service est désactivé ; on libère ce qu'on a créé.
 *
 * Deux fils d'exécution (threads) :
 *  - le fil PRINCIPAL gère les overlays et tes gestes. Il doit rester libre :
 *    un overlay ne change de forme qu'après avoir été redessiné par ce fil ;
 *  - un fil d'ANALYSE interroge Instagram (lecture de l'arbre). Chaque
 *    interrogation est un appel bloquant vers Instagram, qui peut durer
 *    plusieurs dizaines de ms : les faire ici évite de ralentir les overlays
 *    (mesuré : sans cela, le "trou" de l'overlay mettait parfois 250 ms à s'ouvrir).
 *
 * Stratégie anti-gaspillage :
 *  - les clics et défilements sont traités immédiatement (décisifs) ;
 *  - les changements d'écran/contenu (très fréquents pendant une vidéo) ne
 *    déclenchent qu'UNE analyse différée, même s'il en arrive 20
 *    entre-temps ("debounce"). Aucune boucle ni minuterie permanente.
 *
 * Écoute élargie : normalement on n'écoute QUE Instagram. Mais tant qu'un
 * overlay est affiché, il faut savoir si tu quittes Instagram (sinon
 * l'overlay resterait sur ton écran d'accueil). Pendant ce temps-là
 * seulement, on écoute aussi les changements d'écran des autres apps.
 */
class StopScrollService : AccessibilityService() {

    // File d'attente de tâches du fil principal (overlays, gestes).
    private val mainHandler = Handler(Looper.getMainLooper())

    // Fil d'analyse et sa file d'attente.
    private val analysisThread = HandlerThread("StopScroll-analyse").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)

    // Portée des tâches asynchrones (coroutines) du service, annulée à sa destruction.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Copie locale des réglages, mise à jour automatiquement quand ils changent.
    // @Volatile : lue par le fil d'analyse, écrite par le fil principal.
    @Volatile private var settings = Settings()
    private val policy = BlockingPolicy { settings }

    private lateinit var reelsTabBlocker: ReelsTabBlocker
    private lateinit var swipeBlocker: SwipeBlockerOverlay
    private lateinit var safetyNet: ScrollSafetyNet

    // Analyse d'écran différée (voir scheduleScreenCheck).
    private val screenCheckScheduled = AtomicBoolean(false)
    private val screenCheck = Runnable {
        screenCheckScheduled.set(false)
        analyzeScreen()
    }

    private var listeningToAllApps = false

    // Dernier lecteur de Reels vu (sert au journal debug).
    private var lastViewer: ReelViewer? = null

    /**
     * Résultat d'une analyse d'écran, calculé sur le fil d'analyse puis
     * appliqué sur le fil principal.
     */
    private class ScreenState(
        val root: AccessibilityNodeInfo,
        val reelsTabSelected: Boolean,
        val reelsTabBounds: Rect?,
        val viewer: ReelViewer?,
    )

    /**
     * Récepteur du message "exporte l'arbre maintenant" (version debug uniquement).
     * Extra optionnel "delay_ms" : attendre avant l'export, le temps de
     * revenir sur l'écran Instagram voulu.
     */
    private val dumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val delay = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
            analysisHandler.postDelayed({
                val file = TreeDumper.dump(this@StopScrollService)
                Log.i(TAG, "Arbre exporté : ${file.absolutePath}")
            }, delay)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connecté")
        reelsTabBlocker = ReelsTabBlocker(this)
        swipeBlocker = SwipeBlockerOverlay(this)
        safetyNet = ScrollSafetyNet(this)

        // On s'abonne aux réglages : chaque modification dans l'app arrive ici
        // et on réévalue aussitôt l'écran (ex. retirer un overlay désactivé).
        SettingsRepository(this).settings
            .onEach {
                settings = it
                scheduleScreenCheck()
            }
            .launchIn(scope)

        if (BuildConfig.DEBUG) {
            // EXPORTED : nécessaire pour recevoir le message envoyé par adb depuis le PC.
            // Sans risque ici : uniquement dans la version debug, et l'export reste local.
            ContextCompat.registerReceiver(
                this, dumpReceiver, IntentFilter(ACTION_DUMP_TREE), ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    // ── Réception des événements (fil principal) ───────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg != InstagramSelectors.PACKAGE) {
            // Ne peut arriver que pendant l'écoute élargie : un autre écran s'est
            // ouvert (écran d'accueil, autre app…). On réévalue, ce qui retirera
            // les overlays si Instagram n'est plus au premier plan.
            // Nos propres overlays et messages sont ignorés.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName) {
                scheduleScreenCheck(immediate = true)
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Repli de la règle 1 : appui sur Reels qui aurait échappé au bouclier.
                if (policy.shouldBlockReelsTab()) {
                    val copy = copyOf(event)
                    analysisHandler.post { checkReelsTabClick(copy) }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Filet de sécurité : la provenance du défilement se vérifie sur le fil d'analyse.
                if (policy.shouldBlockReelScroll()) {
                    val copy = copyOf(event)
                    analysisHandler.post { checkViewerScroll(copy) }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Nouvel écran : ${event.className}")
                scheduleScreenCheck()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleScreenCheck()
        }
    }

    /**
     * Android recycle l'objet "event" dès la fin de onAccessibilityEvent :
     * pour l'utiliser sur un autre fil, il faut en faire une copie.
     */
    private fun copyOf(event: AccessibilityEvent): AccessibilityEvent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AccessibilityEvent(event)
        } else {
            @Suppress("DEPRECATION")
            AccessibilityEvent.obtain(event)
        }

    /**
     * Programme une analyse de l'écran, sauf si une est déjà prévue.
     * immediate = true : au plus vite (quitter Instagram doit retirer les
     * overlays sans attendre).
     */
    private fun scheduleScreenCheck(immediate: Boolean = false) {
        if (immediate) {
            analysisHandler.removeCallbacks(screenCheck)
            screenCheckScheduled.set(false)
        }
        if (!screenCheckScheduled.compareAndSet(false, true)) return
        analysisHandler.postDelayed(screenCheck, if (immediate) 0L else SCREEN_CHECK_DELAY_MS)
    }

    // ── Analyse (fil d'analyse) ─────────────────────────────────────────────

    private fun checkReelsTabClick(event: AccessibilityEvent) {
        val root = rootInActiveWindow
        val screenHeight = root?.let { Rect().also(it::getBoundsInScreen).height() } ?: 0
        if (ScreenDetector.isReelsTabClick(event, screenHeight)) {
            mainHandler.post { reelsTabBlocker.redirectToHome(root) }
        }
    }

    private fun checkViewerScroll(event: AccessibilityEvent) {
        if (!ScreenDetector.isFromReelViewer(event)) return
        val index = event.fromIndex
        if (BuildConfig.DEBUG) Log.d(TAG, "Défilement du lecteur : index $index")
        mainHandler.post { safetyNet.onViewerScrolled(index) }
    }

    /** Lit l'écran Instagram actuel et transmet le résultat au fil principal. */
    private fun analyzeScreen() {
        // Pendant que tu touches l'overlay et qu'on rejoue ton geste, on laisse
        // le système tranquille (voir SwipeBlockerOverlay.isBusy) et on
        // réessaie un peu plus tard.
        if (swipeBlocker.isBusy) {
            scheduleScreenCheck()
            return
        }
        val root = rootInActiveWindow
        if (root == null || root.packageName != InstagramSelectors.PACKAGE) {
            // Instagram n'est plus au premier plan : on retire tout.
            mainHandler.post { hideAllOverlays() }
            return
        }
        val blockTab = policy.shouldBlockReelsTab()
        val tabSelected = blockTab && ScreenDetector.isReelsTabSelected(root)
        val state = ScreenState(
            root = root,
            reelsTabSelected = tabSelected,
            reelsTabBounds = if (blockTab && !tabSelected) ScreenDetector.findReelsTabBounds(root) else null,
            viewer = if (policy.shouldBlockReelScroll()) {
                val metrics = resources.displayMetrics
                ScreenDetector.findReelViewer(root, metrics.widthPixels, metrics.heightPixels)
            } else {
                null
            },
        )
        mainHandler.post { applyScreenState(state) }
    }

    // ── Application des règles (fil principal) ─────────────────────────────

    private fun applyScreenState(state: ScreenState) {
        // ── Règle 1 : onglet Reels ──
        if (state.reelsTabSelected) {
            // L'onglet s'est ouvert malgré le bouclier : on renvoie sur l'Accueil.
            reelsTabBlocker.hideShield()
            reelsTabBlocker.redirectToHome(state.root)
        } else {
            reelsTabBlocker.updateShield(state.reelsTabBounds)
        }

        // ── Règle 3 : défilement dans le lecteur ──
        val viewer = state.viewer
        safetyNet.onViewerVisibility(viewer != null)
        if (viewer != null && policy.useSwipeOverlay()) {
            swipeBlocker.show(viewer)
        } else {
            swipeBlocker.hide()
        }
        // Lecteur en pleine animation : on remesurera dès qu'elle sera finie.
        if (viewer?.settling == true) scheduleScreenCheck()
        logViewerChange(viewer)

        updateListeningScope()
    }

    private fun hideAllOverlays() {
        reelsTabBlocker.hideAll()
        swipeBlocker.hide()
        safetyNet.onViewerVisibility(false)
        logViewerChange(null)
        updateListeningScope()
    }

    /**
     * Active l'écoute élargie seulement si un overlay est affiché.
     * serviceInfo = la configuration du service ; on peut la modifier à chaud.
     */
    private fun updateListeningScope() {
        val needAll = reelsTabBlocker.isShieldShown || swipeBlocker.isShown
        if (needAll == listeningToAllApps) return
        listeningToAllApps = needAll
        serviceInfo = serviceInfo.apply {
            packageNames = if (needAll) null else arrayOf(InstagramSelectors.PACKAGE)
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "Écoute élargie : $needAll")
    }

    private fun logViewerChange(viewer: ReelViewer?) {
        if (BuildConfig.DEBUG && viewer?.reelKey != lastViewer?.reelKey) {
            when {
                viewer == null -> Log.i(TAG, "Lecteur de Reels : masqué")
                lastViewer == null -> Log.i(TAG, "Lecteur de Reels : affiché $viewer")
                else -> Log.i(TAG, "Lecteur de Reels : CHANGEMENT de Reel → ${viewer.reelKey}")
            }
        }
        lastViewer = viewer
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        analysisThread.quitSafely()
        scope.cancel()
        if (::swipeBlocker.isInitialized) {
            swipeBlocker.hide()
            reelsTabBlocker.hideAll()
        }
        if (BuildConfig.DEBUG) unregisterReceiver(dumpReceiver)
        super.onDestroy()
    }

    companion object {
        // Étiquette pour filtrer nos messages dans Logcat.
        private const val TAG = "StopScroll"
        private const val SCREEN_CHECK_DELAY_MS = 120L

        const val ACTION_DUMP_TREE = "fr.brion.stopscroll.DUMP_TREE"
        const val EXTRA_DELAY_MS = "delay_ms"
    }
}
