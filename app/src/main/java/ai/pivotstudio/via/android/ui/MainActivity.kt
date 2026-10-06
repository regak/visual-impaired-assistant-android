package ai.pivotstudio.via.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.pivotstudio.via.android.core.ModelDownloader
import ai.pivotstudio.via.android.tts.SpeechOutput
import kotlinx.coroutines.launch

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
 * Getting back out of a sub-level uses **swipe down -> directly return
 * to depth 0** — explicit user choice (changed from long-press, which
 * the user found overlapped awkwardly with how long-press is used
 * elsewhere: "Can you replace the long holding with swiping down to go
 * back to the main menu?"). The instructions text says "...Sugua kwenda
 * chini kurudi menu kuu." ("...Swipe down to return to the main menu")
 * at depth 1 — exact user-specified wording, replacing the earlier
 * long-press-worded "...Gusa na ushikilie kurudi mwanzo." Long-press at
 * depth 1 no longer does anything (removed entirely, not reassigned to
 * another action this pass). Long-press at depth 0 (main menu) is
 * UNCHANGED — still announces [Page.subMenuSw], unaffected by this
 * change (it was never part of the back-navigation flow being replaced).
 *
 * Gesture outcomes are announced TWICE now: once automatically the
 * moment a swipe settles on a new page/sub-page (no tap required —
 * explicit user request: "when it swaps or moves to another screen, it
 * should say where it is"), and again on single-tap as a manual repeat.
 * Both paths speak the exact same [Page.instructionsSw]/[SubPage.instructionsSw]
 * text — see the `LaunchedEffect(currentRealIndex)` / `LaunchedEffect(...,
 * currentSubRealIndex)` blocks keyed on `PagerState.settledPage` (NOT
 * `currentPage`, which updates continuously mid-drag — `settledPage`
 * only changes once a swipe's animation has fully finished, so a single
 * swipe speaks its destination exactly once, not once per frame).
 *
 * Full four-gesture vocabulary (both levels):
 * - Swipe left/right: move between sibling views at the current level,
 *   with WRAPAROUND (thesis iteration-3 finding — see [VIRTUAL_PAGE_COUNT]),
 *   and now auto-announces the destination on settle (see above).
 * - Single tap: announce/repeat instructions for the current view —
 *   deliberately NON-destructive (thesis's final design, post
 *   iteration-2 fix — selection moved OFF single tap after too many
 *   accidental activations). Now redundant with the auto-announce-on-swipe
 *   behavior for the FIRST hearing, but still useful to repeat it again.
 * - Double tap: confirms/activates the current view's primary action.
 *   At depth 0 this means "enter this menu's sub-level". At depth 1 it
 *   is still just a status-text placeholder (no real call/SMS logic
 *   wired up yet — out of scope this pass).
 * - Long press: at depth 0, announces the (still-placeholder) sub-menu
 *   description — unchanged. At depth 1, no longer does anything (see
 *   above — replaced by swipe-down).
 * - Swipe down (depth 1 only, NEW this pass): goes DIRECTLY back to
 *   depth 0 — the ONLY way back. Guarded by [SWIPE_DOWN_THRESHOLD_PX]
 *   so a small vertical wobble during an otherwise-horizontal swipe
 *   isn't misread as "go back".
 *
 * Deliberately NOT wired up in this pass: ASR/STT, model download,
 * voice-confirm loops, permissions, telephony/SMS repositories. Gesture
 * outcomes are shown as on-screen status text AND now spoken aloud via
 * [SwahiliTts] (this pass's addition — same text, same
 * [SwahiliTts.speakAndAwait] used by the earlier TTS-hang-fix work, no
 * new TTS code written). [SwahiliTts] already defaults to
 * `TextToSpeech.QUEUE_FLUSH`, so a rapid second gesture interrupts
 * whatever utterance was still playing rather than queuing up a backlog
 * of stale speech.
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
)

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
        instructionsSw = "Uko kwenye Simu. Gusa mara mbili kuingia.",
        // "You are on Phone. Double tap to enter."
        primaryActionSw = "Unaingia Simu...",
        // "Entering Phone..."
        subMenuSw = "Menyu ndogo ya Simu: anwani mpya, hariri anwani, futa anwani, rudi nyuma.",
        subPages = listOf(
            SubPage(
                titleSw = "Piga kwa sauti",
                subtitleSw = "Piga simu kwa amri ya sauti",
                instructionsSw = "Uko kwenye Piga kwa sauti. Gusa mara mbili kuchagua. Sugua kwenda chini kurudi menu kuu.",
                primaryActionSw = "Umechagua Piga kwa sauti — kupiga simu kwa sauti.",
            ),
            SubPage(
                titleSw = "Anwani",
                subtitleSw = "Vitabu vya anwani",
                instructionsSw = "Uko kwenye Anwani. Gusa mara mbili kuchagua. Sugua kwenda chini kurudi menu kuu.",
                primaryActionSw = "Umechagua Anwani — kufungua kitabu cha anwani.",
            ),
        ),
    ),
    UJUMBE(
        titleSw = "Ujumbe",
        subtitleSw = "Tuma na soma ujumbe", // "Send and read messages"
        instructionsSw = "Uko kwenye Ujumbe. Gusa mara mbili kuingia.",
        primaryActionSw = "Unaingia Ujumbe...",
        subMenuSw = "Menyu ndogo ya Ujumbe: soma ujumbe wa hivi karibuni, andika ujumbe mpya, rudi nyuma.",
        subPages = listOf(
            SubPage(
                titleSw = "Andika ujumbe",
                subtitleSw = "Andika ujumbe mpya kwa sauti",
                instructionsSw = "Uko kwenye Andika ujumbe. Gusa mara mbili kuchagua. Sugua kwenda chini kurudi menu kuu.",
                primaryActionSw = "Umechagua Andika ujumbe — kutuma ujumbe kwa sauti.",
            ),
            SubPage(
                titleSw = "Soma ujumbe",
                subtitleSw = "Soma ujumbe wa hivi karibuni",
                instructionsSw = "Uko kwenye Soma ujumbe. Gusa mara mbili kuchagua. Sugua kwenda chini kurudi menu kuu.",
                primaryActionSw = "Umechagua Soma ujumbe — kusoma ujumbe wa hivi karibuni.",
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
    val context = LocalContext.current
    val downloader = remember { ModelDownloader(context) }
    var downloadComplete by remember { mutableStateOf(downloader.isComplete()) }
    var downloadProgress by remember { mutableStateOf<ModelDownloader.Progress?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Download gate: the neural Swahili TTS model (~80MB) is fetched here
    // alongside the ASR/VAD models already handled by this same
    // ModelDownloader. While still downloading, SpeechOutput falls back
    // to the system TTS engine automatically (see SpeechOutput class doc)
    // so the app is never silent, but we still show progress so the user
    // on a slow/metered connection understands what's happening.
    LaunchedEffect(Unit) {
        if (!downloader.isComplete()) {
            coroutineScope.launch {
                downloader.ensureDownloaded { progress -> downloadProgress = progress }
                downloadComplete = true
            }
        }
    }

    if (!downloadComplete) {
        val progress = downloadProgress
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Inapakua sauti ya Kiswahili...", fontSize = 18.sp) // "Downloading Swahili voice..."
                if (progress != null) {
                    val pct = if (progress.bytesTotal > 0) (progress.bytesDone * 100 / progress.bytesTotal) else 0
                    Text(
                        text = "${progress.fileName} (${progress.fileIndex}/${progress.fileCount}) — $pct%",
                        fontSize = 13.sp,
                    )
                }
            }
        }
        return
    }

    GestureNavContent()
}

@Composable
private fun GestureNavContent() {
    val pages = Page.entries
    val topPagerState = rememberPagerState(initialPage = startVirtualPage(pages.size)) { VIRTUAL_PAGE_COUNT }
    var statusMessage by remember { mutableStateOf(pages[0].instructionsSw) }

    // depth: 0 = top-level Simu/Ujumbe pager, 1 = a sub-pager nested under
    // whichever top page was active when the user double-tapped in.
    var depth by remember { mutableIntStateOf(0) }
    var activeTopPageIndex by remember { mutableIntStateOf(0) }

    // TTS: one SpeechOutput instance for the lifetime of this composable,
    // initialized once and torn down on dispose. SpeechOutput prefers the
    // self-hosted neural Swahili voice (vits-piper-sw_CD-lanfrica-medium,
    // user-approved after hearing a real sample) and transparently falls
    // back to the Android system TextToSpeech if the neural model isn't
    // downloaded yet or fails to load/generate — see SpeechOutput class
    // doc. Every gesture outcome that updates [statusMessage] is also
    // spoken aloud via the LaunchedEffect below.
    val context = LocalContext.current
    val speech = remember { SpeechOutput(context) }
    val coroutineScope = rememberCoroutineScope()
    var ttsDiagnostic by remember { mutableStateOf("TTS: inazindua...") } // "TTS: initializing..."
    var speechReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        coroutineScope.launch {
            speech.init()
            ttsDiagnostic = speech.diagnostic
            speechReady = true
        }
        onDispose { speech.shutdown() }
    }

    // Gated on speechReady so the FIRST announcement waits for
    // SpeechOutput.init() to actually finish loading the neural engine
    // before speaking — explicit user-reported bug fix: previously this
    // LaunchedEffect raced init() and the app's very first utterance
    // always grabbed the (not-yet-superseded) system TTS engine by
    // default, even though the neural model was already downloaded and
    // would be ready moments later. Re-fires once speechReady flips
    // true, at which point neuralReady correctly reflects whether the
    // self-hosted voice loaded successfully.
    LaunchedEffect(statusMessage, speechReady) {
        if (!speechReady) return@LaunchedEffect
        speech.speakAndAwait(statusMessage)
        ttsDiagnostic = speech.diagnostic
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (depth == 0) {
            // Auto-announce on swipe-settle: the moment the pager lands on
            // a (real, de-duplicated) page — not just on single-tap — per
            // explicit user request ("when it swaps or moves to another
            // screen, it should say where it is"). Uses settledPage (not
            // currentPage, which updates continuously mid-drag) so this
            // only fires once the swipe animation has fully finished.
            val currentRealIndex = ((topPagerState.settledPage % pages.size) + pages.size) % pages.size
            LaunchedEffect(currentRealIndex) {
                statusMessage = pages[currentRealIndex].instructionsSw
            }
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
            val currentSubRealIndex = ((subPagerState.settledPage % subPages.size) + subPages.size) % subPages.size
            LaunchedEffect(depth, activeTopPageIndex, currentSubRealIndex) {
                statusMessage = subPages[currentSubRealIndex].instructionsSw
            }
            HorizontalPager(
                state = subPagerState,
                modifier = Modifier.fillMaxSize().weight(1f),
            ) { virtualIndex ->
                val subPage = subPages[((virtualIndex % subPages.size) + subPages.size) % subPages.size]
                SubPageContent(
                    subPage = subPage,
                    onStatusChange = { statusMessage = it },
                    onGoBack = {
                        depth = 0
                        statusMessage = activePage.instructionsSw
                    },
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

        // TTS diagnostic row (sighted-tester / debugging aid only, per
        // user-reported "no sound" bug — this app has no ADB/logcat
        // access to the user's real device, so this is the only way to
        // tell "no TTS engine resolvable" apart from "engine present but
        // muted/wrong stream/etc" without tools on the user's end).
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFDDDDDD),
        ) {
            Text(
                text = ttsDiagnostic,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                fontSize = 11.sp,
            )
        }
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
private fun SubPageContent(subPage: SubPage, onStatusChange: (String) -> Unit, onGoBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(subPage) {
                detectTapGestures(
                    onTap = { onStatusChange(subPage.instructionsSw) },
                    onDoubleTap = { onStatusChange(subPage.primaryActionSw) },
                )
            }
            // Swipe-down replaces long-press as the way back to the main
            // menu — explicit user request ("Can you replace the long
            // holding with swiping down to go back to the main menu?").
            // Layered as a SEPARATE pointerInput block (not merged into
            // the detectTapGestures above) because detectVerticalDragGestures
            // and detectTapGestures are different gesture-detection APIs
            // that can't share one block. Runs independently of the
            // enclosing HorizontalPager's own horizontal drag handling —
            // Compose's pointer input distinguishes a predominantly
            // VERTICAL drag from the pager's HORIZONTAL one, so this does
            // not interfere with swiping left/right between sub-items.
            // Only fires onGoBack once total downward drag distance
            // exceeds [SWIPE_DOWN_THRESHOLD_PX] — guards against a small
            // accidental vertical wobble during an otherwise-horizontal
            // swipe being misread as "go back".
            .pointerInput(subPage) {
                var accumulatedDragY = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulatedDragY = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        accumulatedDragY += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        if (accumulatedDragY > SWIPE_DOWN_THRESHOLD_PX) {
                            onGoBack()
                        }
                        accumulatedDragY = 0f
                    },
                    onDragCancel = { accumulatedDragY = 0f },
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

/**
 * Minimum total downward drag distance (device pixels) to count as a
 * deliberate "swipe down to go back" gesture rather than an accidental
 * touch-slip. Roughly 2cm on a typical ~400dpi phone screen (~160dp).
 * Easy to retune after real-device testing if it feels too
 * sensitive/stiff.
 */
private const val SWIPE_DOWN_THRESHOLD_PX = 300f

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
