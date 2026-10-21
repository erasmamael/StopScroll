package fr.brion.stopscroll.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Les réglages de l'app. Par défaut, tout est bloqué. */
data class Settings(
    /** Règle 1 : l'onglet Reels renvoie vers l'Accueil. */
    val blockReelsTab: Boolean = true,
    /** Règle 3 : impossible de passer d'un Reel à l'autre. */
    val blockReelScroll: Boolean = true,
    /**
     * Méthode de la règle 3 :
     *  - true  = overlay qui absorbe les swipes (le Reel reste affiché) ;
     *  - false = on laisse le swipe se faire et on ferme le lecteur aussitôt.
     */
    val useSwipeOverlay: Boolean = true,
)

/**
 * DataStore = petit fichier de réglages privé à l'app (dans /data/data/…).
 * "by preferencesDataStore" garantit qu'il n'existe qu'UNE instance par
 * processus, partagée par l'interface et le service.
 */
private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Seul point d'accès aux réglages.
 * Flow = flux de valeurs : on reçoit la valeur actuelle, puis chaque
 * nouvelle valeur dès qu'un réglage change (même depuis un autre écran).
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val settings: Flow<Settings> = dataStore.data.map { prefs ->
        Settings(
            blockReelsTab = prefs[KEY_BLOCK_REELS_TAB] ?: true,
            blockReelScroll = prefs[KEY_BLOCK_REEL_SCROLL] ?: true,
            useSwipeOverlay = prefs[KEY_USE_SWIPE_OVERLAY] ?: true,
        )
    }

    suspend fun setBlockReelsTab(enabled: Boolean) = set(KEY_BLOCK_REELS_TAB, enabled)

    suspend fun setBlockReelScroll(enabled: Boolean) = set(KEY_BLOCK_REEL_SCROLL, enabled)

    suspend fun setUseSwipeOverlay(enabled: Boolean) = set(KEY_USE_SWIPE_OVERLAY, enabled)

    private suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    private companion object {
        val KEY_BLOCK_REELS_TAB = booleanPreferencesKey("block_reels_tab")
        val KEY_BLOCK_REEL_SCROLL = booleanPreferencesKey("block_reel_scroll")
        val KEY_USE_SWIPE_OVERLAY = booleanPreferencesKey("use_swipe_overlay")
    }
}
