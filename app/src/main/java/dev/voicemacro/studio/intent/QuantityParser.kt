package dev.voicemacro.studio.intent

sealed interface QuantityDecision {
    data class Valid(val quantity: Int) : QuantityDecision
    data object Unsupported : QuantityDecision
}

/** Deterministic, local quantity parsing for the deadline-scoped 1..5 demo. */
object QuantityParser {
    private val supported = mapOf(
        "one" to 1, "ek" to 1,
        "two" to 2, "do" to 2,
        "three" to 3, "teen" to 3,
        "four" to 4, "char" to 4, "chaar" to 4,
        "five" to 5, "panch" to 5, "paanch" to 5
    )
    private val explicitlyUnsupported = setOf(
        "zero", "six", "seven", "eight", "nine", "ten",
        "shunya", "cheh", "chhe", "saat", "aath", "nau", "das"
    )

    fun parse(utterance: String): QuantityDecision {
        val tokens = utterance.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val found = mutableListOf<Int>()
        for ((index, token) in tokens.withIndex()) {
            val numeric = token.toIntOrNull()
            if (numeric != null) {
                if (numeric !in 1..5) return QuantityDecision.Unsupported
                found += numeric
                continue
            }
            if (token in explicitlyUnsupported) return QuantityDecision.Unsupported
            // In "do the macro", do is an English command verb rather than Hinglish two.
            if (token == "do" && tokens.getOrNull(index + 1) == "the") continue
            supported[token]?.let { found += it }
        }

        if (found.isEmpty()) return QuantityDecision.Valid(1)
        val distinct = found.distinct()
        return if (distinct.size == 1) QuantityDecision.Valid(distinct.single())
        else QuantityDecision.Unsupported
    }
}
