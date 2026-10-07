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
 * Scope deliberately narrow, matching murmur's own scope decision:
 * - Units 0-9 (sufuri/moja/mbili/tatu/nne/tano/sita/saba/nane/tisa)
 * - Tens (kumi, ishirini, thelathini, arobaini, hamsini, sitini,
 *   sabini, themanini, tisini) and compounds ("ishirini na tano" ->
 *   "25")
 * - Magnitudes (mia = hundred, elfu = thousand)
 * - A handful of common ASR misspelling/mishearing variants seen in
 *   real transcripts (e.g. "tanu" for "tano", "mbiri" for "mbili",
 *   "nenne" for "nne") normalized to the canonical word before
 *   matching, rather than trying to enumerate every ASR error — this
 *   is NOT a full Swahili spelling-correction pass, just enough to
 *   cover the misrecognitions actually observed.
 *
 * Deliberately does NOT touch ordinals or non-numeric text — a run of
 * words that doesn't parse cleanly as a number is left untouched
 * rather than guessed at, matching [convert]'s "never corrupt text we
 * aren't confident about" discipline.
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

    /**
     * Common ASR misrecognition/alternate-spelling variants observed in
     * real transcripts, normalized to the canonical word above before
     * matching. Not exhaustive by design — extend as new variants are
     * actually observed, rather than guessing ahead of real evidence.
     */
    private val VARIANTS = mapOf(
        "tanu" to "tano",
        "mbiri" to "mbili",
        "nenne" to "nne",
        "nn" to "nne",
    )

    private val ALL_NUMBER_WORDS = UNITS.keys + TENS.keys + MAGNITUDES.keys + setOf(CONNECTOR) + VARIANTS.keys

    private fun normalize(word: String): String = VARIANTS[word] ?: word

    /** Replaces every run of consecutive Swahili number-words in [text] with digits. */
    fun convert(text: String): String {
        val tokens = text.split(Regex("(?<=\\s)|(?=\\s)")) // keep whitespace as tokens
        val result = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val word = normalize(tokens[i].trim().lowercase().trimEnd(',', '.', '!', '?'))
            if (word.isNotEmpty() && word in ALL_NUMBER_WORDS) {
                var j = i
                val run = mutableListOf<String>()
                while (j < tokens.size) {
                    val w = normalize(tokens[j].trim().lowercase().trimEnd(',', '.', '!', '?'))
                    if (w.isNotEmpty() && w in ALL_NUMBER_WORDS) {
                        run.add(w)
                        j++
                    } else if (tokens[j].isBlank() && j + 1 < tokens.size) {
                        val next = normalize(tokens.getOrNull(j + 1)?.trim()?.lowercase()?.trimEnd(',', '.', '!', '?') ?: "")
                        if (next in ALL_NUMBER_WORDS) {
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
     * Parses a run of Swahili number-words into a digit string.
     * Phone-number-style digit sequences ("saba nne tano", 2+ bare
     * units) are concatenated digit-by-digit with NO separator
     * ("745") — the dominant case for "Andika ujumbe"'s recipient
     * field, matching how Swahili speakers read out phone numbers
     * digit-by-digit. A single cardinal-number phrase with a tens/
     * hundred/thousand word ("ishirini na tano" -> "25", "mia tatu" ->
     * "300") is parsed additively instead. Returns null if the run
     * doesn't parse as a sane number.
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
