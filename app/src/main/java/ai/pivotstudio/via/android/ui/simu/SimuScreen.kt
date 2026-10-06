package ai.pivotstudio.via.android.ui.simu

import ai.pivotstudio.via.android.core.VoiceInputController
import ai.pivotstudio.via.android.telephony.SimuRepository
import ai.pivotstudio.via.android.tts.SwahiliTts
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Simu (phone) screen — voice-only dialing/contacts, no manual text entry
 * (PLAN.md Phase 3, item 14). Flow: press "Piga kwa sauti" (Call by
 * voice) -> [VoiceInputController] captures + confirms a spoken contact
 * name or number via TTS readback -> [SimuRepository.searchContactsByVoicedName]
 * resolves the confirmed text to the closest contact match(es) -> a second
 * TTS confirm ("Did you mean X?") -> [SimuRepository.placeCall].
 *
 * Multiple fuzzy matches (item 14's "Did you mean X or Y?" disambiguation):
 * handled by reading out the top candidate and confirming it individually
 * rather than a multi-way choice, since this app has no on-screen list
 * selection UI a non-sighted user could operate anyway — everything here
 * is sequential voice confirm, by design (see PLAN.md).
 *
 * If no contact matches, the confirmed utterance is dialed directly as a
 * phone number (a spoken number transcribes as digits via the ASR model,
 * not a contact name).
 */
@Composable
fun SimuScreen(
    voiceInputController: VoiceInputController,
    simuRepository: SimuRepository,
    tts: SwahiliTts,
    modifier: Modifier = Modifier,
) {
    var status by remember { mutableStateOf("Bonyeza kupiga kwa sauti.") } // "Press to call by voice."
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Simu — piga kwa sauti")
        Spacer(modifier = Modifier.height(16.dp))
        Text(status)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    status = "Inasikiliza swali..." // "Listening to prompt..." (TTS prompt plays first, then mic opens)
                    runCallByVoiceFlow(voiceInputController, simuRepository, tts) { newStatus ->
                        status = newStatus
                    }
                }
            },
        ) {
            Text("Piga kwa sauti") // "Call by voice"
        }
    }
}

private suspend fun runCallByVoiceFlow(
    voiceInputController: VoiceInputController,
    simuRepository: SimuRepository,
    tts: SwahiliTts,
    onStatus: (String) -> Unit,
) {
    val result = voiceInputController.captureConfirmedUtterance(
        "Sema jina la mtu au namba ya simu.", // "Say a person's name or phone number."
    )
    when (result) {
        is VoiceInputController.ConfirmResult.Confirmed -> {
            val spoken = result.text
            val matches = simuRepository.searchContactsByVoicedName(spoken)
            if (matches.isEmpty()) {
                // No contact match close enough — treat as a directly-dialed number.
                tts.speakAndAwait("Ninapiga namba $spoken.") // "I'm calling the number <spoken>."
                try {
                    simuRepository.placeCall(spoken)
                    onStatus("Inapiga: $spoken")
                } catch (e: Exception) {
                    Log.e("VIA/Simu", "placeCall failed", e)
                    onStatus("Imeshindikana kupiga: ${e.message}")
                }
                return
            }

            val best = matches.first()
            tts.speakAndAwait("Je, unamaanisha ${best.displayName}? Sawa?") // "Do you mean <name>? Correct?"
            val confirmAgain = voiceInputController.captureConfirmedUtterance(
                "Sema ndiyo au hapana.", // "Say yes or no."
            )
            when (confirmAgain) {
                is VoiceInputController.ConfirmResult.Confirmed -> {
                    try {
                        simuRepository.placeCall(best.number)
                        onStatus("Inapiga: ${best.displayName}")
                    } catch (e: Exception) {
                        Log.e("VIA/Simu", "placeCall failed", e)
                        onStatus("Imeshindikana kupiga: ${e.message}")
                    }
                }
                VoiceInputController.ConfirmResult.Rejected -> onStatus("Imeghairiwa.") // "Cancelled."
                VoiceInputController.ConfirmResult.NoResponse -> onStatus("Hakuna jibu. Jaribu tena.")
            }
        }
        VoiceInputController.ConfirmResult.Rejected -> onStatus("Imeghairiwa.")
        VoiceInputController.ConfirmResult.NoResponse -> onStatus("Hakuna jibu. Jaribu tena.")
    }
}
