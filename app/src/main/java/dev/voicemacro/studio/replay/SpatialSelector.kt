package dev.voicemacro.studio.replay

import kotlin.math.hypot

/** Scores a candidate against a recorded normalized center in the 0..10000 range. */
object SpatialSelector {
    fun score(recordedX: Int, recordedY: Int, candidateX: Int, candidateY: Int): Double {
        val distance = hypot((recordedX - candidateX).toDouble(), (recordedY - candidateY).toDouble())
        return (1.0 - distance / 6000.0).coerceIn(0.0, 1.0)
    }
}
