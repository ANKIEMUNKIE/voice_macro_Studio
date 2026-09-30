package dev.voicemacro.studio.replay

import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialSelectorTest {
    @Test fun samePositionScoresHighest() {
        assertTrue(SpatialSelector.score(5000, 5000, 5000, 5000) == 1.0)
    }

    @Test fun nearbyCandidateBeatsDistantCandidate() {
        val nearby = SpatialSelector.score(5000, 5000, 5200, 5100)
        val distant = SpatialSelector.score(5000, 5000, 2000, 1000)
        assertTrue(nearby > distant)
    }
}
