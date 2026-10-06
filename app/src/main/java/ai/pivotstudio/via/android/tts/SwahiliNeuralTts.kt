package ai.pivotstudio.via.android.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Self-hosted neural Swahili TTS via sherpa-onnx's `OfflineTts` (VITS/Piper
 * backend), using the `vits-piper-sw_CD-lanfrica-medium` voice — a genuine
 * human-recorded Swahili voice, chosen after the user confirmed by
 * listening to a real generated sample that it sounded natural (see
 * PLAN.md Phase 4 TTS-quality items).
 *
 * Exposes the SAME `speakAndAwait(text)` suspend-function shape as
 * [SwahiliTts] (the Android system `TextToSpeech` wrapper) so callers
 * don't need to change — [MainActivity] picks whichever engine is ready
 * via [SpeechOutput].
 *
 * Model file/constructor field names (`OfflineTtsVitsModelConfig(model,
 * lexicon, tokens, dataDir, dictDir, noiseScale, noiseScaleW,
 * lengthScale)`, `OfflineTts(assetManager, OfflineTtsConfig)`,
 * `generate(text, speakerId, speed) -> GeneratedAudio(samples, sampleRate)`)
 * were confirmed by decompiling `classes.jar` inside
 * `sherpa-onnx-1.13.8.aar` with `javap` — same verification approach
 * already used for [ai.pivotstudio.via.android.asr.OmnilingualAsrEngine].
 * `lexicon` is intentionally empty: Piper voices use espeak-ng's
 * `dataDir` for phonemization, not a separate lexicon file.
 *
 * Unlike the streaming Android system `TextToSpeech`, `generate()` is
 * fully synchronous and CPU-bound — it produces a complete in-memory
 * float PCM buffer before any audio plays, so there is no streaming
 * start-of-speech latency the way QUEUE_FLUSH gives on the system
 * engine. This class runs `generate()` on [Dispatchers.Default] and
 * plays the result back via a one-shot [AudioTrack] in STATIC mode.
 */
class SwahiliNeuralTts(context: Context) {

    private val appContext = context.applicationContext
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null

    val isReady: Boolean get() = tts != null

    /**
     * Loads the model from disk. MUST be preceded by confirming
     * [SwahiliNeuralTtsModel.isDownloaded] — a missing file crashes
     * natively inside sherpa-onnx's JNI layer, not as a catchable
     * exception (same pitfall documented on [OmnilingualAsrEngine]).
     */
    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (!SwahiliNeuralTtsModel.isDownloaded(appContext)) {
            return@withContext false
        }
        try {
            val modelDir = SwahiliNeuralTtsModel.dirFor(appContext).absolutePath
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$modelDir/${SwahiliNeuralTtsModel.MODEL_FILE}",
                        lexicon = "",
                        tokens = "$modelDir/${SwahiliNeuralTtsModel.TOKENS_FILE}",
                        dataDir = SwahiliNeuralTtsModel.espeakDataDirFor(appContext).absolutePath,
                        dictDir = "",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f,
            )
            tts = OfflineTts(assetManager = null, config = config)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Generates and plays [text] synchronously (suspends until playback
     * finishes), mirroring [SwahiliTts.speakAndAwait]'s blocking-until-done
     * contract so callers can treat both engines identically. Returns
     * false (without throwing) on any failure — callers should fall back
     * to [SwahiliTts] when this returns false, never leaving the user
     * with silent output.
     */
    suspend fun speakAndAwait(text: String): Boolean {
        if (text.isBlank()) return true
        val engine = tts ?: return false
        return withContext(Dispatchers.Default) {
            try {
                val audio = engine.generate(text, 0, 1.0f) // (text, speakerId, speed) — positional, since OfflineTts.generate is a regular method not confirmed to retain param names via reflection at this AAR's Kotlin metadata level
                withContext(Dispatchers.IO) {
                    playSamples(audio.samples, audio.sampleRate)
                }
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    private fun playSamples(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferSizeBytes = max(minBufferSize, samples.size * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        audioTrack = track
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            // MODE_STATIC + a one-shot track: block until estimated playback
            // duration elapses, since AudioTrack has no built-in "await
            // playback complete" API. +200ms safety margin for buffering.
            val durationMs = (samples.size.toLong() * 1000L / sampleRate) + 200L
            Thread.sleep(durationMs)
        } finally {
            track.stop()
            track.release()
            audioTrack = null
        }
    }

    fun shutdown() {
        audioTrack?.let {
            it.stop()
            it.release()
        }
        audioTrack = null
        tts?.release()
        tts = null
    }
}
