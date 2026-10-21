package fr.brion.stopscroll.policy

import fr.brion.stopscroll.data.Settings

/**
 * LE point central qui répond à la question "faut-il bloquer maintenant ?".
 *
 * Le service ne lit jamais les réglages directement : il passe toujours par
 * ici. Ainsi, pour ajouter plus tard les plages horaires, il suffira de
 * modifier isBlockingPeriodNow() — rien d'autre dans le projet.
 *
 * @param currentSettings fonction qui renvoie les réglages les plus récents.
 */
class BlockingPolicy(private val currentSettings: () -> Settings) {

    fun shouldBlockReelsTab(): Boolean =
        currentSettings().blockReelsTab && isBlockingPeriodNow()

    fun shouldBlockReelScroll(): Boolean =
        currentSettings().blockReelScroll && isBlockingPeriodNow()

    /** Méthode de blocage du défilement : overlay (true) ou fermeture du lecteur (false). */
    fun useSwipeOverlay(): Boolean = currentSettings().useSwipeOverlay

    /**
     * Point d'extension futur : plages horaires sans blocage.
     * Pour l'instant, le blocage est actif en permanence.
     */
    private fun isBlockingPeriodNow(): Boolean = true
}
