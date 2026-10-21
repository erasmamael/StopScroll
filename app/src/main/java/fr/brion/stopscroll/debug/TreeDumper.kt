package fr.brion.stopscroll.debug

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Outil de DEBUG : écrit dans un fichier texte l'arbre d'accessibilité de
 * toutes les fenêtres affichées (Instagram + éventuels panneaux).
 *
 * Notion : l'écran d'une app est un arbre d'éléments (AccessibilityNodeInfo).
 * La racine est la fenêtre ; ses enfants sont des conteneurs, qui contiennent
 * des boutons, textes, images… Pour chaque élément on note :
 *   - sa classe (ex. android.widget.Button)
 *   - son identifiant technique (viewIdResourceName, ex. "...:id/clips_tab")
 *   - son texte et sa description (contentDescription, lue par les lecteurs d'écran)
 *   - sa position à l'écran (bounds) et quelques états (cliquable, défilable…)
 *
 * Le fichier est écrit dans le stockage PRIVÉ de l'app (filesDir/dumps).
 * Il ne quitte jamais le téléphone tout seul.
 */
object TreeDumper {

    fun dump(service: AccessibilityService): File {
        val sb = StringBuilder()
        val windows = service.windows
        if (windows.isEmpty()) {
            // Repli : si la liste des fenêtres n'est pas disponible, la fenêtre active seule.
            sb.appendLine("=== rootInActiveWindow ===")
            service.rootInActiveWindow?.let { dumpNode(it, 0, sb) }
        } else {
            for (window in windows) {
                sb.appendLine(
                    "=== Fenêtre type=${windowType(window.type)} titre=${window.title} " +
                        "couche=${window.layer} active=${window.isActive} " +
                        "bounds=${Rect().also(window::getBoundsInScreen).toShortString()} ==="
                )
                window.root?.let { dumpNode(it, 0, sb) }
                sb.appendLine()
            }
        }

        val dir = File(service.filesDir, "dumps").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return File(dir, "tree-$stamp.txt").apply { writeText(sb.toString()) }
    }

    /** Écrit un élément puis, récursivement, ses enfants (indentés de 2 espaces). */
    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        val bounds = Rect().also(node::getBoundsInScreen)
        sb.append("  ".repeat(depth))
        sb.append(node.className?.toString()?.substringAfterLast('.') ?: "?")
        node.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
        node.text?.let { sb.append(" text=\"").append(it.oneLine()).append('"') }
        node.contentDescription?.let { sb.append(" desc=\"").append(it.oneLine()).append('"') }
        sb.append(" ").append(bounds.toShortString())
        if (node.isClickable) sb.append(" [clic]")
        if (node.isLongClickable) sb.append(" [clicLong]")
        if (node.isScrollable) sb.append(" [défilable]")
        if (node.isSelected) sb.append(" [sélectionné]")
        if (!node.isVisibleToUser) sb.append(" [invisible]")
        // Actions "personnalisées" proposées par l'app (ex. ce qu'un appui long
        // déclencherait), avec leur libellé. Les actions standard sont ignorées.
        node.actionList
            .filter { it.label != null }
            .takeIf { it.isNotEmpty() }
            ?.let { actions -> sb.append(" actions=").append(actions.joinToString("|") { "${it.id}:${it.label}" }) }
        sb.appendLine()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, depth + 1, sb) }
        }
    }

    private fun CharSequence.oneLine() = toString().replace('\n', ' ').take(120)

    private fun windowType(type: Int) = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "CLAVIER"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTÈME"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "OVERLAY_ACCESSIBILITÉ"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "DIVISEUR"
        else -> type.toString()
    }
}
