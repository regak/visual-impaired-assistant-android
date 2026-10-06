package ai.pivotstudio.via.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Entry point — gestures-only reset (explicit user direction, see PLAN.md
 * Phase 4 history): swipe between Simu (phone) and Ujumbe (SMS) pages,
 * matching the original 2015 thesis's ViewPager-style menu navigation.
 *
 * Deliberately NOT wired up in this pass: TTS, ASR/STT, model download,
 * voice-confirm loops, permissions, telephony/SMS repositories. All of
 * that code still exists elsewhere in the repo (asr/, tts/, core/,
 * telephony/, sms/) from the previous pass and is untouched, just not
 * invoked from this screen — the user wants gesture navigation proven
 * solid on its own first, then features layered back in one at a time.
 *
 * Uses Compose's official [HorizontalPager] instead of the previous
 * pass's hand-rolled pointer-input gesture detector
 * (`core/GestureDetector.kt`, no longer referenced by this screen — left
 * in place, unused, in case a future pass wants tap/long-press gestures
 * HorizontalPager doesn't cover), which had two real, reported bugs
 * (gesture recognition silently stopping after the first swipe/tap, due
 * to cancelling a coroutine mid-suspend inside Compose's low-level
 * pointer event dispatch). `HorizontalPager` is the same battle-tested
 * swipe mechanism most Android apps use for this exact pattern — far
 * lower risk than continuing to debug custom gesture code.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GestureNavRoot()
                }
            }
        }
    }
}

/** Page order: Simu first, Ujumbe second — swipe right-to-left moves 0 -> 1. */
private enum class Page(val titleSw: String, val subtitleSw: String) {
    SIMU("Simu", "Piga simu na anwani"), // "Phone" / "Call and contacts"
    UJUMBE("Ujumbe", "Tuma na soma ujumbe"), // "Messages" / "Send and read messages"
}

@Composable
private fun GestureNavRoot() {
    val pages = Page.entries
    val pagerState = rememberPagerState(initialPage = 0) { pages.size }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().weight(1f),
        ) { pageIndex ->
            PageContent(page = pages[pageIndex])
        }
        PageIndicator(
            pageCount = pages.size,
            currentPage = pagerState.currentPage,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun PageContent(page: Page) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = page.titleSw, fontSize = 32.sp)
            Text(text = page.subtitleSw, fontSize = 16.sp)
        }
    }
}

/** Sighted-tester aid only (dots showing current page) — not relied on for non-sighted navigation. */
@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(pageCount) { index ->
            val color = if (index == currentPage) Color.DarkGray else Color.LightGray
            Surface(
                modifier = Modifier.padding(horizontal = 4.dp).size(10.dp),
                color = color,
                shape = CircleShape,
            ) {}
        }
    }
}
