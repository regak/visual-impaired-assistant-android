package ai.pivotstudio.via.android.ui.simu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Simu (phone) screen stub — voice-only dialing/contacts, no manual text
 * entry. Real implementation (voice dial, voice contact lookup, TTS
 * confirm-before-call) is Phase 2/3, see PLAN.md. Kept as a real
 * `@Composable` (not a bare TODO file) so [ai.pivotstudio.via.android.ui.MainActivity]
 * has a concrete screen to navigate to in this scaffold.
 */
@Composable
fun SimuScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Simu — voice dialer/contacts (scaffold, not yet implemented)")
    }
}
