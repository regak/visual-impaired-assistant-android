package ai.pivotstudio.via.android.telephony

/**
 * Phase 3 stub (see PLAN.md): ContentResolver CRUD against
 * `ContactsContract` + outgoing-call placement for Simu. The 2015 thesis's
 * BroadcastReceiver-based call interception (incoming/outgoing routed
 * through the app's own UI instead of the system dialer) is also Phase 3 —
 * both are deferred out of this scaffold pass.
 */
object SimuRepository {
    data class Contact(val id: Long, val displayName: String, val number: String)

    fun searchContactsByVoicedName(spokenName: String): List<Contact> {
        error("SimuRepository.searchContactsByVoicedName not implemented — see PLAN.md Phase 3")
    }

    fun placeCall(number: String) {
        error("SimuRepository.placeCall not implemented — see PLAN.md Phase 3")
    }
}
