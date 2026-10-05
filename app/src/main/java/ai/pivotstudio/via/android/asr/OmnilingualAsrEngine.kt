package ai.pivotstudio.via.android.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Omnilingual ASR CTC (300M, int8) via sherpa-onnx — the ASR engine for
 * this app. Swahili (and 1600+ other languages) is covered by this single
 * model, so there is no custom Swahili acoustic/language model or JSGF
 * grammar to train/maintain, unlike the original 2015 thesis's
 * PocketSphinx pipeline (see PLAN.md "ASR engine decision").
 *
 * The Kotlin API constructor/field names here (`OfflineOmnilingualAsrCtcModelConfig`,
 * `OfflineModelConfig.omnilingual`) were confirmed by decompiling
 * `classes.jar` inside `sherpa-onnx-1.13.8.aar` with `javap` — see
 * app/libs/README.md. The .aar already supports this model family; no
 * sherpa-onnx version bump was required for this scaffold.
 *
 * Model files are NOT bundled in the APK (~350MB+ for the float/int8
 * weights) — a later phase must add a `ModelDownloader` (port the pattern
 * from murmur-android's `ModelDownloader.kt`) that fetches
 * [OmnilingualAsrModel.DOWNLOAD_URL] into app-private storage on first run
 * before this class is ever constructed. See skill pitfall: never
 * construct a native sherpa-onnx recognizer before confirming the backing
 * model files exist on disk — a missing file crashes natively, not as a
 * catchable exception.
 */
class OmnilingualAsrEngine(
    private val context: Context,
) : TranscriptionEngine {

    override val name: String = "sherpa-onnx / omnilingual-asr-300m-ctc-int8"

    private var recognizer: OfflineRecognizer? = null

    override suspend fun load() = withContext(Dispatchers.IO) {
        check(OmnilingualAsrModel.isDownloaded(context)) {
            "Omnilingual ASR model not downloaded yet — see ModelDownloader " +
                "(PLAN.md Phase 1, item: wire ModelDownloader for this engine). " +
                "Never call load() before the model files exist on disk."
        }
        val modelDir = OmnilingualAsrModel.dirFor(context).absolutePath
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                    model = "$modelDir/${OmnilingualAsrModel.MODEL_FILE}",
                ),
                tokens = "$modelDir/${OmnilingualAsrModel.TOKENS_FILE}",
                numThreads = 2,
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    override suspend fun transcribe(pcm16kMono: ShortArray): String = withContext(Dispatchers.Default) {
        val engine = recognizer ?: error("OmnilingualAsrEngine.load() was not called")
        val stream = engine.createStream()
        try {
            val floatSamples = FloatArray(pcm16kMono.size) { pcm16kMono[it] / 32768.0f }
            stream.acceptWaveform(floatSamples, sampleRate = SAMPLE_RATE_HZ)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
    }
}
