package dev.voicemacro.studio.replay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorTextMatcherTest {
    @Test fun exactTextMatches() {
        assertTrue(SelectorTextMatcher.matches("Add item", "Add item"))
    }

    @Test fun changingPriceAndQuantityDoNotBreakStableLabel() {
        assertTrue(
            SelectorTextMatcher.matches(
                "Paneer Lababdar Rice LunchBox Add item ₹239 quantity 1",
                "Paneer Lababdar Rice LunchBox Add item ₹259 quantity 2"
            )
        )
    }

    @Test fun unrelatedItemDoesNotMatch() {
        assertFalse(
            SelectorTextMatcher.matches(
                "Paneer Lababdar Rice LunchBox Add item",
                "Chocolate Brownie Add item"
            )
        )
    }

    @Test fun aSingleGenericTokenRequiresAnExactLabel() {
        assertFalse(SelectorTextMatcher.matches("Add", "Add more items"))
    }

    @Test fun itemCardContextToleratesDynamicValues() {
        assertTrue(
            SelectorTextMatcher.contextScore(
                "Paneer Lababdar Rice LunchBox ₹239 ADD",
                "Paneer Lababdar Rice LunchBox ₹259 ADD"
            ) >= 0.68
        )
    }

    @Test fun wholeMenuDoesNotMasqueradeAsTheItemCard() {
        assertFalse(
            SelectorTextMatcher.contextScore(
                "Paneer Lababdar Rice LunchBox ADD",
                "Dal Makhani Rice Bowl ADD Paneer Lababdar Rice LunchBox ADD Chocolate Brownie ADD Gulab Jamun ADD"
            ) >= 0.68
        )
    }
}
