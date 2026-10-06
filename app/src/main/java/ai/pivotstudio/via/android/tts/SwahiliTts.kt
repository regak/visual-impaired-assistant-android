package ai.pivotstudio.via.android.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wraps Android's built-in [TextToSpeech] for the Swahili voice-feedback
 * loop every action in this app depends on: every recognized dial number,
 * contact name, or SMS draft is read back via [speakAndAwait] and the user
 * must voice-confirm before [ai.pivotstudio.via.android.core.VoiceInputController]
 * actually places the call or sends the message (see PLAN.md
 * "voice-confirm UX flow").
 *
 * Tries `sw-TZ` first, then `sw-KE`, matching the thesis's Tanzanian
 * Swahili broadcast-news training corpus. Many Android builds ship with no
 * Swahili TTS voice installed at all — [localeAvailability] reports which
 * tier was actually granted by the engine so the caller (and PLAN.md) can
 * surface that limitation instead of silently speaking in the device's
 * default/English voice.
 */
class SwahiliTts(context: Context) {

    enum class LocaleAvailability { SW_TZ, SW_KE, UNAVAILABLE_FALLBACK_DEFAULT, NOT_INITIALIZED }

    private var tts: TextToSpeech? = null
    var localeAvailability: LocaleAvailability = LocaleAvailability.NOT_INITIALIZED
        private set

    /**
     * Resolves once the TTS engine's `onInit` callback has actually fired
     * (NOT when [TextToSpeech]'s constructor returns, which happens
     * synchronously before binding to the system TTS service completes).
     * Root cause of a real "TTS silently never speaks" bug: [tts] used to
     * be assigned synchronously inside [init], so [speakAndAwait]'s null
     * check passed and it called [TextToSpeech.speak] on an engine that
     * hadn't finished binding yet — `speak()` then returns a non-SUCCESS
     * code synchronously, which (correctly, per the separate hang-fix)
     * resumes immediately without ever producing audio. This is most
     * likely to bite on a warm second launch, where the model is already
     * downloaded and the ASR engine loads fast enough that the home
     * screen's first TTS prompt fires before the TTS engine callback has
     * run. [speakAndAwait] now suspends on this until the engine is truly
     * ready before calling [TextToSpeech.speak].
     */
    private val readyDeferred = CompletableDeferred<LocaleAvailability>()

    private val appContext = context.applicationContext

    /** Must be called once (e.g. from Application.onCreate or MainActivity) before [speakAndAwait]. */
    suspend fun init(): LocaleAvailability = suspendCancellableCoroutine { cont ->
        tts = TextToSpeech(appContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                localeAvailability = LocaleAvailability.UNAVAILABLE_FALLBACK_DEFAULT
                if (cont.isActive) cont.resume(localeAvailability)
                readyDeferred.complete(localeAvailability)
                return@TextToSpeech
            }
            localeAvailability = when {
                engine.isLanguageAvailable(Locale("sw", "TZ")) >= TextToSpeech.LANG_AVAILABLE -> {
                    engine.language = Locale("sw", "TZ")
                    LocaleAvailability.SW_TZ
                }
                engine.isLanguageAvailable(Locale("sw", "KE")) >= TextToSpeech.LANG_AVAILABLE -> {
                    engine.language = Locale("sw", "KE")
                    LocaleAvailability.SW_KE
                }
                else -> {
                    // No Swahili voice installed on this device. Documented
                    // limitation (see PLAN.md) — do not silently fail;
                    // caller should surface this to the user/UI.
                    LocaleAvailability.UNAVAILABLE_FALLBACK_DEFAULT
                }
            }
            if (cont.isActive) cont.resume(localeAvailability)
            readyDeferred.complete(localeAvailability)
        }
    }

    /**
     * Speaks [text] and suspends until playback finishes (for sequential
     * confirm prompts). **Never hangs the caller**, even if the TTS engine
     * never fires its completion callback (observed root cause of a real
     * "press and nothing happens" bug: [TextToSpeech.speak] silently fails
     * to queue the utterance on some devices/engine states — e.g. no
     * Swahili voice data actually downloaded despite
     * [TextToSpeech.isLanguageAvailable] reporting it as available — and
     * [UtteranceProgressListener.onDone]/[onError] then never fire,
     * leaving the suspended coroutine parked forever since every voice
     * flow in this app starts with a TTS prompt before any mic capture).
     * Two independent guards: (1) the synchronous return value of
     * [TextToSpeech.speak] is checked and resumes immediately on a
     * non-[TextToSpeech.SUCCESS] result; (2) a [TIMEOUT_MS] watchdog
     * resumes regardless, so a caller is guaranteed to get control back.
     */
    suspend fun speakAndAwait(text: String, utteranceId: String = text.hashCode().toString()) {
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) { readyDeferred.await() }
        if (ready == null) {
            Log.e(TAG, "speakAndAwait: TTS engine never finished init() after ${READY_TIMEOUT_MS}ms — giving up on \"$text\" without speaking")
            return
        }
        val engine = tts ?: error("SwahiliTts.init() was not called")
        Log.d(TAG, "speakAndAwait: queueing \"$text\" (utteranceId=$utteranceId)")
        val completed = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "speakAndAwait: onStart($utteranceId)")
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "speakAndAwait: onDone($utteranceId)")
                        if (cont.isActive) cont.resume(Unit)
                    }
                    @Deprecated("Deprecated in API, required override")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "speakAndAwait: onError($utteranceId)")
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "speakAndAwait: onError($utteranceId, code=$errorCode)")
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
                val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (queued != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "speakAndAwait: engine.speak() returned $queued (not SUCCESS) — resuming immediately, onDone/onError will never fire for this utterance")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            true
        }
        if (completed == null) {
            Log.e(TAG, "speakAndAwait: TIMED OUT after ${TIMEOUT_MS}ms waiting for TTS completion callback — engine.speak() queued but never called onDone/onError. Proceeding anyway so the caller is not stuck forever.")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "VIA/SwahiliTts"
        private const val TIMEOUT_MS = 10_000L
        private const val READY_TIMEOUT_MS = 15_000L
    }
}
