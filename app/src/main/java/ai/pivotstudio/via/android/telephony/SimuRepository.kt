package ai.pivotstudio.via.android.telephony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import ai.pivotstudio.via.android.core.TextSimilarity

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
            .map { contact -> contact to TextSimilarity.levenshteinSimilarity(normalizedQuery, contact.displayName.lowercase()) }
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

    /**
     * Resolves [number] to a saved contact's display name, if any — used
     * by the "Soma ujumbe" message reader to say a name instead of a raw
     * phone number in the spoken sender preview (PLAN.md Phase 4,
     * "Soma ujumbe" Option B). Uses [ContactsContract.PhoneLookup], the
     * platform's own normalized/fuzzy phone-number-to-contact matching
     * (handles formatting differences like +255 vs 0 prefixes), rather
     * than a manual string-equality scan over [allContactsWithNumbers].
     * Returns null if [number] has no saved contact (preview falls back
     * to speaking the raw number).
     */
    fun contactNameForNumber(number: String): String? {
        val resolver = context.contentResolver
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val cursor = resolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        )
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
            } else {
                null
            }
        }
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
