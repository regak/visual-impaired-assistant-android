package ai.pivotstudio.via.android.core

import kotlin.math.max
import kotlin.math.min

/**
 * Shared normalized-Levenshtein-similarity scoring, extracted from
 * [ai.pivotstudio.via.android.telephony.SimuRepository]'s original
 * private contact-name-matching implementation so
 * [SwahiliNumberWordConverter] can reuse the exact same proven
 * algorithm instead of duplicating it (PLAN.md Phase 4 — explicit user
 * request: "use the fuzzy matching version" for number-word
 * recognition, after "sifuri" [a real ASR spelling of "sufuri"/zero]
 * wasn't recognized by the old exact-match + hardcoded-variants
 * approach).
 */
object TextSimilarity {
    /** 0f (completely different) to 1f (identical), normalized by the longer string's length. */
    fun levenshteinSimilarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = levenshteinDistance(a, b)
        val maxLen = max(a.length, b.length)
        return 1f - (distance.toFloat() / maxLen.toFloat())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[a.length][b.length]
    }
}
