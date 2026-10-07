package ai.pivotstudio.via.android.core

/**
 * Converts spoken Swahili number words to digits ("sufuri saba nne
 * sufuri tano sufuri mbili tatu tano" -> "0745032350"), explicit user
 * request/screenshot: ASR transcribes a dictated phone number as
 * Swahili number WORDS, not digits, making "Andika ujumbe"'s recipient
 * readback unreadable and the number itself useless for actually
 * resolving/dialing. Ported from the sibling `murmur-android` project's
 * `core/NumberWordConverter.kt` pattern (same proven approach: a
 * dedicated post-ASR text-normalization pass, not asking the ASR model
 * itself to output digits — speech models transcribe what was said,
 * reformatting is correctly a separate step).
 *
 * Matching strategy: FUZZY, via [TextSimilarity.levenshteinSimilarity]
 * against the canonical word list — NOT exact string equality. This
 * replaced an earlier exact-match + hardcoded-variants-map
 * implementation after an explicit user bug report: "sifuri" (a real
 * ASR transcription of "sufuri"/zero) wasn't in the hardcoded variant
 * list and printed unconverted, and any future misrecognition would
 * have hit the same wall — fuzzy matching (the same approach already
 * proven by [ai.pivotstudio.via.android.telephony.SimuRepository]'s
 * contact-name matching) generalizes to unseen misspellings instead of
 * requiring every variant to be predicted and hand-added in advance.
 *
 * Scope deliberately narrow, matching murmur's own scope decision:
 * - Units 0-9 (sufuri/moja/mbili/tatu/nne/tano/sita/saba/nane/tisa)
 * - Tens (kumi, ishirini, thelathini, arobaini, hamsini, sitini,
 *   sabini, themanini, tisini) and compounds ("ishirini na tano" ->
 *   "25")
 * - Magnitudes (mia = hundred, elfu = thousand)
 *
 * Deliberately does NOT touch ordinals or non-numeric text — a run of
 * words that doesn't fuzzy-match any canonical number word above
 * [MIN_SIMILARITY_THRESHOLD] is left untouched rather than guessed at.
 */
object SwahiliNumberWordConverter {

    private val UNITS = mapOf(
        "sufuri" to 0, "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4,
        "tano" to 5, "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9,
    )
    private val TENS = mapOf(
        "kumi" to 10, "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
        "hamsini" to 50, "sitini" to 60, "sabini" to 70, "themanini" to 80, "tisini" to 90,
    )
    private val MAGNITUDES = mapOf("mia" to 100, "elfu" to 1000)
    private const val CONNECTOR = "na" // "and", e.g. "ishirini na tano" = 25

    /** Every canonical number word this converter recognizes, for fuzzy scoring. */
    private val CANONICAL_WORDS: List<String> =
        (UNITS.keys + TENS.keys + MAGNITUDES.keys + setOf(CONNECTOR)).toList()

    /**
     * Below this normalized-similarity score, a word is not considered
     * a plausible number word at all — kept deliberately high (vs.
     * [ai.pivotstudio.via.android.telephony.SimuRepository]'s 0.4f for
     * free-form contact names) because number words are short (as low
     * as 3-4 letters for "moja"/"nne"/"tatu"), so a looser threshold
     * risks misfiring on ordinary Swahili words in a sentence. Tuned
     * via spot-checking real transcript words AND common non-number
     * Swahili words after an explicit user bug report: a looser 0.65
     * threshold correctly caught "sifuri"~"sufuri" (0.83) and
     * "mbiri"~"mbili" (0.8), but ALSO false-positived "sawa" ("okay")
     * as "saba" (0.75) and had an unresolved tie between "tano" and
     * "tatu" for "tanu" (both 0.75) — i.e. it could silently convert
     * an ordinary word or pick the WRONG digit, which is worse than
     * not converting at all. 0.8 plus the tie-rejection below trades
     * a few uncaught variants ("tanu", "nenne" now fall through
     * unconverted) for never guessing wrong.
     */
    private const val MIN_SIMILARITY_THRESHOLD = 0.8f

