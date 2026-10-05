package ai.pivotstudio.via.android.asr

import android.content.Context
import kotlin.math.max

/**
 * Where the Omnilingual ASR CTC model files live once downloaded, and
 * which GitHub Release they come from (NOT Maven Central — see
 * app/libs/README.md and PLAN.md Phase 1 for why sherpa-onnx models ship
 * as release assets).
 *
 * Model: `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`
 * — Meta's Omnilingual ASR, 300M params, int8-quantized, CTC head, covering
 * 1600+ languages including Swahili (the language this app's UI/TTS targets).
 * Upstream release page:
 * https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
 * Docs: https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html
 *
 * The two files this app actually needs (`model.int8.onnx` + `tokens.txt`)
 * were extracted from that upstream `.tar.bz2` archive and re-uploaded as
 * individual assets to THIS repo's own `models-v1` GitHub Release
 * (`regak/visual-impaired-assistant-android`), matching the sibling
 * `murmur-android` project's pattern of re-hosting under its own release
 * for download stability — see [ai.pivotstudio.via.android.core.ModelDownloader]
 * for the actual fetch URLs.
 */
object OmnilingualAsrModel {
    /** Files inside the model directory that [OmnilingualAsrEngine] needs. */
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"

    /**
     * App-private directory the model files live in once downloaded by
     * [ai.pivotstudio.via.android.core.ModelDownloader]:
     * `context.filesDir/models/omnilingual-asr-300m-ctc-int8/`. Lives
     * under a shared `models/` root (not bundled in the APK) so the
     * directory structure matches the sibling `murmur-android` project's
     * `ModelDownloader` pattern — see PLAN.md Phase 1.
     */
    fun dirFor(context: Context): java.io.File =
        java.io.File(java.io.File(context.filesDir, "models"), "omnilingual-asr-300m-ctc-int8")

    fun isDownloaded(context: Context): Boolean {
        val dir = dirFor(context)
        return java.io.File(dir, MODEL_FILE).exists() && java.io.File(dir, TOKENS_FILE).exists()
    }
}

/** Unused placeholder kept so this file compiles standalone without extra imports elsewhere. */
private fun unused() = max(0, 0)
