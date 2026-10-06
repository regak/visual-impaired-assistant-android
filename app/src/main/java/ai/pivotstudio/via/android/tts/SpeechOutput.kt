package ai.pivotstudio.via.android.tts

import android.content.Context

/**
 * Picks the best available Swahili voice and exposes ONE
 * `speakAndAwait(text)` entry point, so [ai.pivotstudio.via.android.ui.MainActivity]
 * doesn't need to know which engine is actually speaking.
 *
 * Preference order:
 * 1. [SwahiliNeuralTts] — the self-hosted `vits-piper-sw_CD-lanfrica-medium`
 *    voice, once [SwahiliNeuralTtsModel.isDownloaded] and [SwahiliNeuralTts.init]
 *    both succeed. User explicitly approved this voice after listening to
 *    a real generated sample ("It sounds natural").
 * 2. [SwahiliTts] — Android's built-in system `TextToSpeech` — used
 *    automatically whenever the neural model is still downloading (first
 *    launch only) OR fails to load/generate for any reason, so the user
 *    is NEVER left with total silence just because the better voice
 *    isn't ready yet.
 */
class SpeechOutput(context: Context) {
    private val appContext = context.applicationContext
    private val neural = SwahiliNeuralTts(appContext)
    private val system = SwahiliTts(appContext)
    private var neuralReady = false

    /** Human-readable engine/status summary for the on-screen diagnostic row. */
    var diagnostic: String = "TTS: inazindua..." // "initializing..."
        private set

    suspend fun init() {
        // System TTS inits fast and is the guaranteed fallback — bring it
        // up first so SOME voice is ready even if the neural model isn't
        // downloaded yet or fails to load.
        system.init()
        neuralReady = if (SwahiliNeuralTtsModel.isDownloaded(appContext)) {
            neural.init()
        } else {
            false
        }
        refreshDiagnostic()
    }

    suspend fun speakAndAwait(text: String) {
        val spokenByNeural = neuralReady && neural.speakAndAwait(text)
        if (!spokenByNeural) {
            neuralReady = false // one failure permanently drops back to system TTS for this session
            system.speakAndAwait(text)
        }
        refreshDiagnostic()
    }

    private fun refreshDiagnostic() {
        diagnostic = if (neuralReady) {
            "TTS: neural (vits-piper sw_CD) — in tumizi" // "in use"
        } else {
            "TTS: system engine=${system.engineName ?: "NONE"} locale=${system.localeAvailability}"
        }
    }

    fun shutdown() {
        neural.shutdown()
        system.shutdown()
    }
}
