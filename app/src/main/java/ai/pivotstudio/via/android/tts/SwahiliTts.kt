package ai.pivotstudio.via.android.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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

    private val appContext = context.applicationContext

    /** Must be called once (e.g. from Application.onCreate or MainActivity) before [speakAndAwait]. */
    suspend fun init(): LocaleAvailability = suspendCancellableCoroutine { cont ->
        tts = TextToSpeech(appContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                localeAvailability = LocaleAvailability.UNAVAILABLE_FALLBACK_DEFAULT
                if (cont.isActive) cont.resume(localeAvailability)
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
        }
    }

    /** Speaks [text] and suspends until playback finishes (for sequential confirm prompts). */
    suspend fun speakAndAwait(text: String, utteranceId: String = text.hashCode().toString()) {
        val engine = tts ?: error("SwahiliTts.init() was not called")
        suspendCancellableCoroutine<Unit> { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in API, required override")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
