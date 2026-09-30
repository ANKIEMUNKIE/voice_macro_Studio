package dev.voicemacro.studio.replay

/** Compares recorded UI labels while ignoring prices and changing numeric state. */
object SelectorTextMatcher {
    fun score(expected: String, actual: String): Double {
        val expectedCanonical = canonical(expected)
        val actualCanonical = canonical(actual)
        if (expectedCanonical.isBlank() || actualCanonical.isBlank()) return 0.0
        if (expectedCanonical == actualCanonical) return 1.0

        val expectedTokens = expectedCanonical.split(' ').filter { it.isNotBlank() }
        val actualTokens = actualCanonical.split(' ').filter { it.isNotBlank() }
        if (expectedTokens.isEmpty() || actualTokens.isEmpty()) return 0.0

        if (actualCanonical.contains(expectedCanonical) || expectedCanonical.contains(actualCanonical)) {
            return if (minOf(expectedTokens.size, actualTokens.size) >= 2) 0.95 else 0.9
        }

        val expectedSet = expectedTokens.toSet()
        val actualSet = actualTokens.toSet()
        val common = (expectedSet intersect actualSet).size.toDouble()
        val recall = common / expectedSet.size
        val precision = common / actualSet.size
        return (0.75 * recall) + (0.25 * precision)
    }

    fun matches(expected: String, actual: String): Boolean {
        val expectedCanonical = canonical(expected)
        val actualCanonical = canonical(actual)
        val stableTokenCount = expectedCanonical.split(' ').count { it.isNotBlank() }
        if (stableTokenCount == 1) return expectedCanonical == actualCanonical
        val threshold = if (stableTokenCount <= 2) 0.9 else 0.68
        return score(expected, actual) >= threshold
    }

    /** Balanced score for a local item-card context; large menu ancestors score poorly. */
    fun contextScore(expected: String, actual: String): Double {
        val expectedTokens = canonical(expected).split(' ').filter { it.isNotBlank() }.toSet()
        val actualTokens = canonical(actual).split(' ').filter { it.isNotBlank() }.toSet()
        if (expectedTokens.isEmpty() || actualTokens.isEmpty()) return 0.0
        val common = (expectedTokens intersect actualTokens).size.toDouble()
        if (common == 0.0) return 0.0
        val recall = common / expectedTokens.size
        val precision = common / actualTokens.size
        return (2.0 * recall * precision) / (recall + precision)
    }

    internal fun canonical(value: String): String = value.lowercase()
        .replace(Regex("[₹$€£]\\s*[0-9][0-9,]*(?:\\.[0-9]+)?"), " ")
        .replace(Regex("(?<![\\p{L}])[0-9]+(?:\\.[0-9]+)?(?![\\p{L}])"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
