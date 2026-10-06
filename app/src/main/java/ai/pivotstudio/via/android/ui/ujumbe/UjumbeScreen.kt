package ai.pivotstudio.via.android.ui.ujumbe

import ai.pivotstudio.via.android.core.GestureEvent
import ai.pivotstudio.via.android.core.VoiceInputController
import ai.pivotstudio.via.android.core.gestureNavigation
import ai.pivotstudio.via.android.sms.UjumbeRepository
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
 * Ujumbe (SMS) screen — voice-only message composition/reading, no manual
 * text entry (PLAN.md Phase 3, item 14). Flow: press "Tuma ujumbe kwa
 * sauti" (Send SMS by voice) -> [VoiceInputController] captures + confirms
 * a spoken phone number -> captures + confirms the message body -> TTS
 * reads the full draft back once more before [UjumbeRepository.sendSms]
 * actually sends it.
 *
 * Incoming-SMS BroadcastReceiver (reads new messages aloud, PLAN.md item
 * 13) is still deferred — this screen only covers composing/sending and
 * reading back the on-device SMS log.
 *
 * Gesture nav (PLAN.md Phase 4, top-level nav only this pass): swipe down
 * returns to the home gesture screen via [onGoHome]. In-screen actions
 * (send-by-voice, read-recent) remain button-driven for now — only
 * top-level navigation between Simu/Ujumbe/home is gesture-based in this
 * pass.
 */
@Composable
fun UjumbeScreen(
    voiceInputController: VoiceInputController,
    ujumbeRepository: UjumbeRepository,
    tts: SwahiliTts,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var status by remember { mutableStateOf("Bonyeza kutuma ujumbe kwa sauti.") } // "Press to send SMS by voice."
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .gestureNavigation { gesture ->
                if (gesture == GestureEvent.SwipeDown) onGoHome()
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Ujumbe — tuma kwa sauti")
        Spacer(modifier = Modifier.height(16.dp))
        Text(status)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    status = "Inasikiliza..." // "Listening..."
                    runSendSmsByVoiceFlow(voiceInputController, ujumbeRepository, tts) { newStatus ->
                        status = newStatus
                    }
                }
            },
        ) {
            Text("Tuma ujumbe kwa sauti") // "Send SMS by voice"
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    status = readRecentMessagesSummary(ujumbeRepository)
                }
            },
        ) {
            Text("Soma ujumbe wa hivi karibuni") // "Read recent messages"
        }
    }
}

private suspend fun runSendSmsByVoiceFlow(
    voiceInputController: VoiceInputController,
    ujumbeRepository: UjumbeRepository,
    tts: SwahiliTts,
    onStatus: (String) -> Unit,
) {
    val numberResult = voiceInputController.captureConfirmedUtterance(
        "Sema namba ya simu ya mpokeaji.", // "Say the recipient's phone number."
    )
    val number = (numberResult as? VoiceInputController.ConfirmResult.Confirmed)?.text
    if (number == null) {
        onStatus(statusFor(numberResult))
        return
    }

    val bodyResult = voiceInputController.captureConfirmedUtterance(
        "Sema ujumbe wako.", // "Say your message."
    )
    val body = (bodyResult as? VoiceInputController.ConfirmResult.Confirmed)?.text
    if (body == null) {
        onStatus(statusFor(bodyResult))
        return
    }

    tts.speakAndAwait("Utatuma kwa $number: $body. Sawa?") // "You will send to <number>: <body>. Correct?"
    val finalConfirm = voiceInputController.captureConfirmedUtterance("Sema ndiyo au hapana.")
    when (finalConfirm) {
        is VoiceInputController.ConfirmResult.Confirmed -> {
            try {
                ujumbeRepository.sendSms(number, body)
                onStatus("Ujumbe umetumwa kwa $number.") // "Message sent to <number>."
            } catch (e: Exception) {
                Log.e("VIA/Ujumbe", "sendSms failed", e)
                onStatus("Imeshindikana kutuma: ${e.message}")
            }
        }
        VoiceInputController.ConfirmResult.Rejected -> onStatus("Imeghairiwa.")
        VoiceInputController.ConfirmResult.NoResponse -> onStatus("Hakuna jibu. Jaribu tena.")
    }
}

private fun statusFor(result: VoiceInputController.ConfirmResult): String = when (result) {
    is VoiceInputController.ConfirmResult.Confirmed -> result.text
    VoiceInputController.ConfirmResult.Rejected -> "Imeghairiwa."
    VoiceInputController.ConfirmResult.NoResponse -> "Hakuna jibu. Jaribu tena."
}

private fun readRecentMessagesSummary(ujumbeRepository: UjumbeRepository): String {
    val messages = ujumbeRepository.recentMessages(limit = 5)
    if (messages.isEmpty()) return "Hakuna ujumbe wowote."
    return messages.joinToString("\n") { "${it.address}: ${it.body}" }
}
