package ai.pivotstudio.via.android.ui.ujumbe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Ujumbe (SMS) screen stub — voice-only message composition/reading, no
 * manual text entry. Real implementation (voice-to-text compose, TTS
 * readback confirm-before-send, inbox readout) is Phase 2/3, see PLAN.md.
 */
@Composable
fun UjumbeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Ujumbe — voice SMS (scaffold, not yet implemented)")
    }
}
