package ai.pivotstudio.via.android.core

import ai.pivotstudio.via.android.asr.TranscriptionEngine
import ai.pivotstudio.via.android.tts.SwahiliTts

/**
 * Stub for the shared voice-driven input/confirm loop every action in this
 * app uses (dialing, contact entry, SMS composition — see PLAN.md
 * "voice-confirm UX flow"). Not implemented in this scaffold pass; wiring
 * audio capture -> [TranscriptionEngine] -> TTS readback -> confirm/retry
 * is Phase 2 work. Kept as a real class (not a TODO comment) so Simu/Ujumbe
 * fragments have a concrete dependency to construct against.
 */
class VoiceInputController(
    private val asrEngine: TranscriptionEngine,
    private val tts: SwahiliTts,
) {
    /**
     * Captures one voice utterance, transcribes it, reads it back via TTS,
     * and returns the confirmed text — or null if the user voice-rejected
     * it ("hapana"/"no") and a retry loop should run. Not implemented yet.
     */
    suspend fun captureConfirmedUtterance(promptTts: String): String? {
        error("VoiceInputController.captureConfirmedUtterance not implemented — see PLAN.md Phase 2")
    }
}
