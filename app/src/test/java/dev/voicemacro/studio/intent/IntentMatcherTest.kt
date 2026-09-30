package dev.voicemacro.studio.intent

import dev.voicemacro.studio.db.Flow
import org.junit.Assert.*
import org.junit.Test

class IntentMatcherTest {
    private fun flow(name: String) = Flow(name = name, description = null, packageName = "com.test")

    @Test fun extractsTeachingCommand() {
        assertEquals("order paneer from Zomato", IntentMatcher.teachingName("Teach me a new flow order paneer from Zomato"))
        assertNull(IntentMatcher.teachingName("run order paneer"))
    }

    @Test fun exactAndSpeechTypoMatchCorrectFlow() {
        val flows = listOf(flow("order paneer"), flow("test macro"))
        assertEquals("test macro", ((IntentMatcher.decide("start test micro", flows) as MatchDecision.Matched).match.flow.name))
    }

    @Test fun wrapperPhraseMatchesLearnedFlow() {
        val result = IntentMatcher.decide(
            "run the add choco chip brownie safe macro",
            listOf(flow("choco chip brownie safe"))
        )
        assertTrue(result is MatchDecision.Matched)
        assertEquals("choco chip brownie safe", (result as MatchDecision.Matched).match.flow.name)
    }

    @Test fun quantityPhraseStillMatchesLearnedFlow() {
        val result = IntentMatcher.decide(
            "add three choco chip brownie safe",
            listOf(flow("choco chip brownie safe"))
        )
        assertTrue(result is MatchDecision.Matched)
    }

    @Test fun unrelatedCommandIsUnknown() {
        assertTrue(IntentMatcher.decide("call my friend", listOf(flow("order paneer"))) is MatchDecision.Unknown)
    }

    @Test fun closeCandidatesAreAmbiguous() {
        val result = IntentMatcher.decide("order paneer", listOf(flow("order paneer zomato"), flow("order paneer swiggy")))
        assertTrue(result is MatchDecision.Ambiguous)
    }
}
