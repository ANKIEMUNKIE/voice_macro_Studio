package dev.voicemacro.studio.safety

import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Transient inspection only; no screen text is retained or logged.
 *
 * Three-tier visibility strategy:
 *   Tier 1 – isVisibleToUser (standard)
 *   Tier 2 – on-screen bounds fallback (apps that misreport isVisibleToUser)
 *   Tier 3 – label-anywhere fallback (apps like Swiggy where all 160 nodes
 *             report invisible AND zero/off-screen bounds but still carry text)
 *
 * Tier 3 is only reached when tiers 1+2 found zero visible nodes. It allows
 * sensitive-marker detection and CLEAR classification on apps that hide visibility
 * metadata entirely. We never store the labels; we only check them transiently.
 */
object SensitiveScreenReader {

    @Synchronized
    @Suppress("DEPRECATION")
    fun inspect(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): SafetyResult {
        // --- Pass 1: standard + bounds-fallback traversal ---
        val pass1 = traverseNodes(root, screenWidth, screenHeight, useVisibilityOnly = false)
        if (pass1 != null) return pass1          // sensitive hit, timeout, node-limit, or read error
        // pass1 == null means we exhausted the tree without a sensitive hit; check counts
        val (visible1, labels1, incomplete1) = lastCounts
        if (incomplete1) return unknown(Reason.MISSING_NODE)
        if (visible1 > 0 && labels1 > 0) return SafetyResult(Verdict.CLEAR, Reason.NONE)

        // --- Pass 2 (Tier 3): label-anywhere fallback ---
        // Reached when tiers 1+2 found zero visible/labeled nodes.
        // Re-scan accepting any node that carries text/description, regardless of bounds.
        val pass2 = traverseNodes(root, screenWidth, screenHeight, useVisibilityOnly = false, labelAnywhereMode = true)
        if (pass2 != null) return pass2
        val (_, labels2, incomplete2) = lastCounts
        return when {
            incomplete2 -> unknown(Reason.MISSING_NODE)
            labels2 == 0 -> {
                // Diagnose what this empty tree actually looks like
                val structure = mutableListOf<String>()
                val q = ArrayDeque<AccessibilityNodeInfo>()
                q.add(AccessibilityNodeInfo.obtain(root))
                while (q.isNotEmpty() && structure.size < 50) {
                    val n = q.removeFirst()
                    structure.add(n.className?.toString()?.substringAfterLast('.') ?: "null")
                    for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
                    n.recycle()
                }
                while (q.isNotEmpty()) q.removeFirst().recycle()
                android.util.Log.d("VoiceMacroSafety", "EMPTY_TREE structure (up to 50): ${structure.joinToString()}")
                unknown(Reason.EMPTY_TREE)
            }
            else -> SafetyResult(Verdict.CLEAR, Reason.NONE)
        }
    }

    // Shared mutable counts written by traverseNodes, read immediately after.
    // Single-threaded (main thread via Handler), so no synchronization needed.
    private var lastCounts = Triple(0, 0, false)

    /**
     * BFS traversal returning a non-null SafetyResult only on an early exit
     * (sensitive hit, resource limit, or error). Returns null when the full tree
     * is exhausted without a hit; call-site reads [lastCounts] for statistics.
     *
     * @param labelAnywhereMode  When true, accept any node that has text/description
     *                           as a "visible" node (Tier 3 fallback for Swiggy-style apps).
     */
    @Suppress("DEPRECATION")
    private fun traverseNodes(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        useVisibilityOnly: Boolean,
        labelAnywhereMode: Boolean = false
    ): SafetyResult? {
        val queue = ArrayDeque<AccessibilityNodeInfo>(256)
        queue.add(AccessibilityNodeInfo.obtain(root))
        var count = 0
        var visible = 0
        var labels = 0
        var incomplete = false
        val start = SystemClock.uptimeMillis()
        try {
            while (queue.isNotEmpty()) {
                if (++count > 1500) { lastCounts = Triple(visible, labels, incomplete); return unknown(Reason.NODE_LIMIT) }
                if (SystemClock.uptimeMillis() - start > 750) { lastCounts = Triple(visible, labels, incomplete); return unknown(Reason.TREE_TIMEOUT) }
                val node = queue.removeFirst()
                try {
                    val hasLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() ||
                                   (labelAnywhereMode && !node.hintText.isNullOrBlank()) ||
                                   (labelAnywhereMode && android.os.Build.VERSION.SDK_INT >= 30 && !node.stateDescription.isNullOrBlank())
                    val onScreen = if (labelAnywhereMode) hasLabel
                                   else isOnScreen(node, screenWidth, screenHeight)
                    if (onScreen) {
                        visible++
                        if (!labelAnywhereMode) {
                            // Standard password-metadata check (not in label-anywhere mode; no actions here)
                            if (node.isPassword || SafetyPolicy.isPasswordInput(node.inputType))
                                return SafetyResult(Verdict.SENSITIVE, Reason.PASSWORD)
                        }
                        // Never read editable field values.
                        if (!node.isEditable && !node.className.toString().contains("EditText")) {
                            val labelsToCheck = buildList {
                                add(node.text)
                                add(node.contentDescription)
                                if (labelAnywhereMode) add(node.hintText)
                                if (labelAnywhereMode && android.os.Build.VERSION.SDK_INT >= 30) add(node.stateDescription)
                            }
                            for (label in labelsToCheck) {
                                if (!label.isNullOrBlank()) labels++
                                val reason = SafetyPolicy.inspectLabel(label?.toString().orEmpty())
                                if (reason != Reason.NONE) return SafetyResult(Verdict.SENSITIVE, reason)
                            }
                        }
                    }
                    for (i in 0 until node.childCount) {
                        if (queue.size >= 1500) { lastCounts = Triple(visible, labels, incomplete); return unknown(Reason.NODE_LIMIT) }
                        val child = node.getChild(i)
                        if (child == null) incomplete = true else queue.add(child)
                    }
                } finally { node.recycle() }
            }
            lastCounts = Triple(visible, labels, incomplete)
            return null   // clean exit; let call-site inspect counts
        } catch (_: RuntimeException) {
            lastCounts = Triple(visible, labels, incomplete)
            return unknown(Reason.READ_ERROR)
        } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
    }

    private fun unknown(reason: Reason) = SafetyResult(Verdict.UNKNOWN, reason)

    /**
     * Tier 1 + Tier 2 visibility check.
     * Some apps expose useful bounds but incorrectly mark every node invisible.
     */
    private fun isOnScreen(node: AccessibilityNodeInfo, width: Int, height: Int): Boolean {
        if (node.isVisibleToUser) return true
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        // Accept node if it has any non-degenerate bounds that could be on screen.
        // Relaxed from strict intersection: even partially off-screen is fine.
        return !bounds.isEmpty && (bounds.right > 0 || bounds.bottom > 0)
    }
}
