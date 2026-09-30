package dev.voicemacro.studio.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityParserTest {
    @Test fun defaultsToOneWhenQuantityIsOmitted() {
        assertEquals(QuantityDecision.Valid(1), QuantityParser.parse("choco chip brownie safe"))
    }

    @Test fun parsesDigitsAndEnglishWords() {
        assertEquals(QuantityDecision.Valid(3), QuantityParser.parse("add 3 choco chip brownies"))
        assertEquals(QuantityDecision.Valid(5), QuantityParser.parse("please add five brownies"))
    }

    @Test fun parsesHinglishWords() {
        assertEquals(QuantityDecision.Valid(2), QuantityParser.parse("do choco chip brownie add karo"))
        assertEquals(QuantityDecision.Valid(4), QuantityParser.parse("chaar brownie add karo"))
        assertEquals(QuantityDecision.Valid(5), QuantityParser.parse("paanch brownie add karo"))
    }

    @Test fun englishDoTheWrapperDoesNotMeanQuantityTwo() {
        assertEquals(QuantityDecision.Valid(1), QuantityParser.parse("do the choco chip brownie safe macro"))
    }

    @Test fun rejectsOutOfRangeAndConflictingQuantities() {
        assertTrue(QuantityParser.parse("add 6 brownies") is QuantityDecision.Unsupported)
        assertTrue(QuantityParser.parse("add two or three brownies") is QuantityDecision.Unsupported)
    }
}
