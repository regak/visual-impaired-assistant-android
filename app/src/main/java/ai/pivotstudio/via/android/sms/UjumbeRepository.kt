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
     * Normalizes a locally-dictated/typed Tanzanian number ("0719220259")
     * to the full international form ("+255719220259") the system
     * Messages app itself uses (PLAN.md Phase 4, "Andika ujumbe" —
     * explicit user-prompted investigation after comparing our app's
     * sent-message details dialog against the system app's: ours showed
     * the bare local number, the system app showed "+255...", risking
     * the same contact splitting into two separate threads depending on
     * which app sent to them). Only touches numbers in the common local
     * format (leading "0", otherwise all digits, length 9 after the
     * "0" — the standard Tanzanian mobile number shape); anything else
     * (already-international "+255...", a short code, an unusual
     * format) is passed through unchanged rather than guessed at.
     */
    fun normalizeToInternational(number: String): String {
        val trimmed = number.trim()
        if (trimmed.startsWith("+")) return trimmed
        val digitsOnly = trimmed.filter { it.isDigit() }
        return when {
            digitsOnly.length == 10 && digitsOnly.startsWith("0") -> "+255${digitsOnly.substring(1)}"
            digitsOnly.length == 12 && digitsOnly.startsWith("255") -> "+$digitsOnly"
            else -> trimmed
        }
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
     * [number] is normalized to international format via
     * [normalizeToInternational] before sending/storing (PLAN.md Phase 4
     * — explicit user-prompted fix, matching the system Messages app's
     * own convention, to avoid the same contact splitting into two
     * threads by number format).
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
     * Also now registers [deliveryIntents] (PLAN.md Phase 4 — explicit
     * user-prompted fix: our app's sent messages showed no "Received:"
     * field at all in their details dialog, unlike the system app's
     * messages, because the previous version only ever registered a
     * sentIntent, never a deliveryIntent — so Android had no delivery
     * report to track or display). Delivery broadcasts are now handled
     * by the MANIFEST-registered [SmsDeliveryReceiver] (PLAN.md Phase
     * 4 — explicit user question: "will it work even when I move to
     * another app... so it works even in the background?") rather than
     * a receiver dynamically registered here, so the delivery
     * confirmation — including its spoken "Ujumbe umepokelewa na
     * [jina]." announcement — still arrives even if Android has fully
     * killed this app's process by the time the carrier reports back.
     * [recipientLabel] (the resolved contact name, or the number
     * itself if no contact matched) is carried through as an Intent
     * extra so that receiver can speak the right name without this
     * Activity/process needing to still be alive. Whether a delivery
     * report actually arrives still partly depends on carrier support
     * — not every SIM/network combination sends one back, regardless
     * of this fix.
     *
     * A fresh sent-confirmation [BroadcastReceiver] is still registered
     * dynamically per call (that one is fine to lose if the process
     * dies — the "sent" event already happened by the time the user
     * could plausibly switch away, since it fires within the same
     * foreground interaction as the system's send-confirmation dialog)
     * and unregisters itself once every part's result has been
     * reported (or after [SEND_RESULT_TIMEOUT_MS] elapses with no
     * response, so a caller's [onResult] is never left uncalled if the
     * OS never broadcasts back for some reason).
     */
    fun sendSms(number: String, body: String, recipientLabel: String = number, onResult: (Boolean) -> Unit = {}) {
        val normalizedNumber = normalizeToInternational(number)
        val smsManager = context.getSystemService(SmsManager::class.java)
        val parts = smsManager.divideMessage(body)

        val sentAction = "${context.packageName}.SMS_SENT_${System.nanoTime()}"
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        val deliveryIntents = ArrayList<PendingIntent>(parts.size)
        val resultsReceived = booleanArrayOf(false)
        var remaining = parts.size
        var allSucceeded = true

        lateinit var sentReceiver: BroadcastReceiver
        val finish = { success: Boolean ->
            if (!resultsReceived[0]) {
                resultsReceived[0] = true
                try {
                    context.unregisterReceiver(sentReceiver)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered (e.g. by the timeout path) — fine.
                }
                onResult(success)
            }
        }

        sentReceiver = object : BroadcastReceiver() {
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
        ContextCompat.registerReceiver(context, sentReceiver, IntentFilter(sentAction), receiverFlags)

        for (i in parts.indices) {
            sentIntents.add(
                PendingIntent.getBroadcast(
                    context,
                    i,
                    Intent(sentAction).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            // Only the LAST part's delivery intent carries announce=true,
            // so a multi-part message speaks "received" exactly once
            // (per-part delivery broadcasts can arrive in any order or
            // close together; this avoids a double announcement).
            deliveryIntents.add(
                PendingIntent.getBroadcast(
                    context,
                    parts.size + i,
                    Intent(SmsDeliveryReceiver.ACTION_SMS_DELIVERED).apply {
                        setPackage(context.packageName)
                        putExtra(SmsDeliveryReceiver.EXTRA_NUMBER, normalizedNumber)
                        putExtra(SmsDeliveryReceiver.EXTRA_BODY, body)
                        putExtra(SmsDeliveryReceiver.EXTRA_LABEL, recipientLabel)
                        putExtra(SmsDeliveryReceiver.EXTRA_ANNOUNCE, i == parts.size - 1)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        smsManager.sendMultipartTextMessage(normalizedNumber, null, parts, sentIntents, deliveryIntents)

        // Safety net: if the OS never broadcasts back (seen on some OEM
        // SMS stacks when the user dismisses the system confirmation
        // dialog without choosing Send/Cancel explicitly), don't leave
        // the caller's onResult permanently uncalled.
        android.os.Handler(context.mainLooper).postDelayed({
            if (!resultsReceived[0]) finish(false)
        }, SEND_RESULT_TIMEOUT_MS)

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, normalizedNumber)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            // STATUS_PENDING (not STATUS_NONE) so the system Messages app
            // shows a delivery-tracking state until SmsDeliveryReceiver
            // updates it to STATUS_COMPLETE (or leaves it pending if the
            // carrier never reports back / this app's send path doesn't
            // support it on this network).
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
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
