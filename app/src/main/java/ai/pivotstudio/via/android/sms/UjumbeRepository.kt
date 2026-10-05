package ai.pivotstudio.via.android.sms

/**
 * Phase 3 stub (see PLAN.md): ContentResolver CRUD against the SMS
 * provider + `SmsManager.sendTextMessage`, plus the incoming-SMS
 * BroadcastReceiver that announces new messages via TTS instead of a
 * system notification (matching the 2015 thesis's Ujumbe behavior).
 * Deferred out of this scaffold pass.
 */
object UjumbeRepository {
    data class SmsMessage(val id: Long, val address: String, val body: String, val timestampMs: Long)

    fun recentMessages(limit: Int = 20): List<SmsMessage> {
        error("UjumbeRepository.recentMessages not implemented — see PLAN.md Phase 3")
    }

    fun sendSms(number: String, body: String) {
        error("UjumbeRepository.sendSms not implemented — see PLAN.md Phase 3")
    }
}
