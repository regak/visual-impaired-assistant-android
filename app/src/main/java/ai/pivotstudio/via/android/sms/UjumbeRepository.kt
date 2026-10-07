package ai.pivotstudio.via.android.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * ContentResolver CRUD against the SMS provider + `SmsManager.sendTextMessage`
 * for Ujumbe (PLAN.md Phase 3, item 13). The incoming-SMS BroadcastReceiver
 * that reads new messages aloud via TTS (matching the 2015 thesis's Ujumbe
 * behavior) is still deferred — see PLAN.md for the remaining checklist.
 */
class UjumbeRepository(private val context: Context) {
    data class SmsMessage(val id: Long, val address: String, val body: String, val timestampMs: Long)

    /**
     * One date bucket's messages grouped together (PLAN.md Phase 4,
     * "Soma ujumbe" Option A grouping — revised from an initial Option
     * B [group-by-sender] pass per explicit user correction: "Revise
     * and use option A and not option B"). [messages] is newest-first,
     * matching [recentMessages]'s own ordering. [labelSw] is a short
     * Swahili bucket label ("Leo", "Jana", "Wiki iliyopita", "Mwezi
     * uliopita", "Zamani").
     */
    data class DateGroup(val labelSw: String, val messages: List<SmsMessage>)

    /**
     * Reads the most recent [limit] messages from the combined inbox+sent
     * conversation view (`content://sms`), newest first. Raised from an
     * initial default of 20 to 300 after a real user-reported bug: with
     * only 20, "Soma ujumbe" silently only ever showed ~2 days of
     * history on an active phone, with no indication older messages
     * existed. 300 is still a fixed ceiling, not true unlimited
     * incremental loading — see PLAN.md Phase 4 for the deferred
     * load-more-on-demand follow-up if 300 proves insufficient too.
     */
    fun recentMessages(limit: Int = 300): List<SmsMessage> {
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
     * Groups [messages] (assumed already newest-first, i.e. straight
     * from [recentMessages]) into date buckets — "Leo" (today), "Jana"
     * (yesterday), "Wiki iliyopita" (last 7 days), "Mwezi uliopita"
     * (last 30 days), "Zamani" (older) — per PLAN.md Phase 4 "Soma
     * ujumbe" Option A grouping (explicit user choice: "Revise and use
     * option A and not option B"). Each bucket's own message list
     * preserves newest-first order; only non-empty buckets are
     * returned, in chronological-bucket order (Leo first, Zamani
     * last). [nowMs] is injectable for testing; defaults to real time.
     */
    fun groupByDate(messages: List<SmsMessage>, nowMs: Long = System.currentTimeMillis()): List<DateGroup> {
        val todayCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val yesterdayCal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val sameDay = { a: Calendar, b: Calendar ->
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
        val sevenDaysMs = 7L * 24 * 3_600_000L
        val thirtyDaysMs = 30L * 24 * 3_600_000L

        val buckets = linkedMapOf(
            "Leo" to ArrayList<SmsMessage>(),
            "Jana" to ArrayList<SmsMessage>(),
            "Wiki iliyopita" to ArrayList<SmsMessage>(),
            "Mwezi uliopita" to ArrayList<SmsMessage>(),
            "Zamani" to ArrayList<SmsMessage>(),
        )
        for (message in messages) {
            val msgCal = Calendar.getInstance().apply { timeInMillis = message.timestampMs }
            val ageMs = nowMs - message.timestampMs
            val label = when {
                sameDay(msgCal, todayCal) -> "Leo"
                sameDay(msgCal, yesterdayCal) -> "Jana"
                ageMs < sevenDaysMs -> "Wiki iliyopita"
                ageMs < thirtyDaysMs -> "Mwezi uliopita"
                else -> "Zamani"
            }
            buckets[label]!!.add(message)
        }
        return buckets
            .filterValues { it.isNotEmpty() }
            .map { (label, msgs) -> DateGroup(label, msgs) }
    }

    /**
     * Sends [body] to [number] via [SmsManager]. Must only be called AFTER
     * the voice-confirm loop has read the composed body back via TTS and
     * the user confirmed — never send unconfirmed ASR output directly.
     * Also writes the sent message into the Sent provider so
     * [recentMessages] reflects it immediately (some OEM dialers/SMS apps
     * do this automatically when this app is the default SMS app; this app
     * is explicitly NOT the default SMS app, so it is done manually here).
     *
     * [onResult] (PLAN.md Phase 4, "Andika ujumbe" — explicit user
     * request: "the message should be sent directly but also receive a
     * notification that the message has arrived by voice") is called
     * once Android's OS-level send actually completes — [SmsManager]'s
     * send calls are asynchronous and fire a [PendingIntent] broadcast
     * when the radio layer reports success/failure; this is the ONLY
     * reliable way to know a message genuinely went out (as opposed to
     * assuming success right after the `sendMultipartTextMessage` call
     * returns, which only means "queued", not "sent" — a dropped/failed
     * send would otherwise get a false "sent" announcement). This does
     * NOT suppress the mandatory Android system "Allow <app> to send
     * SMS?" confirmation dialog that appears for any app that is not
     * the phone's default SMS app — that is an OS-level anti-fraud
     * protection with no app-facing API to bypass or auto-dismiss (see
     * PLAN.md Phase 4 "Andika ujumbe" history for the full Option A
     * [become default SMS app] vs Option B [voice heads-up + spoken
     * send confirmation] discussion — Option B, this function, is what
     * was chosen).
     *
     * A fresh [BroadcastReceiver] is registered per call and
     * unregisters itself once every part's result has been reported
     * (or after [SEND_RESULT_TIMEOUT_MS] elapses with no response, so a
     * caller's [onResult] is never left uncalled if the OS never
     * broadcasts back for some reason).
     */
    fun sendSms(number: String, body: String, onResult: (Boolean) -> Unit = {}) {
        val smsManager = context.getSystemService(SmsManager::class.java)
        val parts = smsManager.divideMessage(body)

        val action = "${context.packageName}.SMS_SENT_${System.nanoTime()}"
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        val resultsReceived = booleanArrayOf(false)
        var remaining = parts.size
        var allSucceeded = true

        lateinit var receiver: BroadcastReceiver
        val finish = { success: Boolean ->
            if (!resultsReceived[0]) {
                resultsReceived[0] = true
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered (e.g. by the timeout path) — fine.
                }
                onResult(success)
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK) allSucceeded = false
                remaining--
                if (remaining <= 0) finish(allSucceeded)
            }
        }
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), receiverFlags)

        for (i in parts.indices) {
            sentIntents.add(
                PendingIntent.getBroadcast(
                    context,
                    i,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, null)

        // Safety net: if the OS never broadcasts back (seen on some OEM
        // SMS stacks when the user dismisses the system confirmation
        // dialog without choosing Send/Cancel explicitly), don't leave
        // the caller's onResult permanently uncalled.
        android.os.Handler(context.mainLooper).postDelayed({
            if (!resultsReceived[0]) finish(false)
        }, SEND_RESULT_TIMEOUT_MS)

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, number)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
        }
        context.contentResolver.insert(Uri.parse("content://sms/sent"), values)
    }

    companion object {
        /**
         * Safety-net timeout (PLAN.md Phase 4, "Andika ujumbe" send
         * result): if Android's SMS radio layer never broadcasts a
         * sent-result back (observed on some OEM stacks when the
         * mandatory system confirmation dialog is dismissed without an
         * explicit Send/Cancel tap), [sendSms]'s [onResult] callback is
         * still guaranteed to fire — as a failure — rather than leaving
         * the UI stuck waiting forever for a voice confirmation that
         * will never come.
         */
        private const val SEND_RESULT_TIMEOUT_MS = 15_000L

        /**
         * Formats [timestampMs] as a short Swahili relative-time phrase
         * for the "Soma ujumbe" spoken preview (PLAN.md Phase 4, Option
         * B) — "sasa hivi" (just now) / "dakika X zilizopita" (X minutes
         * ago) / "saa X zilizopita" (X hours ago) / "jana" (yesterday) /
         * falls back to a short date for anything older. [nowMs] is
         * injectable for testing; defaults to the real current time.
         */
        fun relativeTimeSw(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
            val deltaMs = (nowMs - timestampMs).coerceAtLeast(0L)
            val minutes = deltaMs / 60_000L
            val hours = deltaMs / 3_600_000L

            if (minutes < 1) return "sasa hivi" // "just now"
            if (minutes < 60) return "dakika $minutes zilizopita" // "X minutes ago"
            if (hours < 24) return "saa $hours zilizopita" // "X hours ago"

            val msgCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
            val yesterdayCal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, -1)
            }
            val sameDay = { a: Calendar, b: Calendar ->
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            }
            if (sameDay(msgCal, yesterdayCal)) return "jana" // "yesterday"

            // Older than yesterday: short Swahili date, e.g. "tarehe 3/10".
            val day = msgCal.get(Calendar.DAY_OF_MONTH)
            val month = msgCal.get(Calendar.MONTH) + 1
            return "tarehe $day/$month"
        }
    }
}
