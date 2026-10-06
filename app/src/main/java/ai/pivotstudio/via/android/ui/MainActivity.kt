package ai.pivotstudio.via.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Entry point — gestures-only reset (explicit user direction, see PLAN.md
 * Phase 4 history): swipe between Simu (phone) and Ujumbe (SMS) pages,
 * matching the original 2015 thesis's ViewPager-style menu navigation.
 *
 * This pass implements the FULL four-gesture vocabulary from Kivaisi's
 * 2015 thesis (chapter 3.2.4, confirmed by re-reading the actual thesis
 * PDF, see PLAN.md Phase 4 for the page-by-page citations), not just
 * swipe nav:
 * - Swipe left/right: move between views (here: Simu <-> Ujumbe), with
 *   WRAPAROUND (thesis iteration 3 finding: users kept re-swiping past
 *   the last view thinking the gesture hadn't registered, so the final
 *   design made the view list loop endlessly instead of dead-ending).
 * - Single tap: announce/repeat instructions for the current view —
 *   deliberately NON-destructive. The thesis's EARLY design used single
 *   tap to select/activate, but iteration-2 testing showed too many
 *   accidental activations, so the FINAL design moved selection to
 *   double-tap and repurposed single-tap as a safe "where am I / what do
 *   I do" announcement. This app replicates the FINAL design, not the
 *   superseded early one.
 * - Double tap: confirms/activates the current view's primary action
 *   (thesis's final design, post-iteration-2 change).
 * - Long press: opens a contextual sub-menu for the current view (e.g.
 *   thesis: long-press a contact -> new/edit/delete/back; long-press the
 *   voice-call view -> speak a voice command).
 *
 * Deliberately NOT wired up in this pass: TTS, ASR/STT, model download,
 * voice-confirm loops, permissions, telephony/SMS repositories. All of
 * that code still exists elsewhere in the repo (asr/, tts/, core/,
 * telephony/, sms/) from a previous pass and is untouched, just not
 * invoked from this screen. Gesture OUTCOMES are shown as on-screen
 * status text only (per explicit user request this pass — "put a text
 * field with the message") since there is no TTS wired up yet; this text
 * field is where TTS output will eventually be spoken from once
 * re-attached, so the status strings are already phrased as the actual
 * prompts/confirmations a later TTS pass will read aloud, not placeholder
 * debug text.
 *
 * Uses Compose's official [HorizontalPager] + built-in
 * [detectTapGestures] (which natively disambiguates single tap / double
 * tap / long press — no manual timer/cancellation logic needed) instead
 * of the previous pass's hand-rolled pointer-input gesture detector
 * (`core/GestureDetector.kt`, no longer referenced — left in place,
 * unused), which had two real, reported bugs (gesture recognition
 * silently stopping after the first swipe/tap, due to cancelling a
 * coroutine mid-suspend inside Compose's low-level pointer event
 * dispatch). Both of these are official, battle-tested Compose APIs —
 * far lower risk than continuing to debug custom gesture code.
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
private enum class Page(
    val titleSw: String,
    val subtitleSw: String,
    val instructionsSw: String,
    val primaryActionSw: String,
    val subMenuSw: String,
) {
    SIMU(
        titleSw = "Simu",
        subtitleSw = "Piga simu na anwani", // "Call and contacts"
        instructionsSw = "Uko kwenye Simu. Gusa mara mbili kuchagua. Gusa na ushikilie kwa menyu ndogo.",
        // "You are on Phone. Double tap to select. Press and hold for a sub-menu."
        primaryActionSw = "Umechagua Simu — kupiga simu kwa sauti.",
        // "You selected Phone — call by voice."
        subMenuSw = "Menyu ndogo ya Simu: anwani mpya, hariri anwani, futa anwani, rudi nyuma.",
        // "Phone sub-menu: new contact, edit contact, delete contact, go back."
    ),
    UJUMBE(
        titleSw = "Ujumbe",
        subtitleSw = "Tuma na soma ujumbe", // "Send and read messages"
        instructionsSw = "Uko kwenye Ujumbe. Gusa mara mbili kuchagua. Gusa na ushikilie kwa menyu ndogo.",
        primaryActionSw = "Umechagua Ujumbe — kutuma ujumbe kwa sauti.",
        subMenuSw = "Menyu ndogo ya Ujumbe: soma ujumbe wa hivi karibuni, andika ujumbe mpya, rudi nyuma.",
        // "Messages sub-menu: read recent messages, write a new message, go back."
    ),
}

/**
 * Wraparound swipe (thesis iteration-3 finding — see class doc): instead
 * of a pager sized to exactly [Page.entries], use a very large virtual
 * page count and map each virtual index back onto the real pages with
 * modulo, starting the user in the middle of that range so there's
 * effectively infinite room to swipe in either direction without ever
 * hitting a dead end.
 */
private const val VIRTUAL_PAGE_COUNT = 10_000
private val START_VIRTUAL_PAGE = VIRTUAL_PAGE_COUNT / 2 - (VIRTUAL_PAGE_COUNT / 2) % Page.entries.size

@Composable
private fun GestureNavRoot() {
    val pages = Page.entries
    val pagerState = rememberPagerState(initialPage = START_VIRTUAL_PAGE) { VIRTUAL_PAGE_COUNT }
    var statusMessage by remember { mutableStateOf(pages[0].instructionsSw) }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().weight(1f),
        ) { virtualIndex ->
            val page = pages[((virtualIndex % pages.size) + pages.size) % pages.size]
            PageContent(
                page = page,
                onStatusChange = { statusMessage = it },
            )
        }
        PageIndicator(
            pageCount = pages.size,
            currentPage = ((pagerState.currentPage % pages.size) + pages.size) % pages.size,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        // Gesture outcome status field (explicit user request this pass):
        // shows what the last recognized gesture did, phrased as the
        // actual prompt/confirmation text a later TTS pass will speak —
        // not placeholder debug text.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFEFEFF4),
        ) {
            Text(
                text = statusMessage,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun PageContent(page: Page, onStatusChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(page) {
                detectTapGestures(
                    onTap = { onStatusChange(page.instructionsSw) },
                    onDoubleTap = { onStatusChange(page.primaryActionSw) },
                    onLongPress = { onStatusChange(page.subMenuSw) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
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