    /**
     * Fuzzy-matches [word] against the canonical number-word list;
     * null if nothing scores at/above [MIN_SIMILARITY_THRESHOLD], OR
     * if the top two candidates are tied (within 0.001) and point to
     * DIFFERENT digits/words — an ambiguous match is treated the same
     * as no match, since silently picking one of two equally-likely
     * wrong digits is worse than leaving the original word untouched.
     */
    private fun canonicalOf(word: String): String? {
        if (word.isEmpty()) return null
        val scored = CANONICAL_WORDS
            .map { it to TextSimilarity.levenshteinSimilarity(word, it) }
            .sortedByDescending { it.second }
        val (best, bestScore) = scored.firstOrNull() ?: return null
        if (bestScore < MIN_SIMILARITY_THRESHOLD) return null
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && runnerUp.first != best && runnerUp.second >= bestScore - 0.001f) return null
        return best
    }

    /** Replaces every run of consecutive Swahili number-words in [text] with digits. */
    fun convert(text: String): String {
        val tokens = text.split(Regex("(?<=\\s)|(?=\\s)")) // keep whitespace as tokens
        val result = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val word = tokens[i].trim().lowercase().trimEnd(',', '.', '!', '?')
            val canonical = canonicalOf(word)
            if (canonical != null) {
                var j = i
                val run = mutableListOf<String>()
                while (j < tokens.size) {
                    val w = tokens[j].trim().lowercase().trimEnd(',', '.', '!', '?')
                    val c = canonicalOf(w)
                    if (c != null) {
                        run.add(c)
                        j++
                    } else if (tokens[j].isBlank() && j + 1 < tokens.size) {
                        val next = tokens.getOrNull(j + 1)?.trim()?.lowercase()?.trimEnd(',', '.', '!', '?') ?: ""
                        if (canonicalOf(next) != null) {
                            j++
                        } else {
                            break
                        }
                    } else {
                        break
                    }
                }
                val digits = wordsToDigits(run)
                if (digits != null) {
                    result.append(digits)
                } else {
                    for (k in i until j) result.append(tokens[k])
                }
                i = j
            } else {
                result.append(tokens[i])
                i++
            }
        }
        return result.toString()
    }

    /**
     * Parses a run of (already-canonicalized) Swahili number-words into
     * a digit string. Phone-number-style digit sequences ("saba nne
     * tano", 2+ bare units) are concatenated digit-by-digit with NO
     * separator ("745") — the dominant case for "Andika ujumbe"'s
     * recipient field, matching how Swahili speakers read out phone
     * numbers digit-by-digit. A single cardinal-number phrase with a
     * tens/hundred/thousand word ("ishirini na tano" -> "25", "mia
     * tatu" -> "300") is parsed additively instead. Returns null if the
     * run doesn't parse as a sane number.
     */
    private fun wordsToDigits(words: List<String>): String? {
        if (words.isEmpty()) return null

        // 2+ bare units in a row with no tens/hundred/thousand word at
        // all -> digit-by-digit phone-number-style speech, concatenated
        // with no separator (same reasoning as murmur-android's own
        // phone-number handling).
        if (words.size >= 2 && words.all { it in UNITS }) {
            return words.joinToString("") { UNITS.getValue(it).toString() }
        }

        val hasMagnitude = words.any { it in MAGNITUDES }
        if (hasMagnitude || words.any { it in TENS }) {
            var total = 0L
            var current = 0L
            var sawAnything = false
            for (w in words) {
                when {
                    w == CONNECTOR -> continue
                    w in UNITS -> { current += UNITS.getValue(w); sawAnything = true }
                    w in TENS -> { current += TENS.getValue(w); sawAnything = true }
                    w == "mia" -> { current = (if (current == 0L) 1L else current) * 100; sawAnything = true }
                    w == "elfu" -> {
                        total += (if (current == 0L) 1L else current) * 1000
                        current = 0L
                        sawAnything = true
                    }
                    else -> return null
                }
            }
            if (!sawAnything) return null
            return (total + current).toString()
        }

        // A single bare unit word on its own ("sufuri" alone, "tatu"
        // alone) -- just that one digit.
        if (words.size == 1 && words[0] in UNITS) {
            return UNITS.getValue(words[0]).toString()
        }

        return null
    }
}
