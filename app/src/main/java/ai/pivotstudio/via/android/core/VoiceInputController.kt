package ai.pivotstudio.via.android.core

import ai.pivotstudio.via.android.asr.TranscriptionEngine
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared voice-driven input/confirm loop every action in this app uses
 * (dialing, contact entry, SMS composition — see PLAN.md Phase 2
 * "voice-confirm UX flow"). Every input is voice, with mandatory TTS
 * readback confirmation before any call/send action executes — there is
 * no manual text entry anywhere in this app, by design.
 *
 * Flow (PLAN.md item 6): capture -> transcribe -> TTS "Ulisema: <text>.
 * Sawa?" (You said: <text>. Correct?) -> listen for a short yes/no voice
 * response -> return confirmed text, or null to let the caller retry.
 *
 * Yes/no parsing (item 7): no second model — just another ASR pass over a
 * short utterance, matched (case-insensitive substring match) against
 * [AFFIRMATIVE_WORDS]/[NEGATIVE_WORDS]. Silence/timeout (item 8) is
 * reported as [ConfirmResult.NoResponse], distinct from an explicit
 * "hapana" ([ConfirmResult.Rejected]), so callers can give a different
 * prompt ("I didn't hear anything, try again" vs "OK, let's redo that").
 */
class VoiceInputController(
    private val audioCapture: AudioCapture,
    private val segmenter: SpeechSegmenter,
    private val asrEngine: TranscriptionEngine,
    /**
     * Speaks [text] and suspends until playback finishes. Originally a
     * concrete [ai.pivotstudio.via.android.tts.SwahiliTts] parameter;
     * widened to a plain lambda (PLAN.md Phase 4, "Andika ujumbe" voice
     * composer) so callers can pass
     * [ai.pivotstudio.via.android.tts.SpeechOutput.speakAndAwait]
     * instead — the app's actual neural-voice-with-fallback entry
     * point used everywhere else in `MainActivity`, not the raw system
     * engine alone.
     */
    private val speak: suspend (String) -> Unit,
) {
    sealed class ConfirmResult {
        data class Confirmed(val text: String) : ConfirmResult()
        object Rejected : ConfirmResult()
        object NoResponse : ConfirmResult()
    }

    /**
     * Captures one voice utterance (caller drives start/stop timing, e.g.
     * a held button), transcribes it, reads it back via TTS, then listens
     * for a short "ndiyo"/"hapana" response captured the same way and
     * returns the outcome. The caller is responsible for re-prompting on
     * [ConfirmResult.Rejected] / [ConfirmResult.NoResponse] if a retry loop
     * is wanted — this function runs the loop body once per call, not an
     * internal retry loop, so the UI can show state between attempts.
     */
    suspend fun captureConfirmedUtterance(
        promptTts: String,
        captureWindowMs: Long = DEFAULT_CAPTURE_WINDOW_MS,
        confirmWindowMs: Long = DEFAULT_CONFIRM_WINDOW_MS,
    ): ConfirmResult {
        speak(promptTts)
        val utterance = captureUtterance(captureWindowMs)
        if (utterance.isBlank()) {
            speak("Sikusikia chochote.") // "I didn't hear anything."
            return ConfirmResult.NoResponse
        }

        speak("Ulisema: $utterance. Sawa?") // "You said: <text>. Correct?"
        val response = captureUtterance(confirmWindowMs)
        return when {
            response.isBlank() -> ConfirmResult.NoResponse
            matchesAny(response, AFFIRMATIVE_WORDS) -> ConfirmResult.Confirmed(utterance)
            matchesAny(response, NEGATIVE_WORDS) -> ConfirmResult.Rejected
            else -> ConfirmResult.NoResponse
        }
    }

    /**
     * Records for up to [windowMs], runs it through the shared VAD
     * segmenter, and transcribes the speech-only portion. Returns an empty
     * string if nothing was captured/recognized.
     */
    private suspend fun captureUtterance(windowMs: Long): String = withContext(Dispatchers.Default) {
        if (!audioCapture.hasMicPermission()) {
            Log.w(TAG, "captureUtterance called without RECORD_AUDIO permission")
            return@withContext ""
        }
        segmenter.reset()
        val collected = ArrayList<Short>()
        val finished = withTimeoutOrNull(windowMs) {
            val channel = audioCapture.start(this as CoroutineScope)
            channel.consumeEach { chunk -> collected.addAll(chunk.toList()) }
            true
        }
        audioCapture.stop()
        if (finished == null) {
            Log.i(TAG, "captureUtterance window elapsed (${windowMs}ms) — stopping capture")
        }

        val raw = collected.toShortArray()
        if (raw.isEmpty()) return@withContext ""

        segmenter.accept(raw)
        val speechOnly = segmenter.extractSpeech()
        if (speechOnly.isEmpty()) return@withContext ""

        try {
            asrEngine.transcribe(speechOnly)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed during voice-confirm capture", e)
            ""
        }
    }

    private fun matchesAny(response: String, words: Set<String>): Boolean {
        val normalized = response.trim().lowercase()
        return words.any { normalized.contains(it) }
    }

    companion object {
        private const val TAG = "VIA/VoiceInput"
        private const val DEFAULT_CAPTURE_WINDOW_MS = 8_000L
        private const val DEFAULT_CONFIRM_WINDOW_MS = 4_000L

        /** Swahili affirmative words/phrases this simple matcher accepts. */
        val AFFIRMATIVE_WORDS = setOf("ndiyo", "ndio", "sawa", "sahihi", "yes")

        /** Swahili negative words/phrases this simple matcher accepts. */
        val NEGATIVE_WORDS = setOf("hapana", "siyo", "sio", "si sahihi", "no")
    }
}
