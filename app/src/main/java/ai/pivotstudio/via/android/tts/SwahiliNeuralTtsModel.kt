package ai.pivotstudio.via.android.tts

import android.content.Context
import java.io.File

/**
 * Where the self-hosted neural Swahili TTS voice lives once downloaded,
 * and which files it consists of — mirrors
 * [ai.pivotstudio.via.android.asr.OmnilingualAsrModel]'s pattern exactly.
 *
 * Model: `vits-piper-sw_CD-lanfrica-medium` — a genuine human-recorded
 * Swahili (Congo DRC dialect) VITS/Piper voice, NOT the robotic
 * phonetic-fallback voice Android's system `TextToSpeech` was using
 * (see PLAN.md Phase 4 TTS-quality items: user confirmed by diagnostic
 * row that their device's engine, `com.xiaomi.mibrain.speech`, reported
 * `UNAVAILABLE_FALLBACK_DEFAULT` for Swahili). User listened to a sample
 * of this exact voice before approving ("It sounds natural").
 *
 * Upstream source (sherpa-onnx's own re-hosting of the Piper voice):
 * https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-sw_CD-lanfrica-medium.tar.bz2
 * Demo page: https://k2-fsa.github.io/sherpa/onnx/tts/all/Swahili/vits-piper-sw_CD-lanfrica-medium.html
 *
 * Re-hosted on THIS repo's `models-v1` GitHub Release (same release tag
 * the ASR model files already live on), for the same download-stability
 * reason documented in [ai.pivotstudio.via.android.asr.OmnilingualAsrModel]
 * — an upstream release can be renamed/removed outside this project's
 * control. [MODEL_FILE] and [TOKENS_FILE] are uploaded as-is; the
 * `espeak-ng-data/` directory (355 files, needed by Piper/VITS for
 * phonemization) is uploaded as a single [ESPEAK_DATA_ARCHIVE] tarball
 * and extracted on-device after download, since a GitHub Release asset
 * must be a single file.
 */
object SwahiliNeuralTtsModel {
    const val MODEL_FILE = "sw_CD-lanfrica-medium.onnx"
    const val TOKENS_FILE = "tokens.txt"

    /** Downloaded as one tar.gz asset, extracted on-device into [espeakDataDirFor]. */
    const val ESPEAK_DATA_ARCHIVE = "espeak-ng-data.tar.gz"

    /**
     * App-private directory the model files live in once downloaded:
     * `context.filesDir/models/swahili-neural-tts/`.
     */
    fun dirFor(context: Context): File =
        File(File(context.filesDir, "models"), "swahili-neural-tts")

    /** Directory the extracted espeak-ng-data/ files live in — passed to sherpa-onnx as `dataDir`. */
    fun espeakDataDirFor(context: Context): File =
        File(dirFor(context), "espeak-ng-data")

    fun isDownloaded(context: Context): Boolean {
        val dir = dirFor(context)
        return File(dir, MODEL_FILE).exists() &&
            File(dir, TOKENS_FILE).exists() &&
            espeakDataDirFor(context).let { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }
    }
}
