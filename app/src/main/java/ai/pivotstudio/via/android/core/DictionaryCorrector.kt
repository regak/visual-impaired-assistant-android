package ai.pivotstudio.via.android.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Post-ASR Swahili spelling correction — "Dict v2 gated" from the
 * dictionary-correction research program (see
 * `docs/asr-benchmark/dictionary-correction-v2.md` in this repo).
 *
 * Does NOT touch the ASR engine itself ([ai.pivotstudio.via.android.asr.OmnilingualAsrEngine]
 * stays exactly as-is). This is a pure post-processing pass: take the raw
 * transcript string the engine returns, look up each word against a
 * 71,528-word Swahili vocabulary (Hunspell `sw_TZ` base + prefixed verb
 * forms + two flat wordlists, merged — built during the research phase),
 * and replace only words that have EXACTLY ONE dictionary word within
 * edit-distance 1. Words with zero matches, or two-or-more equally-close
 * matches (an ambiguous tie), are left untouched — "abstain on
 * uncertainty" is the whole point of the gated version, and is what took
 * the measured improve:worse ratio from 2.7:1 (ungated) to 12:1 (gated)
 * on the project's 96-clip real-ASR-output benchmark.
 *
 * Algorithm is a direct, faithful port of `correct_text_gated()` /
 * `nearest_word_gated()` from the Python research script
 * (`run_dict_correction.py`, not shipped in this repo — scratch-only).
 * max_dist is fixed at 1 here (not 2) because 1 was the config that
 * produced the best measured result (WER 0.674 -> 0.658, 12 improved / 1
 * worse); max_dist=2 was tested and performed worse due to looser
 * matching pulling in more spurious ties.
 *
 * Vocabulary ships as a plain-text asset (`assets/swahili_vocab.txt`,
 * ~657KB, one lowercase word per line) rather than the Python pickle —
 * loaded once into an in-memory [Set] + length-bucketed [Map] mirroring
 * the Python `vocab`/`by_len` structures, for the same O(candidates in
 * length band) lookup strategy instead of a full vocab scan per word.
 *
 * Measured Python-side latency (96-item benchmark, single-threaded
 * CPython): mean 629ms/item, max 3.6s/item. This is too slow to block a
 * dictation result on — callers MUST treat [correct] as a background
 * step (see [ai.pivotstudio.via.android.core.DictationController] usage:
 * show the raw transcript immediately, then swap in the corrected text
 * when this returns), never as something the UI thread waits on.
 */
class DictionaryCorrector(private val context: Context) {

    data class CorrectionResult(
        val correctedText: String,
        /** (original word, corrected word, edit distance) for each word actually changed. */
        val changes: List<Triple<String, String, Int>>,
        /** Words that had an ambiguous tie and were left untouched. */
        val abstentions: List<String>,
    )

    private var vocab: Set<String> = emptySet()
    private var byLen: Map<Int, List<String>> = emptyMap()
    private var loaded = false

    /** Must be called once, off the main thread, before [correct]. Cheap (~tens of ms). */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val words = context.assets.open(VOCAB_ASSET).bufferedReader(Charsets.UTF_8)
            .useLines { lines -> lines.filter { it.isNotBlank() }.toList() }
        vocab = words.toHashSet()
        byLen = words.groupBy { it.length }
        loaded = true
        Log.i(TAG, "Loaded ${vocab.size} Swahili vocabulary words for post-ASR correction")
    }

    /**
     * Correct [text] against the loaded vocabulary. Safe to call from any
     * dispatcher the caller is already on for background work — internally
     * runs on [Dispatchers.Default] since this is CPU-bound, not I/O.
     */
    suspend fun correct(text: String, maxDist: Int = DEFAULT_MAX_DIST, minWordLen: Int = DEFAULT_MIN_WORD_LEN): CorrectionResult =
        withContext(Dispatchers.Default) {
            check(loaded) { "DictionaryCorrector.load() was not called" }
            val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val out = ArrayList<String>(tokens.size)
            val changes = ArrayList<Triple<String, String, Int>>()
            val abstentions = ArrayList<String>()

            for (tok in tokens) {
                val core = tok.filter { it.isLetter() }
                if (core.length < minWordLen || core.length != tok.length) {
                    // Short, punctuation-containing, or digit-containing tokens are left
                    // untouched — mirrors the Python `core.isalpha()` guard, avoiding
                    // over-aggressive correction on numbers/short function words.
                    out.add(tok)
                    continue
                }
                val lower = core.lowercase()
                if (lower in vocab) {
                    out.add(tok)
                    continue
                }
                val (candidate, dist, wasTie) = nearestWordGated(lower, maxDist)
                when {
                    wasTie -> {
                        abstentions.add(tok)
                        out.add(tok)
                    }
                    candidate != null -> {
                        changes.add(Triple(tok, candidate, dist ?: -1))
                        out.add(candidate)
                    }
                    else -> out.add(tok)
                }
            }
            CorrectionResult(out.joinToString(" "), changes, abstentions)
        }

    /** Mirrors Python `nearest_word_gated()` exactly: returns (candidate, distance, wasTie). */
    private fun nearestWordGated(word: String, maxDist: Int): Triple<String?, Int?, Boolean> {
        val bestAtDist = HashMap<Int, MutableList<String>>()
        var bestDist = maxDist + 1
        val lo = max(1, word.length - maxDist)
        val hi = word.length + maxDist
        for (len in lo..hi) {
            val candidates = byLen[len] ?: continue
            for (cand in candidates) {
                val d = editDistance(word, cand, bestDist)
                if (d <= maxDist) {
                    bestAtDist.getOrPut(d) { ArrayList() }.add(cand)
                    if (d < bestDist) bestDist = d
                }
            }
        }
        if (bestDist > maxDist || bestAtDist[bestDist] == null) return Triple(null, null, false)
        val winners = bestAtDist.getValue(bestDist)
        return if (winners.size > 1) {
            Triple(null, bestDist, true) // ambiguous tie — abstain
        } else {
            Triple(winners[0], bestDist, false)
        }
    }

    /** Banded Levenshtein distance with early-exit, same shape as the Python `_edit_distance`. */
    private fun editDistance(a: String, b: String, maxDist: Int): Int {
        val n = a.length
        val m = b.length
        if (abs(n - m) > maxDist) return maxDist + 1
        var prev = IntArray(m + 1) { it }
        for (i in 1..n) {
            val cur = IntArray(m + 1)
            cur[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[m]
    }

    companion object {
        private const val TAG = "VIA/DictCorrector"
        private const val VOCAB_ASSET = "swahili_vocab.txt"

        /** max_dist=1 was the best-measured config (see class doc) — do not change without re-benchmarking. */
        const val DEFAULT_MAX_DIST = 1
        const val DEFAULT_MIN_WORD_LEN = 3
    }
}
