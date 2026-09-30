package dev.voicemacro.studio.safety

enum class Verdict { CLEAR, SENSITIVE, UNKNOWN }
enum class Reason { NONE, PASSWORD, OTP, PAYMENT, LOGIN, UNREADABLE, OUTSIDE_TARGET, MANUAL_STOP, TREE_TIMEOUT, NODE_LIMIT, MISSING_NODE, EMPTY_TREE, READ_ERROR }
data class SafetyResult(val verdict: Verdict, val reason: Reason)

object SafetyPolicy {
    private fun words(s: String) = Regex("(?iu)(?<![\\p{L}\\p{N}])(?:$s)(?![\\p{L}\\p{N}])")
    private val rules = listOf(
        Reason.PASSWORD to words("password|passcode|पासवर्ड"),
        Reason.OTP to words("otp|one[ -]?time (?:password|code)|verification code|verify (?:your )?(?:phone|mobile|number)|ओटीपी|सत्यापन कोड"),
        Reason.PAYMENT to words("cvv|cvc|upi|payments?|pay now|pay securely|proceed to pay|confirm (?:and pay|payment|order)|place order|card number|expiry date|भुगतान|पेमेंट"),
        Reason.LOGIN to words("log[ -]?in|sign[ -]?in|sign[ -]?up|enter (?:your )?(?:phone|mobile) number|लॉगिन|लॉग इन")
    )
    fun inspectLabel(label: String): Reason = rules.firstOrNull { it.second.containsMatchIn(label) }?.first ?: Reason.NONE
    fun isPasswordInput(inputType: Int): Boolean {
        val kind = inputType and 0x0f
        val variation = inputType and 0x0ff0
        return (kind == 1 && variation in setOf(0x80, 0x90, 0xe0)) || (kind == 2 && variation == 0x10)
    }
}

/** No cached permits: callers must supply a fresh screen inspection for every action. */
class SafetyGate {
    var latchedReason = Reason.NONE
        private set
    fun observe(result: SafetyResult) {
        // A confirmed sensitive boundary is more informative than an earlier incomplete read.
        if (result.verdict == Verdict.SENSITIVE || (result.verdict != Verdict.CLEAR && latchedReason == Reason.NONE)) latchedReason = result.reason
    }
    fun stop() { latchedReason = Reason.MANUAL_STOP }
    fun permits(freshCheck: () -> SafetyResult): Boolean {
        val result = freshCheck()
        observe(result)
        return result.verdict == Verdict.CLEAR && latchedReason == Reason.NONE
    }
    fun resumeManually(freshCheck: () -> SafetyResult): Boolean {
        val result = freshCheck()
        if (result.verdict != Verdict.CLEAR) { observe(result); return false }
        latchedReason = Reason.NONE
        return true
    }
}
