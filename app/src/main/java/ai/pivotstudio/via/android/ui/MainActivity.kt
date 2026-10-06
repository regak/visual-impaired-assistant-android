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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * This pass adds a SECOND navigation level: double-tapping a top-level
 * menu (Simu or Ujumbe) drills into that menu's own swipeable sub-pager
 * (e.g. Simu -> "Piga kwa sauti" <-> "Anwani"), reusing the exact same
 * four-gesture vocabulary recursively, per the thesis's own recursive
 * view-hierarchy design (ch.3.2.4.3, figure 7: "Each rectangle... can be
 * seen as a view object" nested under the parent view).
 *
 * Getting back out of a sub-level uses **long-press -> a sub-menu dialog
 * with a "Rudi nyuma" (Back) option** — explicit user choice, matching
 * the thesis's literal "back view" object (p.43: "tapping the back view
 * (8) will go back to the previous view") rather than inventing a new
 * swipe-down gesture not present in the thesis's four-gesture scheme.
 *
 * Full four-gesture vocabulary (both levels):
 * - Swipe left/right: move between sibling views at the current level,
 *   with WRAPAROUND (thesis iteration-3 finding — see [VIRTUAL_PAGE_COUNT]).
 * - Single tap: announce/repeat instructions for the current view —
 *   deliberately NON-destructive (thesis's final design, post
 *   iteration-2 fix — selection moved OFF single tap after too many
 *   accidental activations).
 * - Double tap: confirms/activates the current view's primary action.
 *   At depth 0 this means "enter this menu's sub-level". At depth 1 it
 *   is still just a status-text placeholder (no real call/SMS logic
 *   wired up yet — out of scope this pass).
 * - Long press: opens a contextual sub-menu dialog for the current view
 *   (thesis p.43: new/edit/delete/back for a contact). At depth 1 this
 *   dialog's "Rudi nyuma" option is the ONLY way back to depth 0 this
 *   pass (explicit user choice over an invented swipe-down shortcut).
 *
 * Deliberately NOT wired up in this pass: TTS, ASR/STT, model download,
 * voice-confirm loops, permissions, telephony/SMS repositories. Gesture
 * OUTCOMES are shown as on-screen status text only, phrased as the
 * actual prompts/confirmations a later TTS pass will read aloud.
 *
 * Uses Compose's official [HorizontalPager] + built-in
 * [detectTapGestures] (natively disambiguates single/double/long-press,
 * no manual timer/cancellation logic) — see PLAN.md Phase 4 for why this
 * replaced an earlier hand-rolled pointer-input gesture detector that
 * had two real, reported bugs.
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

/** A depth-1 item nested under a top-level [Page] (e.g. Simu -> "Piga kwa sauti"). */
private data class SubPage(
    val titleSw: String,
    val subtitleSw: String,
    val instructionsSw: String,
    val primaryActionSw: String,
    /** Long-press sub-menu options for this sub-page. "Rudi nyuma" (Back) is handled specially — see [SubMenuOption]. */
    val menuOptions: List<SubMenuOption>,
)

private data class SubMenuOption(val labelSw: String, val isBack: Boolean = false)

/** Page order: Simu first, Ujumbe second — swipe right-to-left moves 0 -> 1. */
private enum class Page(
    val titleSw: String,
    val subtitleSw: String,
    val instructionsSw: String,
    val primaryActionSw: String,
    val subMenuSw: String,
    val subPages: List<SubPage>,
) {
    SIMU(
        titleSw = "Simu",
        subtitleSw = "Piga simu na anwani", // "Call and contacts"
        instructionsSw = "Uko kwenye Simu. Gusa mara mbili kuingia. Gusa na ushikilie kwa menyu ndogo.",
        // "You are on Phone. Double tap to enter. Press and hold for a sub-menu."
        primaryActionSw = "Unaingia Simu...",
        // "Entering Phone..."
        subMenuSw = "Menyu ndogo ya Simu: anwani mpya, hariri anwani, futa anwani, rudi nyuma.",
        subPages = listOf(
            SubPage(
                titleSw = "Piga kwa sauti",
                subtitleSw = "Piga simu kwa amri ya sauti",
                instructionsSw = "Uko kwenye Piga kwa sauti. Gusa mara mbili kuchagua. Gusa na ushikilie kwa menyu ndogo.",
                primaryActionSw = "Umechagua Piga kwa sauti — kupiga simu kwa sauti.",
                menuOptions = listOf(
                    SubMenuOption("Ongea amri ya sauti"), // "Speak a voice command"
                    SubMenuOption("Rudi nyuma", isBack = true), // "Go back"
                ),
            ),
            SubPage(
                titleSw = "Anwani",
                subtitleSw = "Vitabu vya anwani",
                instructionsSw = "Uko kwenye Anwani. Gusa mara mbili kuchagua. Gusa na ushikilie kwa menyu ndogo.",
                primaryActionSw = "Umechagua Anwani — kufungua kitabu cha anwani.",
                menuOptions = listOf(
                    SubMenuOption("Anwani mpya"), // "New contact"
                    SubMenuOption("Hariri anwani"), // "Edit contact"
                    SubMenuOption("Futa anwani"), // "Delete contact"
                    SubMenuOption("Rudi nyuma", isBack = true),
                ),
            ),
        ),
    ),
    UJUMBE(
        titleSw = "Ujumbe",
        subtitleSw = "Tuma na soma ujumbe", // "Send and read messages"
        instructionsSw = "Uko kwenye Ujumbe. Gusa mara mbili kuingia. Gusa na ushikilie kwa menyu ndogo.",
        primaryActionSw = "Unaingia Ujumbe...",
        subMenuSw = "Menyu ndogo ya Ujumbe: soma ujumbe wa hivi karibuni, andika ujumbe mpya, rudi nyuma.",
        subPages = listOf(
            SubPage(
                titleSw = "Andika ujumbe",
                subtitleSw = "Andika ujumbe mpya kwa sauti",
                instructionsSw = "Uko kwenye Andika ujumbe. Gusa mara mbili kuchagua. Gusa na ushikilie kwa menyu ndogo.",
                primaryActionSw = "Umechagua Andika ujumbe — kutuma ujumbe kwa sauti.",
                menuOptions = listOf(
                    SubMenuOption("Andika ujumbe mpya"), // "Write a new message"
                    SubMenuOption("Rudi nyuma", isBack = true),
                ),
            ),
            SubPage(
                titleSw = "Soma ujumbe",
                subtitleSw = "Soma ujumbe wa hivi karibuni",
                instructionsSw = "Uko kwenye Soma ujumbe. Gusa mara mbili kuchagua. Gusa na ushikilie kwa menyu ndogo.",
                primaryActionSw = "Umechagua Soma ujumbe — kusoma ujumbe wa hivi karibuni.",
                menuOptions = listOf(
                    SubMenuOption("Soma ujumbe wa hivi karibuni"), // "Read recent messages"
                    SubMenuOption("Rudi nyuma", isBack = true),
                ),
            ),
        ),
    ),
}

/**
 * Wraparound swipe (thesis iteration-3 finding — see class doc): instead
 * of a pager sized to exactly the real item count, use a very large
 * virtual page count and map each virtual index back onto the real items
 * with modulo, starting the user in the middle of that range so there's
 * effectively infinite room to swipe in either direction without ever
 * hitting a dead end. Used for BOTH the depth-0 (Page) pager and every
 * depth-1 (SubPage) pager.
 */
private const val VIRTUAL_PAGE_COUNT = 10_000

private fun startVirtualPage(itemCount: Int): Int =
    VIRTUAL_PAGE_COUNT / 2 - (VIRTUAL_PAGE_COUNT / 2) % itemCount

private fun PagerState.realIndex(itemCount: Int): Int =
    ((currentPage % itemCount) + itemCount) % itemCount

@Composable
private fun GestureNavRoot() {
    val pages = Page.entries
    val topPagerState = rememberPagerState(initialPage = startVirtualPage(pages.size)) { VIRTUAL_PAGE_COUNT }
    var statusMessage by remember { mutableStateOf(pages[0].instructionsSw) }

    // depth: 0 = top-level Simu/Ujumbe pager, 1 = a sub-pager nested under
    // whichever top page was active when the user double-tapped in.
    var depth by remember { mutableIntStateOf(0) }
    var activeTopPageIndex by remember { mutableIntStateOf(0) }
    var menuDialogFor: SubPage? by remember { mutableStateOf(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (depth == 0) {
            HorizontalPager(
                state = topPagerState,
                modifier = Modifier.fillMaxSize().weight(1f),
            ) { virtualIndex ->
                val page = pages[((virtualIndex % pages.size) + pages.size) % pages.size]
                TopPageContent(
                    page = page,
                    onStatusChange = { statusMessage = it },
                    onEnter = {
                        activeTopPageIndex = topPagerState.realIndex(pages.size)
                        depth = 1
                        statusMessage = page.primaryActionSw
                    },
                )
            }
            PageIndicator(
                pageCount = pages.size,
                currentPage = topPagerState.realIndex(pages.size),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        } else {
            val activePage = pages[activeTopPageIndex]
            val subPages = activePage.subPages
            val subPagerState = rememberPagerState(initialPage = startVirtualPage(subPages.size)) { VIRTUAL_PAGE_COUNT }
            HorizontalPager(
                state = subPagerState,
                modifier = Modifier.fillMaxSize().weight(1f),
            ) { virtualIndex ->
                val subPage = subPages[((virtualIndex % subPages.size) + subPages.size) % subPages.size]
                SubPageContent(
                    subPage = subPage,
                    onStatusChange = { statusMessage = it },
                    onOpenMenu = { menuDialogFor = subPage },
                )
            }
            PageIndicator(
                pageCount = subPages.size,
                currentPage = subPagerState.realIndex(subPages.size),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        // Gesture outcome status field: shows what the last recognized
        // gesture did, phrased as the actual prompt/confirmation text a
        // later TTS pass will speak — not placeholder debug text.
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

    // Long-press sub-menu dialog. The ONLY way back from depth 1 to
    // depth 0 this pass is its "Rudi nyuma" (Back) option — explicit
    // user choice over an invented swipe-down gesture, matching the
    // thesis's literal "back view" object.
    menuDialogFor?.let { subPage ->
        AlertDialog(
            onDismissRequest = { menuDialogFor = null },
            title = { Text(subPage.titleSw) },
            text = {
                Column {
                    subPage.menuOptions.forEach { option ->
                        TextButton(onClick = {
                            menuDialogFor = null
                            if (option.isBack) {
                                depth = 0
                                statusMessage = pages[activeTopPageIndex].instructionsSw
                            } else {
                                statusMessage = "Umechagua: ${option.labelSw}" // "You selected: ..."
                            }
                        }) {
                            Text(option.labelSw)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun TopPageContent(page: Page, onStatusChange: (String) -> Unit, onEnter: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(page) {
                detectTapGestures(
                    onTap = { onStatusChange(page.instructionsSw) },
                    onDoubleTap = { onEnter() },
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

@Composable
private fun SubPageContent(subPage: SubPage, onStatusChange: (String) -> Unit, onOpenMenu: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(subPage) {
                detectTapGestures(
                    onTap = { onStatusChange(subPage.instructionsSw) },
                    onDoubleTap = { onStatusChange(subPage.primaryActionSw) },
                    onLongPress = { onOpenMenu() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = subPage.titleSw, fontSize = 28.sp)
            Text(text = subPage.subtitleSw, fontSize = 14.sp)
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
