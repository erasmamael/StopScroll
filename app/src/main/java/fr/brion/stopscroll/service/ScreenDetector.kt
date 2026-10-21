package fr.brion.stopscroll.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import fr.brion.stopscroll.service.InstagramSelectors as Sel

/**
 * Ce que l'on sait du lecteur de Reels affiché à l'écran.
 *
 * @param viewerBounds   rectangle occupé par le lecteur (vidéo + boutons + barre de lecture).
 * @param buttonAreas    zones de boutons, où un tap est transmis sans attendre.
 * @param seekArea       zone de la barre de lecture, où un glissé horizontal est autorisé.
 * @param reelKey        "empreinte" du Reel affiché (auteur + légende), pour le journal debug.
 * @param settling       vrai si le lecteur est en pleine animation (il glisse
 *                       depuis le bord) : les positions mesurées sont provisoires.
 */
data class ReelViewer(
    val viewerBounds: Rect,
    val buttonAreas: List<Rect>,
    val seekArea: Rect?,
    val reelKey: String,
    val settling: Boolean,
)

/**
 * Reconnaît les écrans d'Instagram à partir de l'arbre d'accessibilité.
 *
 * Performance : on n'explore JAMAIS tout l'arbre. On utilise
 * findAccessibilityNodeInfosByViewId(), qui demande directement à Instagram
 * "donne-moi l'élément qui a tel identifiant". Les replis par description
 * ne parcourent que les enfants directs de la racine (la barre du bas).
 *
 * Attention aux éléments "invisibles" : Instagram garde en mémoire les onglets
 * non affichés (ex. le fil d'accueil quand on est sur Reels). On ignore donc
 * tout élément dont isVisibleToUser est faux.
 */
object ScreenDetector {

    // ── Onglet Reels ────────────────────────────────────────────────────────

    /** Vrai si l'onglet Reels de la barre du bas est l'onglet sélectionné. */
    fun isReelsTabSelected(root: AccessibilityNodeInfo): Boolean =
        findBottomTab(root, Sel.REELS_TAB_IDS, Sel.REELS_TAB_DESCS)?.isSelected == true

    /** Position de l'onglet Reels s'il est affiché (pour y poser le bouclier), sinon null. */
    fun findReelsTabBounds(root: AccessibilityNodeInfo): Rect? =
        findBottomTab(root, Sel.REELS_TAB_IDS, Sel.REELS_TAB_DESCS)
            ?.boundsInScreen()?.takeIf { !it.isEmpty }

    /** L'onglet Accueil, pour pouvoir cliquer dessus. */
    fun findHomeTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findBottomTab(root, Sel.HOME_TAB_IDS, Sel.HOME_TAB_DESCS)

    /** Vrai si cet événement de clic correspond à un appui sur l'onglet Reels. */
    fun isReelsTabClick(event: AccessibilityEvent, screenHeight: Int): Boolean {
        val source = event.source ?: return false
        val id = source.viewIdResourceName?.substringAfter(":id/")
        if (id != null && id in Sel.REELS_TAB_IDS) return true
        // Repli : description "Reels" ET position dans la barre du bas.
        return source.contentDescription?.toString() in Sel.REELS_TAB_DESCS &&
            isInBottomBar(source, screenHeight)
    }

    // ── Lecteur de Reels ────────────────────────────────────────────────────

    /** Vrai si cet événement (ex. défilement) provient du lecteur de Reels. */
    fun isFromReelViewer(event: AccessibilityEvent): Boolean =
        event.source?.viewIdResourceName?.substringAfter(":id/") in Sel.REEL_VIEWER_IDS

