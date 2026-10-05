package ai.pivotstudio.via.android.core

import ai.pivotstudio.via.android.asr.TranscriptionEngine
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal press-and-hold capture -> VAD -> ASR round trip, proving
 * [ai.pivotstudio.via.android.asr.OmnilingualAsrEngine] actually works end
 * to end on a real device (PLAN.md Phase 1 milestone: "press-and-hold in
 * MainActivity -> speak Swahili -> see the transcript appear on screen").
 *
 * Ported from the sibling `murmur-android` project's
 * `core/DictationController.kt` state-machine shape (consume the capture
 * channel until [AudioCapture.stop] closes it, then VAD + transcribe).
 * This app has no dictionary-correction/rule-based-formatter passes (those
 * are murmur-android-specific dictation features, not relevant to this
 * app's voice-confirm dial/SMS flows) — kept intentionally minimal; the
 * real voice-confirm wrapper is [VoiceInputController] (PLAN.md Phase 2).
 */
class DictationController(
    private val audioCapture: AudioCapture,
    private val engine: TranscriptionEngine,
    private val segmenter: SpeechSegmenter,
    private val context: Context,
    private val onResult: (Result) -> Unit = {},
) {
    enum class State { IDLE, LISTENING, FINISHING }

    sealed class Result {
        data class Transcript(val text: String) : Result()
        object NoSpeechDetected : Result()
        data class Error(val message: String) : Result()
    }

    var state: State = State.IDLE
        private set

    /** Called when the user presses the record area (hold to talk). */
    fun startListening(scope: CoroutineScope, onAmplitude: (Float) -> Unit = {}) {
        check(state == State.IDLE) { "startListening() called while state=$state" }
        state = State.LISTENING
        segmenter.reset()

        val chunks = audioCapture.start(scope, onAmplitude)
        val collected = ArrayList<Short>()

        scope.launch {
            chunks.consumeEach { chunk -> collected.addAll(chunk.toList()) }
            // Channel closes when stop() is called (AudioRecord released) -> transcribe.
            state = State.FINISHING

            val raw = collected.toShortArray()
            val speechOnly = withContext(Dispatchers.Default) {
                if (raw.isEmpty()) raw else {
                    segmenter.accept(raw)
                    segmenter.extractSpeech()
                }
            }

            if (speechOnly.isEmpty()) {
                Log.i(TAG, "No speech detected (silence or button tap too short)")
                onResult(Result.NoSpeechDetected)
            } else {
                Log.i(TAG, "VAD kept ${speechOnly.size}/${raw.size} samples")
                try {
                    val text = engine.transcribe(speechOnly)
                    Log.i(TAG, "Transcript: \"$text\"")
                    onResult(if (text.isBlank()) Result.NoSpeechDetected else Result.Transcript(text))
                } catch (e: Exception) {
                    Log.e(TAG, "Transcription failed", e)
                    onResult(Result.Error(e.message ?: e.toString()))
                }
            }
            state = State.IDLE
        }
    }

    /** Called when the user releases the record area. */
    fun stopListening() {
        audioCapture.stop()
    }

    companion object {
        private const val TAG = "VIA/Dictation"
    }
}
