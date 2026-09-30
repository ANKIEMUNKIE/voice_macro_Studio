package dev.voicemacro.studio.capture

import android.graphics.Rect

/**
 * Represents a frozen, serialized copy of a UI node.
 * Strips away the AccessibilityNodeInfo IPC linkage so it can be stored, diffed, or logged.
 */
data class UiNode(
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val boundsInScreen: Rect,
    val isImportantForAccessibility: Boolean,
    val viewIdResourceName: String?
)

/**
 * A snapshot of the entire screen structure at a given moment.
 */
data class UiSnapshot(
    val packageName: String,
    val timestampMs: Long,
    val signature: String, // A hash/string representing the physical structure of the screen (ignoring text/prices)
    val nodes: List<UiNode>
)

/**
 * Criteria to find a specific node on a screen during replay.
 */
data class Selector(
    val textMatches: String? = null,
    val contentDescriptionMatches: String? = null,
    val viewIdMatches: String? = null,
    val classNameMatches: String? = null,
    val requireClickable: Boolean = false,
    val indexOffBy: Int = 0 // E.g., if there are 3 identical 'Add' buttons, this distinguishes them
) {
    fun matches(node: UiNode): Boolean {
        if (requireClickable && !node.isClickable) return false
        if (textMatches != null && node.text != textMatches) return false
        if (contentDescriptionMatches != null && node.contentDescription != contentDescriptionMatches) return false
        if (viewIdMatches != null && node.viewIdResourceName != viewIdMatches) return false
        if (classNameMatches != null && !node.className.contains(classNameMatches, ignoreCase = true)) return false
        return true
    }
}