    /**
     * Renvoie le lecteur de Reels s'il est affiché ET qu'aucun panneau
     * (commentaires, partage) n'est ouvert par-dessus ; sinon null.
     *
     * @param screenWidth  largeur de l'écran, en pixels.
     * @param screenHeight hauteur de l'écran, en pixels.
     */
    fun findReelViewer(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): ReelViewer? {
        if (findVisibleById(root, Sel.BOTTOM_SHEET_IDS) != null) return null
        val viewer = findVisibleById(root, Sel.REEL_VIEWER_IDS) ?: return null

        val viewerBounds = viewer.boundsInScreen().takeIf { !it.isEmpty } ?: return null

        // Le lecteur occupe toujours toute la largeur de l'écran. S'il est
        // décalé ou rétréci, c'est qu'il est en pleine animation (il glisse
        // depuis le bord, ou "zoome" au lancement d'Instagram). On couvre
        // alors TOUT l'écran, pour ne laisser aucune zone sans protection
        // pendant l'animation (constaté en test) ; une nouvelle mesure suit.
        val settling = viewerBounds.left != 0 || viewerBounds.right != screenWidth
        if (settling) viewerBounds.set(0, 0, screenWidth, screenHeight)

        // Barre de lecture = la barre elle-même + sa zone tactile agrandie.
        val seekArea = (Sel.REEL_SCRUBBER_IDS + Sel.REEL_EXTRA_TOUCH_IDS)
            .mapNotNull { findVisibleById(root, listOf(it))?.boundsInScreen() }
            .filter { !it.isEmpty }
            .reduceOrNull { acc, r -> acc.apply { union(r) } }
        // L'overlay doit la couvrir aussi : elle déborde sous le lecteur.
        seekArea?.let(viewerBounds::union)

        val buttonAreas = Sel.REEL_BUTTON_AREA_IDS
            .mapNotNull { findVisibleById(root, listOf(it))?.boundsInScreen() }
            .filter { !it.isEmpty }

        val author = findVisibleById(root, Sel.REEL_AUTHOR_IDS)?.text?.toString()?.trim().orEmpty()
        // La légende est un petit sous-arbre : on prend la première description trouvée dedans.
        val caption = findVisibleById(root, Sel.REEL_CAPTION_IDS)
            ?.let { firstDescription(it, depthLeft = 8) }.orEmpty()

        return ReelViewer(viewerBounds, buttonAreas, seekArea, reelKey = "$author|$caption", settling = settling)
    }

    // ── Outils internes ─────────────────────────────────────────────────────

    /** Premier élément VISIBLE portant l'un des identifiants donnés. */
    private fun findVisibleById(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? {
        for (id in ids) {
            root.findAccessibilityNodeInfosByViewId(Sel.fullId(id))
                .firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }
        return null
    }

    /**
     * Cherche un onglet de la barre du bas : d'abord par identifiant, puis
     * (repli) par description parmi les enfants directs de la racine,
     * à condition qu'il soit en bas de l'écran.
     */
    private fun findBottomTab(
        root: AccessibilityNodeInfo,
        ids: List<String>,
        descs: List<String>,
    ): AccessibilityNodeInfo? {
        findVisibleById(root, ids)?.let { return it }
        val screenHeight = root.boundsInScreen().height()
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (child.contentDescription?.toString() in descs && isInBottomBar(child, screenHeight)) {
                return child
            }
        }
        return null
    }

    private fun isInBottomBar(node: AccessibilityNodeInfo, screenHeight: Int): Boolean =
        screenHeight > 0 && node.boundsInScreen().top >= screenHeight * Sel.BOTTOM_BAR_MIN_TOP_FRACTION

    /** Parcours limité en profondeur d'un PETIT sous-arbre. */
    private fun firstDescription(node: AccessibilityNodeInfo, depthLeft: Int): String? {
        node.contentDescription?.let { if (it.isNotBlank()) return it.toString() }
        if (depthLeft == 0) return null
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> firstDescription(child, depthLeft - 1)?.let { return it } }
        }
        return null
    }

    private fun AccessibilityNodeInfo.boundsInScreen() = Rect().also { getBoundsInScreen(it) }
}
