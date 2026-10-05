package ai.pivotstudio.via.android.asr

/**
 * Seam between the voice-input pipeline and whatever ASR backend is active.
 *
 * Mirrors the pattern used in the sibling `murmur-android` project's
 * `TranscriptionEngine` interface: every dial, contact lookup, and SMS
 * compose flow in this app goes through voice (there is no manual text
 * entry at all, per PLAN.md), so this interface is the single seam that
 * [ai.pivotstudio.via.android.core.VoiceInputController] depends on —
 * swapping the model implementation never touches UI/telephony/SMS code.
 */
interface TranscriptionEngine {
    /** Human-readable name for logs / settings UI. */
    val name: String

    /** Must be called once, off the main thread, before [transcribe]. */
    suspend fun load()

    /**
     * Transcribe a single finished utterance (post-VAD segment).
     *
     * @param pcm16kMono 16kHz mono 16-bit PCM samples.
     */
    suspend fun transcribe(pcm16kMono: ShortArray): String

    /** Release native resources (sherpa-onnx sessions, etc). */
    fun close()
}
