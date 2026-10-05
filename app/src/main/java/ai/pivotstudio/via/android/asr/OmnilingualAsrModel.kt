package ai.pivotstudio.via.android.asr

import android.content.Context
import kotlin.math.max

/**
 * Where the Omnilingual ASR CTC model files live once downloaded, and the
 * GitHub Release they come from (NOT Maven Central — see app/libs/README.md
 * and PLAN.md Phase 1 for why sherpa-onnx models ship as release assets).
 *
 * Model: `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`
 * — Meta's Omnilingual ASR, 300M params, int8-quantized, CTC head, covering
 * 1600+ languages including Swahili (the language this app's UI/TTS targets).
 * Release page:
 * https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
 * Docs: https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html
 */
object OmnilingualAsrModel {
    const val RELEASE_TAG = "asr-models"
    const val ARCHIVE_NAME = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12"
    const val DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/$RELEASE_TAG/$ARCHIVE_NAME.tar.bz2"

    /** Files inside the extracted archive directory that [OmnilingualAsrEngine] needs. */
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"

    fun dirFor(context: Context): java.io.File =
        java.io.File(context.filesDir, ARCHIVE_NAME)

    fun isDownloaded(context: Context): Boolean {
        val dir = dirFor(context)
        return java.io.File(dir, MODEL_FILE).exists() && java.io.File(dir, TOKENS_FILE).exists()
    }
}

/** Unused placeholder kept so this file compiles standalone without extra imports elsewhere. */
private fun unused() = max(0, 0)
