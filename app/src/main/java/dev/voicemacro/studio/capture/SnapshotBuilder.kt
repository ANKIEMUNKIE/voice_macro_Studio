package dev.voicemacro.studio.capture

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

object SnapshotBuilder {

    /**
     * Builds a normalized snapshot of the current window root.
     * Extracts structural signatures to identify the screen type, and collects visible interactive nodes.
     */
    fun build(root: AccessibilityNodeInfo): UiSnapshot? {
        val packageName = root.packageName?.toString() ?: ""
        val nodes = mutableListOf<UiNode>()
        val structureString = StringBuilder()

        // The caller owns the root. Children returned by getChild() are owned here.
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(root to false)

        try {
            while (queue.isNotEmpty()) {
                val (node, owned) = queue.removeFirst()
                try {
                    // Normalize class name for signature (e.g. android.widget.FrameLayout -> FrameLayout)
                    val shortClass = node.className?.toString()?.substringAfterLast('.') ?: "View"
                    structureString.append(shortClass).append(",")

                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)

                    // We only save nodes that are potentially interactive or have meaningful text
                    val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
                    // Some React Native trees misreport isVisibleToUser. Meaningful nodes are
                    // retained even when that flag is false; safety is decided elsewhere.
                    val isExposed = node.isVisibleToUser || !bounds.isEmpty || hasText || node.isClickable || node.isEditable
                    
                    if (isExposed && (hasText || node.isClickable || node.isEditable)) {
                        nodes.add(
                            UiNode(
                                className = shortClass,
                                text = node.text?.toString(),
                                contentDescription = node.contentDescription?.toString(),
                                isClickable = node.isClickable,
                                isScrollable = node.isScrollable,
                                isEditable = node.isEditable,
                                boundsInScreen = bounds,
                                isImportantForAccessibility = node.isImportantForAccessibility,
                                viewIdResourceName = node.viewIdResourceName
                            )
                        )
                    }

                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it to true) }
                    }
                } finally {
                    if (owned) node.recycle()
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                val (node, owned) = queue.removeFirst()
                if (owned) node.recycle()
            }
        }

        // Create a signature hash of the structure.
        // This explicitly ignores dynamic fields like prices, times, and banners because it only hashes class names!
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(structureString.toString().toByteArray())
        val signature = hashBytes.joinToString("") { "%02x".format(it) }.take(16)

        return UiSnapshot(
            packageName = packageName,
            timestampMs = SystemClock.uptimeMillis(),
            signature = signature,
            nodes = nodes
        )
    }

    /**
     * Compares two snapshots of the same screen to discover which fields are stable and which are dynamic.
     * Returns a list of stable selectors.
     */
    fun findStableSelectors(snapshotA: UiSnapshot, snapshotB: UiSnapshot): List<Selector> {
        if (snapshotA.signature != snapshotB.signature) {
            // Screens are structurally different
            return emptyList()
        }

        val stableSelectors = mutableListOf<Selector>()

        for (nodeA in snapshotA.nodes) {
            // Find a structurally identical node in snapshotB
            val matchInB = snapshotB.nodes.find { nodeB ->
                nodeA.boundsInScreen == nodeB.boundsInScreen && nodeA.className == nodeB.className
            }

            if (matchInB != null) {
                // Check if text/desc is stable
                val textStable = nodeA.text == matchInB.text && !nodeA.text.isNullOrBlank()
                val descStable = nodeA.contentDescription == matchInB.contentDescription && !nodeA.contentDescription.isNullOrBlank()
                val idStable = nodeA.viewIdResourceName == matchInB.viewIdResourceName && !nodeA.viewIdResourceName.isNullOrBlank()

                if (textStable || descStable || idStable) {
                    stableSelectors.add(
                        Selector(
                            textMatches = if (textStable) nodeA.text else null,
                            contentDescriptionMatches = if (descStable) nodeA.contentDescription else null,
                            viewIdMatches = if (idStable) nodeA.viewIdResourceName else null,
                            classNameMatches = nodeA.className,
                            requireClickable = nodeA.isClickable
                        )
                    )
                }
            }
        }

        return stableSelectors
    }
}
