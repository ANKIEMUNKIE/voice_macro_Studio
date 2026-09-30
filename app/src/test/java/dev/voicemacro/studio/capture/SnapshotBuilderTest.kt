package dev.voicemacro.studio.capture

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SnapshotBuilderTest {

    private fun createNode(
        className: String,
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        bounds: Rect = Rect(0, 0, 100, 100),
        children: List<AccessibilityNodeInfo> = emptyList()
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.className } returns className
        every { node.text } returns text
        every { node.contentDescription } returns desc
        every { node.isClickable } returns clickable
        every { node.isEditable } returns editable
        every { node.isScrollable } returns false
        every { node.isVisibleToUser } returns true
        every { node.isImportantForAccessibility } returns true
        every { node.viewIdResourceName } returns null
        every { node.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(bounds)
        }
        every { node.childCount } returns children.size
        for (i in children.indices) {
            every { node.getChild(i) } returns children[i]
        }
        return node
    }

    @Test
    fun testBuildFiltersNonInteractiveNodesButKeepsStructureInSignature() {
        val button = createNode("android.widget.Button", text = "Submit", clickable = true, bounds = Rect(20, 20, 200, 100))
        val container = createNode("android.widget.LinearLayout", bounds = Rect(10, 10, 1070, 2390), children = listOf(button))
        val root = createNode("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), children = listOf(container))
        
        every { root.packageName } returns "com.test.app"
        every { root.getPackageName() } returns "com.test.app"

        // Mock AccessibilityNodeInfo.obtain(root)
        val obtainedRoot = root // Since it's a mock, we just return the same
        
        val snapshot = SnapshotBuilder.build(root)!!

        assertEquals(1, snapshot.nodes.size)
        assertEquals("Submit", snapshot.nodes[0].text)
        assertEquals("Button", snapshot.nodes[0].className)
        
        assertTrue(snapshot.signature.length == 16)
    }

    @Test
    fun testFindStableSelectors() {
        val btnA = createNode("android.widget.Button", text = "Next", bounds = Rect(20, 20, 200, 100))
        val priceA = createNode("android.widget.TextView", text = "Total: $10", bounds = Rect(20, 120, 200, 200))
        val rootA = createNode("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), children = listOf(btnA, priceA))
        every { rootA.packageName } returns "com.test.app"
        every { rootA.getPackageName() } returns "com.test.app"

        val snapA = SnapshotBuilder.build(rootA)!!

        val btnB = createNode("android.widget.Button", text = "Next", bounds = Rect(20, 20, 200, 100))
        val priceB = createNode("android.widget.TextView", text = "Total: $15", bounds = Rect(20, 120, 200, 200)) // Price changed!
        val rootB = createNode("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), children = listOf(btnB, priceB))
        every { rootB.packageName } returns "com.test.app"
        every { rootB.getPackageName() } returns "com.test.app"

        val snapB = SnapshotBuilder.build(rootB)!!

        assertEquals(snapA.signature, snapB.signature)

        val stableSelectors = SnapshotBuilder.findStableSelectors(snapA, snapB)

        assertEquals(1, stableSelectors.size)
        assertEquals("Next", stableSelectors[0].textMatches)
        assertEquals("Button", stableSelectors[0].classNameMatches)
    }
}
