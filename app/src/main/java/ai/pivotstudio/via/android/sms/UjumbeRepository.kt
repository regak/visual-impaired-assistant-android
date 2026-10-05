package ai.pivotstudio.via.android.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager

/**
 * ContentResolver CRUD against the SMS provider + `SmsManager.sendTextMessage`
 * for Ujumbe (PLAN.md Phase 3, item 13). The incoming-SMS BroadcastReceiver
 * that reads new messages aloud via TTS (matching the 2015 thesis's Ujumbe
 * behavior) is still deferred — see PLAN.md for the remaining checklist.
 */
class UjumbeRepository(private val context: Context) {
    data class SmsMessage(val id: Long, val address: String, val body: String, val timestampMs: Long)

    /**
     * Reads the most recent [limit] messages from the combined inbox+sent
     * conversation view (`content://sms`), newest first.
     */
    fun recentMessages(limit: Int = 20): List<SmsMessage> {
        val results = ArrayList<SmsMessage>()
        val resolver = context.contentResolver
        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit",
        )
        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                results.add(
                    SmsMessage(
                        id = it.getLong(idIdx),
                        address = it.getString(addressIdx) ?: continue,
                        body = it.getString(bodyIdx) ?: "",
                        timestampMs = it.getLong(dateIdx),
                    ),
                )
            }
        }
        return results
    }

    /**
     * Sends [body] to [number] via [SmsManager]. Must only be called AFTER
     * the voice-confirm loop has read the composed body back via TTS and
     * the user confirmed — never send unconfirmed ASR output directly.
     * Also writes the sent message into the Sent provider so
     * [recentMessages] reflects it immediately (some OEM dialers/SMS apps
     * do this automatically when this app is the default SMS app; this app
     * is explicitly NOT the default SMS app, so it is done manually here).
     */
    fun sendSms(number: String, body: String) {
        val smsManager = context.getSystemService(SmsManager::class.java)
        val parts = smsManager.divideMessage(body)
        smsManager.sendMultipartTextMessage(number, null, parts, null, null)

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, number)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
        }
        context.contentResolver.insert(Uri.parse("content://sms/sent"), values)
    }
}
