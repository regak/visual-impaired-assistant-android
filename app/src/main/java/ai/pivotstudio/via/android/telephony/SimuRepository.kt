package ai.pivotstudio.via.android.telephony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import kotlin.math.max
import kotlin.math.min

/**
 * ContentResolver CRUD against `ContactsContract` + outgoing-call placement
 * for Simu (PLAN.md Phase 3, items 10-11). The 2015 thesis's
 * BroadcastReceiver-based call interception (incoming/outgoing routed
 * through the app's own UI instead of the system dialer) is Phase 3 item 12
 * and still deferred — see PLAN.md for the remaining checklist.
 *
 * Fuzzy name matching: an exact match on a spoken name transcribed by ASR
 * will rarely be perfect (accents, mis-hearings, partial names), so
 * [searchContactsByVoicedName] ranks every contact's display name by
 * normalized Levenshtein distance against the spoken name rather than
 * requiring an exact match, and returns the closest matches first.
 */
class SimuRepository(private val context: Context) {
    data class Contact(val id: Long, val displayName: String, val number: String)

    /**
     * Returns contacts ranked by closeness to [spokenName] (best match
     * first), via normalized Levenshtein distance over lowercased display
     * names. Caller (voice-confirm flow) should present the top 1-2
     * candidates via TTS ("Did you mean X or Y?") rather than silently
     * picking the best match, since ASR mishearings are common.
     */
    fun searchContactsByVoicedName(spokenName: String, maxResults: Int = 3): List<Contact> {
        val all = allContactsWithNumbers()
        val normalizedQuery = spokenName.trim().lowercase()
        if (normalizedQuery.isBlank()) return emptyList()

        return all
            .map { contact -> contact to levenshteinSimilarity(normalizedQuery, contact.displayName.lowercase()) }
            .sortedByDescending { it.second }
            .take(maxResults)
            .filter { it.second > MIN_SIMILARITY_THRESHOLD }
            .map { it.first }
    }

    private fun allContactsWithNumbers(): List<Contact> {
        val results = ArrayList<Contact>()
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        )
        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                results.add(
                    Contact(
                        id = it.getLong(idIdx),
                        displayName = it.getString(nameIdx) ?: continue,
                        number = it.getString(numberIdx) ?: continue,
                    ),
                )
            }
        }
        return results
    }

    /** 0f (completely different) to 1f (identical), normalized by the longer string's length. */
    private fun levenshteinSimilarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = levenshteinDistance(a, b)
        val maxLen = max(a.length, b.length)
        return 1f - (distance.toFloat() / maxLen.toFloat())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[a.length][b.length]
    }

    /**
     * Places an outgoing call via [Intent.ACTION_CALL] — requires the
     * `CALL_PHONE` permission to be granted already (caller must check/
     * request this before invoking; see MainActivity's runtime permission
     * flow). Must only be called AFTER the voice-confirm loop
     * ([ai.pivotstudio.via.android.core.VoiceInputController]) has
     * confirmed the number/contact via TTS readback — never dial
     * unconfirmed ASR output directly.
     */
    fun placeCall(number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        /** Below this normalized-similarity score, a candidate is not considered a plausible match. */
        private const val MIN_SIMILARITY_THRESHOLD = 0.4f
    }
}
