package dev.voicemacro.studio.intent

import dev.voicemacro.studio.db.Flow

data class FlowMatch(val flow: Flow, val score: Double)
sealed interface MatchDecision {
    data class Matched(val match: FlowMatch) : MatchDecision
    data class Ambiguous(val candidates: List<FlowMatch>) : MatchDecision
    data object Unknown : MatchDecision
}

object IntentMatcher {
    private val filler = setOf("please", "run", "start", "execute", "do", "can", "you", "the", "flow", "macro")

    fun teachingName(utterance: String): String? {
        val trimmed = utterance.trim()
        val match = Regex("(?iu)^(?:teach|learn|record)(?: me)?(?: a new)?(?: flow| macro)?\\s+(.+)$").find(trimmed)
            ?: return null
        return match.groupValues[1].trim().takeIf { it.length >= 2 }
    }

    fun decide(utterance: String, flows: List<Flow>): MatchDecision {
        val spoken = canonical(utterance)
        if (spoken.isBlank()) return MatchDecision.Unknown
        val ranked = flows.map { FlowMatch(it, similarity(spoken, canonical(it.name))) }
            .filter { it.score >= 0.62 }
            .sortedByDescending { it.score }
        if (ranked.isEmpty()) return MatchDecision.Unknown
        if (ranked.size > 1 && ranked[0].score - ranked[1].score < 0.08) {
            return MatchDecision.Ambiguous(ranked.take(3))
        }
        return MatchDecision.Matched(ranked.first())
    }

    internal fun canonical(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in filler }
        .joinToString(" ")

    internal fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val aTokens = a.split(' ').toSet()
        val bTokens = b.split(' ').toSet()
        val union = (aTokens union bTokens).size.coerceAtLeast(1)
        val jaccard = (aTokens intersect bTokens).size.toDouble() / union
        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        val edit = 1.0 - levenshtein(compactA, compactB).toDouble() / maxOf(compactA.length, compactB.length)
        val contains = if (compactA.contains(compactB) || compactB.contains(compactA)) 0.85 else 0.0
        return maxOf(jaccard, edit, contains)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
