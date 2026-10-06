package ai.pivotstudio.via.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import ai.pivotstudio.via.android.core.AudioCapture
import ai.pivotstudio.via.android.core.DictationController
import ai.pivotstudio.via.android.core.GestureEvent
import ai.pivotstudio.via.android.core.gestureNavigation
import ai.pivotstudio.via.android.core.SpeechSegmenter
import ai.pivotstudio.via.android.asr.OmnilingualAsrEngine
import ai.pivotstudio.via.android.sms.UjumbeRepository
import ai.pivotstudio.via.android.telephony.SimuRepository
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
 * chini kurudi mwanzo." ("...Swipe down to return to start")
 * at depth 1 — exact user-specified wording (revised once from an
 * initial "...kurudi menu kuu." draft to this final "...kurudi mwanzo."
 * per explicit follow-up correction), replacing the earlier
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
    /**
     * When true, double-tapping this [SubPage] drills into the depth-2
     * message-reader pager (PLAN.md Phase 4, "Soma ujumbe" Option B)
     * instead of just speaking [primaryActionSw] as a placeholder. Only
     * "Soma ujumbe" sets this — every other SubPage is still a
     * placeholder, unchanged.
     */
    val opensMessageReader: Boolean = false,
    /**
     * When true, double-tapping this [SubPage] starts the voice-driven
     * "Andika ujumbe" compose-and-send flow (PLAN.md Phase 4, "Andika
     * ujumbe" — explicit user request: thesis-informed voice SMS
     * composition with mandatory readback confirmation, directly
     * addressing thesis §3.2.2.2's "message verification after
     * finishing writing" pain point) instead of just speaking
     * [primaryActionSw] as a placeholder. Only "Andika ujumbe" sets
     * this — every other SubPage (besides "Soma ujumbe") is still a
     * placeholder, unchanged.
     */
    val opensComposer: Boolean = false,
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
                instructionsSw = "Uko kwenye Piga kwa sauti. Gusa mara mbili kuchagua. Sugua kwenda chini kurudi mwanzo.",
                primaryActionSw = "Umechagua Piga kwa sauti — kupiga simu kwa sauti.",
            ),
            SubPage(
                titleSw = "Anwani",
                subtitleSw = "Vitabu vya anwani",
                instructionsSw = "Uko kwenye Anwani. Gusa mara mbili kuchagua. Sugua kwenda chini kurudi mwanzo.",
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
                instructionsSw = "Uko kwenye Andika ujumbe. Gusa mara mbili kuchagua. Sugua kwenda chini kurudi mwanzo.",
                primaryActionSw = "Umechagua Andika ujumbe — kutuma ujumbe kwa sauti.",
                opensComposer = true,
            ),
            SubPage(
                titleSw = "Soma ujumbe",
                subtitleSw = "Soma ujumbe wa hivi karibuni",
                instructionsSw = "Uko kwenye Soma ujumbe. Gusa mara mbili kuchagua. Sugua kwenda chini kurudi mwanzo.",
                primaryActionSw = "Umechagua Soma ujumbe — kusoma ujumbe wa hivi karibuni.",
                opensMessageReader = true,
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
    // Monotonic counter bumped every time [announce] is called, even when
    // the new text is identical to the current statusMessage. Needed
    // because the auto-speak LaunchedEffect below is keyed on this value
    // (not just on statusMessage's text) — explicit user-reported bug:
    // double-tapping the SAME message a second time to repeat it did
    // nothing, because Compose's remember-based state doesn't re-fire a
    // LaunchedEffect when the new value structurally equals the old one.
    var speechNonce by remember { mutableIntStateOf(0) }
    val announce: (String) -> Unit = { text ->
        statusMessage = text
        speechNonce++
    }

    // depth: 0 = top-level Simu/Ujumbe pager, 1 = a sub-pager nested under
    // whichever top page was active when the user double-tapped in, 2 =
    // the "Soma ujumbe" date-group list (grouped by date — PLAN.md Phase
    // 4, "Soma ujumbe" Option A grouping pass; revised from an initial
    // Option B [group-by-sender] attempt per explicit user correction:
    // "Revise and use option A and not option B"), 3 = that date
    // group's own messages (the original flat message reader, now
    // nested one level deeper under its date bucket instead of being
    // the top of the whole message list).
    var depth by remember { mutableIntStateOf(0) }
    var activeTopPageIndex by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val ujumbeRepository = remember { UjumbeRepository(context) }
    val simuRepository = remember { SimuRepository(context) }
    var dateGroups by remember { mutableStateOf<List<UjumbeRepository.DateGroup>>(emptyList()) }
    var activeDateGroupIndex by remember { mutableIntStateOf(0) }

    // "Andika ujumbe" (PLAN.md Phase 4, voice SMS composer) — rebuilt this
    // pass on a press-and-hold-to-speak / release-to-transcribe mechanic
    // (explicit user request: "can we use a press and hold button to
    // speak and when release to trascribe like the one we used earlier
    // in a demo ASR or murmur app"), replacing an earlier fixed-timer
    // voice-only prototype entirely. depth 4 = recipient capture screen,
    // depth 5 = message-body capture screen. Both screens reuse the SAME
    // gesture vocabulary as the rest of this app (single-tap repeats the
    // current draft/prompt, double-tap confirms+advances, swipe-down
    // cancels back one screen) PLUS a dedicated press-hold record button
    // for capture — two separate pointer-input regions on the same
    // screen, not one overloaded gesture area, so "hold to record" never
    // has to be disambiguated from "tap to confirm" at the gesture-
    // recognizer level.
    var composerRecipientDraft by remember { mutableStateOf<String?>(null) }
    var composerRecipientLabel by remember { mutableStateOf<String?>(null) }
    var composerRecipientNumber by remember { mutableStateOf<String?>(null) }
    var composerBodyDraft by remember { mutableStateOf<String?>(null) }
    var composerBodyPendingSend by remember { mutableStateOf(false) }
    var isComposerRecording by remember { mutableStateOf(false) }

    // READ_SMS is declared in the manifest but, per Android 6+ runtime
    // permission rules, was never actually requested anywhere until this
    // pass — "Soma ujumbe" is the first feature that needs it. Requesting
    // READ_CONTACTS alongside it too (also declared, also never
    // requested) so the sender-name lookup can resolve to a saved
    // contact's name instead of a raw number; if contacts permission is
    // denied specifically, [SimuRepository.contactNameForNumber] still
    // works gracefully by falling back to null (caller falls back to
    // speaking the raw number) — only READ_SMS is actually required to
    // enter the reader at all.
    var pendingMessageReaderEntry by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val smsGranted = results[Manifest.permission.READ_SMS] == true
        if (pendingMessageReaderEntry) {
            pendingMessageReaderEntry = false
            if (smsGranted) {
                dateGroups = ujumbeRepository.groupByDate(ujumbeRepository.recentMessages())
                depth = 2
                statusMessage = if (dateGroups.isEmpty()) {
                    "Hauna ujumbe wa kusoma." // "You have no messages to read."
                } else {
                    dateGroupPreviewSw(dateGroups[0])
                }
            } else {
                statusMessage = "Haiwezi kusoma ujumbe bila ruhusa ya SMS." // "Cannot read messages without SMS permission."
            }
        }
    }

    // TTS: one SpeechOutput instance for the lifetime of this composable,
    // initialized once and torn down on dispose. SpeechOutput prefers the
    // self-hosted neural Swahili voice (vits-piper-sw_CD-lanfrica-medium,
    // user-approved after hearing a real sample) and transparently falls
    // back to the Android system TextToSpeech if the neural model isn't
    // downloaded yet or fails to load/generate — see SpeechOutput class
    // doc. Every gesture outcome that updates [statusMessage] is also
    // spoken aloud via the LaunchedEffect below.
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

    // "Andika ujumbe" (PLAN.md Phase 4, voice SMS composer — explicit user
    // request, informed by thesis §3.2.2.2's "message verification after
    // finishing writing" pain point: real 2015 interview participants had
    // no way to confirm what they'd actually written before it was sent;
    // addressed here via the double-tap-to-confirm readback below rather
    // than a spoken "ndiyo"/"hapana" — more reliable than parsing a
    // yes/no utterance, and consistent with how every other screen in
    // this app already confirms things). Reuses the SAME ASR/VAD
    // pipeline already proven for dictation (PLAN.md Phase 1) via
    // [DictationController] — the exact press-and-hold-to-speak/
    // release-to-transcribe state machine ported from the sibling
    // `murmur-android` project, not the fixed-timer [VoiceInputController]
    // this pass replaces for this flow (VoiceInputController itself is
    // untouched/still used elsewhere — see its own doc comment).
    val audioCapture = remember { AudioCapture(context) }
    val segmenter = remember { SpeechSegmenter(context) }
    val asrEngine = remember { OmnilingualAsrEngine(context) }
    LaunchedEffect(Unit) {
        asrEngine.load()
    }
    // composerActive suppresses the general auto-speak LaunchedEffect
    // below (keyed on statusMessage) while depth 4/5 are active, since
    // those screens manage their own TTS prompts/readback directly
    // instead of going through the page-level auto-announce mechanism
    // (which is designed for gesture-nav pager pages, not a multi-step
    // record/confirm flow).
    var composerActive by remember { mutableStateOf(false) }
    val dictationController = remember {
        DictationController(audioCapture, asrEngine, segmenter, context) { result ->
            isComposerRecording = false
            when (result) {
                is DictationController.Result.Transcript -> {
                    if (depth == 4) {
                        composerRecipientDraft = result.text
                        announce("Ulisema: ${result.text}. Gusa mara mbili kuthibitisha, au shikilia tena kurekodi upya.")
                    } else if (depth == 5) {
                        composerBodyDraft = result.text
                        announce("Ulisema: ${result.text}. Gusa mara mbili kuthibitisha, au shikilia tena kurekodi upya.")
                    }
                }
                is DictationController.Result.NoSpeechDetected -> announce("Sikusikia chochote. Shikilia kitufe tena.") // "I didn't hear anything. Hold the button again."
                is DictationController.Result.Error -> announce("Hitilafu ya kusikiliza: ${result.message}") // "Listening error: <msg>"
            }
        }
    }
    val startComposerRecording: () -> Unit = {
        if (dictationController.state == DictationController.State.IDLE && audioCapture.hasMicPermission()) {
            isComposerRecording = true
            dictationController.startListening(coroutineScope)
        }
    }
    val stopComposerRecording: () -> Unit = {
        dictationController.stopListening()
    }

    // RECORD_AUDIO + SEND_SMS are both declared in the manifest but, per
    // Android 6+ runtime permission rules, were never requested anywhere
    // until this feature — "Andika ujumbe" is the first flow that needs
    // either of them.
    var pendingComposerEntry by remember { mutableStateOf(false) }
    val composerPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        val smsSendGranted = results[Manifest.permission.SEND_SMS] == true
        if (pendingComposerEntry) {
            pendingComposerEntry = false
            if (micGranted && smsSendGranted) {
                composerActive = true
                composerRecipientDraft = null
                composerRecipientLabel = null
                composerRecipientNumber = null
                composerBodyDraft = null
                depth = 4
                announce("Shikilia kitufe na useme jina au namba ya mpokeaji, kisha achia.") // "Hold the button and say the recipient's name or number, then release."
            } else {
                announce("Haiwezi kuandika ujumbe bila ruhusa ya sauti na SMS.") // "Cannot write a message without mic and SMS permission."
            }
        }
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
    LaunchedEffect(statusMessage, speechNonce, speechReady) {
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
                    onStatusChange = announce,
                    onEnter = {
                        activeTopPageIndex = topPagerState.realIndex(pages.size)
                        depth = 1
                        announce(page.primaryActionSw)
                    },
                )
            }
            PageIndicator(
                pageCount = pages.size,
                currentPage = topPagerState.realIndex(pages.size),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        } else if (depth == 1) {
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
                    onStatusChange = announce,
                    onDoubleTap = {
                        if (subPage.opensMessageReader) {
                            // "Soma ujumbe" (PLAN.md Phase 4, Option B):
                            // READ_SMS was never requested at runtime
                            // anywhere in this app until this feature —
                            // request it (+ READ_CONTACTS for sender-name
                            // resolution) now, entering the reader from
                            // the launcher callback once the user
                            // responds to the system dialog.
                            val smsAlreadyGranted = context.checkSelfPermission(Manifest.permission.READ_SMS) ==
                                PackageManager.PERMISSION_GRANTED
                            if (smsAlreadyGranted) {
                                dateGroups = ujumbeRepository.groupByDate(ujumbeRepository.recentMessages())
                                depth = 2
                                announce(
                                    if (dateGroups.isEmpty()) {
                                        "Hauna ujumbe wa kusoma."
                                    } else {
                                        dateGroupPreviewSw(dateGroups[0])
                                    },
                                )
                            } else {
                                pendingMessageReaderEntry = true
                                permissionLauncher.launch(
                                    arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS),
                                )
                            }
                        } else if (subPage.opensComposer) {
                            // "Andika ujumbe" (PLAN.md Phase 4): RECORD_AUDIO
                            // + SEND_SMS were never requested at runtime
                            // anywhere in this app until this feature —
                            // request both (+ READ_CONTACTS already granted
                            // by "Soma ujumbe" if the user went there first,
                            // but requested again here defensively since
                            // this flow can be entered first) now, starting
                            // the composer flow from the launcher callback
                            // once the user responds to the system dialog.
                            val micAlreadyGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            val smsSendAlreadyGranted = context.checkSelfPermission(Manifest.permission.SEND_SMS) ==
                                PackageManager.PERMISSION_GRANTED
                            if (micAlreadyGranted && smsSendAlreadyGranted) {
                                composerActive = true
                                composerRecipientDraft = null
                                composerRecipientLabel = null
                                composerRecipientNumber = null
                                composerBodyDraft = null
                                depth = 4
                                announce("Shikilia kitufe na useme jina au namba ya mpokeaji, kisha achia.")
                            } else {
                                pendingComposerEntry = true
                                composerPermissionLauncher.launch(
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS),
                                )
                            }
                        } else {
                            announce(subPage.primaryActionSw)
                        }
                    },
                    onGoBack = {
                        depth = 0
                        announce(activePage.instructionsSw)
                    },
                )
            }
            PageIndicator(
                pageCount = subPages.size,
                currentPage = subPagerState.realIndex(subPages.size),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        } else if (depth == 2) {
            // "Soma ujumbe" date-group list (PLAN.md Phase 4, Option A
            // grouping pass). Same gesture vocabulary as every other
            // level: swipe to browse date buckets, single-tap repeats
            // the preview, double-tap drills into that bucket's own
            // messages (depth 3), swipe-down goes back to the Ujumbe
            // sub-menu (depth 1) — NOT depth 0, consistent with every
            // other depth-1<->depth-N relationship in this app only
            // going back one level at a time.
            if (dateGroups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = "Hauna ujumbe.", fontSize = 20.sp) // "You have no messages."
                }
            } else {
                val dateGroupPagerState = rememberPagerState(initialPage = startVirtualPage(dateGroups.size)) { VIRTUAL_PAGE_COUNT }
                val currentDateGroupRealIndex =
                    ((dateGroupPagerState.settledPage % dateGroups.size) + dateGroups.size) % dateGroups.size
                LaunchedEffect(depth, currentDateGroupRealIndex, dateGroups) {
                    statusMessage = dateGroupPreviewSw(dateGroups[currentDateGroupRealIndex])
                }
                HorizontalPager(
                    state = dateGroupPagerState,
                    modifier = Modifier.fillMaxSize().weight(1f),
                ) { virtualIndex ->
                    val dateGroup = dateGroups[((virtualIndex % dateGroups.size) + dateGroups.size) % dateGroups.size]
                    DateGroupListItemContent(
                        dateGroup = dateGroup,
                        onStatusChange = announce,
                        onEnter = {
                            activeDateGroupIndex = dateGroupPagerState.realIndex(dateGroups.size)
                            depth = 3
                            announce(messagePreviewSw(dateGroup.messages[0], simuRepository))
                        },
                        onGoBack = {
                            depth = 1
                            announce(pages[activeTopPageIndex].subPages.first { it.opensMessageReader }.instructionsSw)
                        },
                    )
                }
                PageIndicator(
                    pageCount = dateGroups.size,
                    currentPage = dateGroupPagerState.realIndex(dateGroups.size),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
        } else if (depth == 3) {
            // depth == 3: messages within ONE date group (PLAN.md Phase
            // 4, Option A). Reuses the exact same gesture vocabulary as
            // depth 2 (swipe to browse, single-tap repeats the
            // sender+time preview, double-tap speaks the full body,
            // swipe-down goes back) — back here returns to depth 2 (the
            // date-group list), not depth 1/0.
            val activeDateGroup = dateGroups[activeDateGroupIndex]
            val groupMessages = activeDateGroup.messages
            val messagePagerState = rememberPagerState(initialPage = startVirtualPage(groupMessages.size)) { VIRTUAL_PAGE_COUNT }
            val currentMessageRealIndex =
                ((messagePagerState.settledPage % groupMessages.size) + groupMessages.size) % groupMessages.size
            LaunchedEffect(depth, activeDateGroupIndex, currentMessageRealIndex) {
                statusMessage = messagePreviewSw(groupMessages[currentMessageRealIndex], simuRepository)
            }
            HorizontalPager(
                state = messagePagerState,
                modifier = Modifier.fillMaxSize().weight(1f),
            ) { virtualIndex ->
                val message = groupMessages[((virtualIndex % groupMessages.size) + groupMessages.size) % groupMessages.size]
                MessageReaderContent(
                    message = message,
                    simuRepository = simuRepository,
                    onStatusChange = announce,
                    onGoBack = {
                        depth = 2
                        announce(dateGroupPreviewSw(activeDateGroup))
                    },
                )
            }
            PageIndicator(
                pageCount = groupMessages.size,
                currentPage = messagePagerState.realIndex(groupMessages.size),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        } else if (depth == 4) {
            // "Andika ujumbe" recipient capture screen (PLAN.md Phase 4,
            // press-and-hold-to-speak/release-to-transcribe rebuild —
            // explicit user request, murmur-android pattern). Two
            // separate pointer-input regions so "hold to record" never
            // has to be disambiguated from "tap to confirm" by the
            // gesture recognizer: the top RECORD button is a plain
            // press/release detector (mirrors murmur-android's
            // `DictationController` demo verbatim), the bottom CONFIRM
            // area is this app's normal [gestureNavigation] (single-tap
            // repeats the current draft, double-tap resolves the
            // contact and advances to depth 5, swipe-down cancels back
            // to the Ujumbe sub-menu at depth 1).
            Column(
                modifier = Modifier.fillMaxSize().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    startComposerRecording()
                                    tryAwaitRelease()
                                    stopComposerRecording()
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isComposerRecording) {
                            "Inasikiliza... achia kumaliza" // "Listening... release to finish"
                        } else {
                            "Shikilia hapa useme mpokeaji" // "Hold here and say the recipient"
                        },
                        fontSize = 20.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .gestureNavigation { event ->
                            when (event) {
                                GestureEvent.SingleTap -> {
                                    announce(
                                        composerRecipientDraft?.let { "Ulisema: $it." }
                                            ?: "Bado hujasema jina au namba. Shikilia kitufe juu.",
                                    )
                                }
                                GestureEvent.DoubleTap -> {
                                    val draft = composerRecipientDraft
                                    if (draft == null) {
                                        announce("Bado hujasema jina au namba. Shikilia kitufe juu.")
                                    } else {
                                        val bestContact = simuRepository.searchContactsByVoicedName(draft, maxResults = 1).firstOrNull()
                                        composerRecipientNumber = bestContact?.number ?: draft
                                        composerRecipientLabel = bestContact?.displayName ?: draft
                                        composerBodyDraft = null
                                        depth = 5
                                        announce("Shikilia kitufe na useme ujumbe wako, kisha achia.") // "Hold the button and say your message, then release."
                                    }
                                }
                                GestureEvent.SwipeDown -> {
                                    composerActive = false
                                    depth = 1
                                    announce("Umeghairi. Haujatuma ujumbe.") // "You cancelled. You have not sent a message."
                                }
                                else -> {}
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Gusa mara mbili kuthibitisha. Sugua chini kughairi.", // "Double-tap to confirm. Swipe down to cancel."
                        fontSize = 14.sp,
                    )
                }
            }
        } else if (depth == 5) {
            // "Andika ujumbe" message-body capture screen — same two-
            // region mechanic as depth 4. Double-tap here does a FINAL
            // combined readback (recipient + full body) and actually
            // sends on the SECOND double-tap in a row, directly
            // addressing the thesis §3.2.2.2 "message verification
            // after finishing writing" finding: the user gets one more
            // explicit confirm gate before anything is sent, not an
            // immediate send on the first confirm.
            Column(
                modifier = Modifier.fillMaxSize().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    startComposerRecording()
                                    tryAwaitRelease()
                                    stopComposerRecording()
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isComposerRecording) {
                            "Inasikiliza... achia kumaliza"
                        } else {
                            "Shikilia hapa useme ujumbe" // "Hold here and say the message"
                        },
                        fontSize = 20.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .gestureNavigation { event ->
                            when (event) {
                                GestureEvent.SingleTap -> {
                                    announce(
                                        composerBodyDraft?.let { "Ulisema: $it." }
                                            ?: "Bado hujasema ujumbe. Shikilia kitufe juu.",
                                    )
                                }
                                GestureEvent.DoubleTap -> {
                                    val body = composerBodyDraft
                                    val label = composerRecipientLabel
                                    val number = composerRecipientNumber
                                    if (body == null || label == null || number == null) {
                                        announce("Bado hujasema ujumbe. Shikilia kitufe juu.")
                                    } else if (!composerBodyPendingSend) {
                                        composerBodyPendingSend = true
                                        announce("Utatuma kwa $label: $body. Gusa mara mbili tena kutuma, au sugua chini kughairi.")
                                    } else {
                                        try {
                                            ujumbeRepository.sendSms(number, body)
                                            composerActive = false
                                            composerBodyPendingSend = false
                                            depth = 1
                                            announce("Ujumbe umetumwa kwa $label.") // "Message sent to <recipient>."
                                        } catch (e: Exception) {
                                            composerBodyPendingSend = false
                                            announce("Imeshindikana kutuma ujumbe: ${e.message}") // "Failed to send message: <error>"
                                        }
                                    }
                                }
                                GestureEvent.SwipeDown -> {
                                    composerBodyPendingSend = false
                                    depth = 4
                                    announce(
                                        composerRecipientDraft?.let { "Ulisema: $it." }
                                            ?: "Shikilia kitufe na useme jina au namba ya mpokeaji, kisha achia.",
                                    )
                                }
                                else -> {}
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Gusa mara mbili kuthibitisha na kutuma. Sugua chini kurudi nyuma.", // "Double-tap to confirm and send. Swipe down to go back."
                        fontSize = 14.sp,
                    )
                }
            }
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
private fun SubPageContent(
    subPage: SubPage,
    onStatusChange: (String) -> Unit,
    onDoubleTap: () -> Unit,
    onGoBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(subPage) {
                detectTapGestures(
                    onTap = { onStatusChange(subPage.instructionsSw) },
                    onDoubleTap = { onDoubleTap() },
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

/**
 * Builds the short spoken preview for a date group, per PLAN.md Phase 4
 * "Soma ujumbe" Option A grouping pass: "[Jina la kundi], ujumbe [N]."
 * ("[Group name], [N] messages.") e.g. "Leo, ujumbe 3."
 */
private fun dateGroupPreviewSw(dateGroup: UjumbeRepository.DateGroup): String =
    "${dateGroup.labelSw}, ujumbe ${dateGroup.messages.size}."

/**
 * One date bucket's row inside the "Soma ujumbe" depth-2 date-group-list
 * pager (PLAN.md Phase 4, Option A grouping pass). Mirrors
 * [SubPageContent]'s gesture pattern: swipe handled by the enclosing
 * HorizontalPager; single-tap repeats the group preview; double-tap
 * drills into THAT bucket's own messages (depth 3, calls [onEnter]);
 * swipe-down goes back to the Ujumbe sub-menu (depth 1).
 */
@Composable
private fun DateGroupListItemContent(
    dateGroup: UjumbeRepository.DateGroup,
    onStatusChange: (String) -> Unit,
    onEnter: () -> Unit,
    onGoBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(dateGroup) {
                detectTapGestures(
                    onTap = { onStatusChange(dateGroupPreviewSw(dateGroup)) },
                    onDoubleTap = { onEnter() },
                )
            }
            .pointerInput(dateGroup) {
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
            Text(text = dateGroup.labelSw, fontSize = 24.sp)
            Text(text = "Ujumbe ${dateGroup.messages.size}", fontSize = 14.sp) // "N messages"
        }
    }
}

/**
 * Builds the short sender+relative-time spoken preview for a message,
 * per PLAN.md Phase 4 "Soma ujumbe" Option B: "Ujumbe kutoka [jina/namba],
 * [muda] zilizopita." ("Message from [name/number], [time] ago.") —
 * speaks a saved contact's name via [SimuRepository.contactNameForNumber]
 * when available, falling back to the raw [UjumbeRepository.SmsMessage.address]
 * otherwise. Does NOT include the message body — that is deliberately
 * reserved for double-tap (see [MessageReaderContent]), the whole point
 * of Option B over Option A (sender-first preview, full body is a
 * separate explicit gesture, not forced on every swipe).
 */
private fun messagePreviewSw(message: UjumbeRepository.SmsMessage, simuRepository: SimuRepository): String {
    val sender = simuRepository.contactNameForNumber(message.address) ?: message.address
    val relativeTime = UjumbeRepository.relativeTimeSw(message.timestampMs)
    return "Ujumbe kutoka $sender, $relativeTime."
}

/**
 * A single message inside the "Soma ujumbe" depth-2 pager (PLAN.md
 * Phase 4, Option B). Reuses the exact same gesture vocabulary as
 * [SubPageContent] (swipe to browse — handled by the enclosing
 * HorizontalPager, not here; single-tap repeats the sender+time
 * preview; double-tap speaks the full body; swipe-down goes back) —
 * deliberately NOT a new/sixth gesture meaning.
 */
@Composable
private fun MessageReaderContent(
    message: UjumbeRepository.SmsMessage,
    simuRepository: SimuRepository,
    onStatusChange: (String) -> Unit,
    onGoBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(message) {
                detectTapGestures(
                    onTap = { onStatusChange(messagePreviewSw(message, simuRepository)) },
                    onDoubleTap = { onStatusChange(message.body) },
                )
            }
            .pointerInput(message) {
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
            val sender = simuRepository.contactNameForNumber(message.address) ?: message.address
            Text(text = sender, fontSize = 22.sp)
            Text(text = UjumbeRepository.relativeTimeSw(message.timestampMs), fontSize = 13.sp)
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
