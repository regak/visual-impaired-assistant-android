package ai.pivotstudio.via.android.ui

import ai.pivotstudio.via.android.ui.simu.SimuScreen
import ai.pivotstudio.via.android.ui.ujumbe.UjumbeScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Entry point. Only two sections per project scope: Simu (phone) and
 * Ujumbe (SMS) — explicitly no Muziki/Kikokotoa/Habari (see PLAN.md).
 *
 * This scaffold uses a plain two-button chooser instead of the original
 * thesis's swipe-based ViewPager navigation; the swipe/tap gesture
 * detector described in PLAN.md Phase 4 (CountDownTimer-based single/
 * double/long tap + 4-direction swipe) will replace this once built, as
 * the primary navigation for a non-sighted user.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Section { CHOOSER, SIMU, UJUMBE }

@Composable
private fun AppRoot() {
    var section by remember { mutableStateOf(Section.CHOOSER) }
    when (section) {
        Section.CHOOSER -> Column {
            Text("Visual Impaired Assistant")
            Button(onClick = { section = Section.SIMU }) { Text("Simu") }
            Button(onClick = { section = Section.UJUMBE }) { Text("Ujumbe") }
        }
        Section.SIMU -> SimuScreen()
        Section.UJUMBE -> UjumbeScreen()
    }
}
