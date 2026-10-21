package fr.brion.stopscroll.service

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  TOUS les repères qui servent à reconnaître l'interface d'Instagram.     ║
 * ║  C'est LE fichier à modifier quand une mise à jour d'Instagram casse     ║
 * ║  la détection : exporte l'arbre (mode debug), cherche le nouvel          ║
 * ║  identifiant / libellé, et ajoute-le dans la liste correspondante.       ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * Vocabulaire :
 *  - "id" : identifiant technique d'un élément (viewIdResourceName), écrit ici
 *    SANS le préfixe "com.instagram.android:id/". Indépendant de la langue,
 *    mais Instagram le renomme parfois.
 *  - "desc" : contentDescription, le texte lu par les lecteurs d'écran.
 *    Dépend de la langue : on liste français ET anglais.
 *
 * Relevé effectué le 21/10/2026 sur un Pixel 7a, Instagram en français.
 * Chaque liste est essayée dans l'ordre ; ajouter un élément ne casse rien.
 */
object InstagramSelectors {

    const val PACKAGE = "com.instagram.android"

    /** Construit l'identifiant complet attendu par findAccessibilityNodeInfosByViewId(). */
    fun fullId(id: String) = "$PACKAGE:id/$id"

    // ── Barre de navigation du bas ──────────────────────────────────────────

    /** Onglet Reels de la barre du bas. */
    val REELS_TAB_IDS = listOf("clips_tab")
    val REELS_TAB_DESCS = listOf("Reels")

    /** Onglet Accueil (là où on redirige). */
    val HOME_TAB_IDS = listOf("feed_tab")
    val HOME_TAB_DESCS = listOf("Accueil", "Home")

    /**
     * La barre du bas occupe le bas de l'écran. Un élément "Reels" n'est
     * considéré comme l'onglet que s'il commence sous cette fraction de la
     * hauteur d'écran. Évite de confondre avec le titre "Reels" du lecteur
     * (en haut de l'écran).
     */
    const val BOTTOM_BAR_MIN_TOP_FRACTION = 0.85f

    // ── Lecteur de Reels (plein écran) ──────────────────────────────────────

    /**
     * Le conteneur qui fait défiler les Reels verticalement. Le même dans
     * tous les cas : onglet Reels, message privé, fil, Explorer, profil.
     * L'overlay anti-swipe est posé exactement sur son rectangle.
     */
    val REEL_VIEWER_IDS = listOf("clips_viewer_view_pager")

    /**
     * Zone tactile agrandie de la barre de lecture : elle déborde SOUS le
     * lecteur. Sans elle, attraper la barre et glisser vers le haut fait
     * défiler. L'overlay couvre donc aussi ce rectangle.
     */
    val REEL_EXTRA_TOUCH_IDS = listOf("clips_expanded_touch_view")

    /** Barre de lecture : un glissé horizontal dessus (avance/recul) est autorisé. */
    val REEL_SCRUBBER_IDS = listOf("scrubber")

    /**
     * Zones de boutons : colonne de droite (J'aime, commentaires, partage…)
     * et zone du bas (auteur, Suivre, légende). Un tap y est transmis
     * IMMÉDIATEMENT, sans attendre un éventuel double-tap.
     */
    val REEL_BUTTON_AREA_IDS = listOf("clips_ufi_component", "clips_media_info_component")

    /** Nom de l'auteur du Reel affiché (sert à repérer un changement de Reel). */
    val REEL_AUTHOR_IDS = listOf("clips_author_username")

    /** Légende du Reel (avec l'auteur, sert à repérer un changement de Reel). */
    val REEL_CAPTION_IDS = listOf("clips_caption_component")

    // ── Panneaux qui s'ouvrent par-dessus le lecteur ────────────────────────

    /** Panneau du bas : commentaires ET menu de partage utilisent ce conteneur. */
    val BOTTOM_SHEET_IDS = listOf("bottom_sheet_container")
}
