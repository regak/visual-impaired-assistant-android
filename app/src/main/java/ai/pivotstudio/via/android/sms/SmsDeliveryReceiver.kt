package ai.pivotstudio.via.android.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ai.pivotstudio.via.android.tts.SpeechOutput

/**
 * Manifest-registered (NOT dynamically registered in code) receiver for
 * "Andika ujumbe"'s SMS delivery confirmations (PLAN.md Phase 4 —
 * explicit user question: "will it work even when I move to another
 * app without closing it so it works even in the background?").
 *
 * The PREVIOUS implementation registered its delivery receiver
 * dynamically via `ContextCompat.registerReceiver` from inside
 * [UjumbeRepository.sendSms], tied to the calling Activity/process's
 * lifetime. That works fine while the app stays in memory, but if
 * Android kills the app's background process to reclaim memory before
 * the carrier's delivery report arrives (entirely possible if the user
 * switches to several other apps, or just waits a while), the receiver
 * — and the spoken "message received" confirmation this accessibility
 * app depends on — is silently lost with it, with no way to recover.
 *
 * A manifest-registered receiver (declared in AndroidManifest.xml
 * against a STABLE action string, not tied to any particular call's
 * registration) survives independently of whether the Activity/process
 * is alive: Android itself briefly wakes the app specifically to
 * deliver the broadcast, even from a fully-stopped state. This is the
 * standard, correct pattern for a broadcast that must be received
 * regardless of UI visibility — chosen explicitly over a foreground
 * service (PLAN.md Phase 5 item 17's recording-indicator use case is a
 * better fit for that pattern; a one-shot delivery confirmation doesn't
 * need to keep a whole process pinned alive).
 *
 * Uses [BroadcastReceiver.goAsync] because speaking the confirmation
 * via [SpeechOutput] is asynchronous and can take a couple of seconds
 * (neural TTS init + synthesis) — longer than a receiver's normal
 * onReceive is allowed to block the main thread for.
 */
class SmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
        val body = intent.getStringExtra(EXTRA_BODY) ?: return
        val shouldAnnounce = intent.getBooleanExtra(EXTRA_ANNOUNCE, false)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: number
        val delivered = resultCode == Activity.RESULT_OK

        if (delivered) {
            val values = ContentValues().apply {
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.TYPE} = ?",
                arrayOf(number, body, Telephony.Sms.MESSAGE_TYPE_SENT.toString()),
            )
        }

        if (!shouldAnnounce) return // only the designated "last part" intent speaks, see sendSms

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val speech = SpeechOutput(context.applicationContext)
                speech.init()
                if (delivered) {
                    speech.speakAndAwait("Ujumbe umepokelewa na $label.") // "The message has been received by <recipient>."
                }
                // Silence (no announcement) on a non-OK delivery result —
                // a carrier that doesn't report delivery, or reports a
                // failure, does NOT necessarily mean the message never
                // arrived (delivery reports are not universally
                // supported), so this deliberately does not speak a
                // false "failed to be received" claim either.
                speech.shutdown()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SMS_DELIVERED = "ai.pivotstudio.via.android.SMS_DELIVERED"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_BODY = "body"
        const val EXTRA_LABEL = "label"
        const val EXTRA_ANNOUNCE = "announce"
    }
}
