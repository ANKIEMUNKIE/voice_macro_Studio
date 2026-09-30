package dev.voicemacro.studio.safety

import org.junit.Assert.*
import org.junit.Test

class SafetyPolicyTest {
    @Test fun sensitiveLabels() {
        mapOf("Enter password" to Reason.PASSWORD, "OTP" to Reason.OTP,
            "Enter verification code" to Reason.OTP, "CVV" to Reason.PAYMENT,
            "Select payment method" to Reason.PAYMENT, "Pay now ₹250" to Reason.PAYMENT,
            "Place order" to Reason.PAYMENT, "Sign in" to Reason.LOGIN,
            "Enter your mobile number" to Reason.LOGIN, "भुगतान करें" to Reason.PAYMENT,
            "ओटीपी" to Reason.OTP, "पासवर्ड" to Reason.PASSWORD
        ).forEach { (label, reason) -> assertEquals(label, reason, SafetyPolicy.inspectLabel(label)) }
    }
    @Test fun normalBrowsingAndSubstrings() {
        listOf("Hotpot", "Papaya", "Cart", "Add to cart", "Search restaurants", "2 garlic breads", "Delivery address")
            .forEach { assertEquals(it, Reason.NONE, SafetyPolicy.inspectLabel(it)) }
    }
    @Test fun passwordMetadata() {
        listOf(0x81, 0x91, 0xe1, 0x12, 0x80081).forEach { assertTrue(SafetyPolicy.isPasswordInput(it)) }
        listOf(0, 1, 2, 3, 0x21).forEach { assertFalse(SafetyPolicy.isPasswordInput(it)) }
    }
    private val clear = SafetyResult(Verdict.CLEAR, Reason.NONE)
    private val sensitive = SafetyResult(Verdict.SENSITIVE, Reason.PAYMENT)
    @Test fun stopRemainsLatchedUntilManualSafeRecheck() {
        val gate = SafetyGate()
        assertFalse(gate.permits { sensitive })
        assertFalse(gate.permits { clear })
        assertFalse(gate.resumeManually { sensitive })
        assertTrue(gate.resumeManually { clear })
        assertTrue(gate.permits { clear })
    }
    @Test fun unknownNeverPermits() {
        listOf(Reason.UNREADABLE, Reason.OUTSIDE_TARGET).forEach {
            val gate = SafetyGate()
            assertFalse(gate.permits { SafetyResult(Verdict.UNKNOWN, it) })
            assertFalse(gate.permits { clear })
        }
    }
    @Test fun everyActionRechecksAndManualStopBlocks() {
        val gate = SafetyGate()
        assertTrue(gate.permits { clear })
        assertFalse(gate.permits { sensitive })
        assertTrue(gate.resumeManually { clear })
        gate.stop()
        assertFalse(gate.permits { clear })
    }
    @Test fun sensitiveReasonSurvivesLaterIncompleteReads() {
        val gate = SafetyGate()
        gate.observe(SafetyResult(Verdict.UNKNOWN, Reason.TREE_TIMEOUT))
        gate.observe(sensitive)
        assertEquals(Reason.PAYMENT, gate.latchedReason)
        assertFalse(gate.permits { SafetyResult(Verdict.UNKNOWN, Reason.MISSING_NODE) })
        assertEquals(Reason.PAYMENT, gate.latchedReason)
        assertFalse(gate.permits { clear })
    }
    /** Regression: 'Place order' label must match even in Tier 3 label-anywhere scan. */
    @Test fun paymentLabelInTier3StyleScan() {
        // The label-anywhere scan reads any non-editable text; payment labels must still trigger SENSITIVE.
        listOf("Place order", "Pay now ₹250", "Confirm payment", "UPI", "CVV").forEach { label ->
            assertNotEquals("$label should be sensitive", Reason.NONE, SafetyPolicy.inspectLabel(label))
        }
    }
    /** 'Add to cart' and delivery labels must not trigger in Tier 3 style scan. */
    @Test fun normalLabelsDoNotTriggerInTier3StyleScan() {
        listOf("Add to cart", "Delivery address", "Search restaurants", "Biryani", "2 items").forEach { label ->
            assertEquals("$label should be clear", Reason.NONE, SafetyPolicy.inspectLabel(label))
        }
    }
}

