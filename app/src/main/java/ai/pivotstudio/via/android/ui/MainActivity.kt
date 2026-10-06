package ai.pivotstudio.via.android.ui

import ai.pivotstudio.via.android.asr.OmnilingualAsrEngine
import ai.pivotstudio.via.android.core.AudioCapture
import ai.pivotstudio.via.android.core.DictationController
import ai.pivotstudio.via.android.core.GestureEvent
import ai.pivotstudio.via.android.core.ModelDownloader
import ai.pivotstudio.via.android.core.SpeechSegmenter
import ai.pivotstudio.via.android.core.VoiceInputController
import ai.pivotstudio.via.android.core.gestureNavigation
import ai.pivotstudio.via.android.sms.UjumbeRepository
import ai.pivotstudio.via.android.telephony.SimuRepository
import ai.pivotstudio.via.android.tts.SwahiliTts
import ai.pivotstudio.via.android.ui.simu.SimuScreen
import ai.pivotstudio.via.android.ui.ujumbe.UjumbeScreen
import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Entry point. Only two sections per project scope: Simu (phone) and
 * Ujumbe (SMS) — explicitly no Muziki/Kikokotoa/Habari (see PLAN.md).
 *
 * This scaffold uses a plain two-button chooser instead of the original
 * thesis's swipe-based ViewPager navigation; the swipe/tap gesture
 * detector described in PLAN.md Phase 4 (CountDownTimer-based single/
 * double/long tap + 4-direction swipe) will replace this once built, as
 * the primary navigation for a non-sighted user.
 *
 * On-first-launch model download gate (PLAN.md Phase 1, item 1 + 4 + 5):
 * [ModelDownloader] fetches the Omnilingual ASR CTC (300M, int8) model +
 * shared Silero VAD model from this repo's GitHub Release into app-private
 * storage before anything else happens. A progress UI (file name, index/
 * count, percentage) is shown while this runs — ported exactly from the
 * sibling `murmur-android` project's `MainActivity` download-gate pattern.
 *
 * IMPORTANT (see android-app-building skill pitfall #9): [OmnilingualAsrEngine]
 * wraps a native sherpa-onnx `OfflineRecognizer`, which crashes the whole
 * process (no catchable exception) if constructed before its model files
 * exist on disk. It is therefore never constructed until
 * [ModelDownloader.isComplete] is true — load() itself also double-checks
 * this (see that class), but the ordering here is the real guarantee.
 *
 * Phase 1 milestone (item 5): once loaded, a press-and-hold button lets a
 * person speak and see the raw transcript appear directly on screen — this
 * proves the model + wrapper actually work before any telephony/SMS voice
 * flow (Phase 2/3) is built on top. Kept here as a standalone debug demo
 * even after Phase 2/3 wiring landed, since it is the fastest way to sanity
 * check the ASR model is actually working on a given device.
 *
 * Runtime permission onboarding (PLAN.md Phase 3, item 9): all dangerous
 * permissions this app needs (`RECORD_AUDIO`, `CALL_PHONE`,
 * `READ_CONTACTS`, `SEND_SMS`, `READ_SMS`) are requested together up front
 * once the model/engine finishes loading, rather than one at a time as
 * each feature is first used — a non-sighted user cannot read an on-screen
 * permission dialog unassisted, so batching the request into a single
 * TTS-narrated moment (rather than scattering surprise dialogs across
 * first use of each screen) is the more predictable flow. The very first
 * grant still needs a sighted helper or TalkBack, per PLAN.md's noted
 * bootstrapping gap.
 */
class MainActivity : ComponentActivity() {

    private var screenState = mutableStateOf<ScreenState>(ScreenState.Downloading("Preparing...", null))
    private var lastTranscript = mutableStateOf("")
    private lateinit var downloader: ModelDownloader
    private lateinit var audioCapture: AudioCapture
    private lateinit var tts: SwahiliTts
    private lateinit var simuRepository: SimuRepository
    private lateinit var ujumbeRepository: UjumbeRepository
    private var asrEngine: OmnilingualAsrEngine? = null
    private var segmenter: SpeechSegmenter? = null
    private var controller: DictationController? = null
    private var voiceInputController: VoiceInputController? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        lastTranscript.value = if (granted) "" else "Mic permission denied — hold-to-talk demo disabled."
    }

    private val requestDangerousPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* results observed lazily via hasMicPermission()/etc at point of use */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        downloader = ModelDownloader(this)
        audioCapture = AudioCapture(this)
        tts = SwahiliTts(this)
        simuRepository = SimuRepository(this)
        ujumbeRepository = UjumbeRepository(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val state = screenState.value) {
                        is ScreenState.Downloading -> DownloadGateScreen(state.status, state.progress)
                        is ScreenState.Failed -> DownloadGateScreen(state.message, null)
                        ScreenState.Ready -> AppRoot(
                            lastTranscript = lastTranscript.value,
                            onPressStart = { startListening() },
                            onPressEnd = { stopListeningAndTranscribe() },
                            voiceInputController = voiceInputController,
                            simuRepository = simuRepository,
                            ujumbeRepository = ujumbeRepository,
                            tts = tts,
                        )
                    }
                }
            }
        }

        lifecycleScope.launch { tts.init() }
        ensureModelThenLoadEngine()
    }

    private fun ensureModelThenLoadEngine() {
        lifecycleScope.launch {
            if (!downloader.isComplete()) {
                screenState.value = ScreenState.Downloading("Downloading speech model...", 0f)
                try {
                    downloader.ensureDownloaded { progress ->
                        val fraction = if (progress.bytesTotal > 0) {
                            progress.bytesDone.toFloat() / progress.bytesTotal.toFloat()
                        } else {
                            0f
                        }
                        val mbDone = progress.bytesDone / (1024 * 1024)
                        val mbTotal = progress.bytesTotal / (1024 * 1024)
                        screenState.value = ScreenState.Downloading(
                            "Downloading ${progress.fileName} " +
                                "(${progress.fileIndex}/${progress.fileCount}): " +
                                "${mbDone}MB / ${mbTotal}MB",
                            fraction,
                        )
                    }
                } catch (e: Exception) {
                    Log.e("VIA/Download", "Model download failed", e)
                    screenState.value = ScreenState.Failed(
                        "Model download failed: ${e.message}. Check your connection and restart the app.",
                    )
                    return@launch
                }
            }

            // Safe to construct the native sherpa-onnx recognizer now — the
            // download gate above guarantees the backing files exist on disk.
            screenState.value = ScreenState.Downloading("Loading speech model...", null)
            val engine = OmnilingualAsrEngine(this@MainActivity)
            try {
                engine.load()
            } catch (e: Exception) {
                Log.e("VIA/Engine", "Failed to load ASR engine", e)
                screenState.value = ScreenState.Failed(
                    "Engine load failed: ${e.message}. Try reinstalling the app.",
                )
                return@launch
            }
            asrEngine = engine
            // Silero VAD trims leading/trailing silence from the held-button
            // recording before it reaches the ASR engine (PLAN.md Phase 1
            // item 3) — same native-construction-after-download-gate rule
            // applies to this object too.
            val vadSegmenter = SpeechSegmenter(this@MainActivity)
            segmenter = vadSegmenter
            controller = DictationController(
                audioCapture,
                engine,
                vadSegmenter,
                applicationContext,
            ) { result ->
                lastTranscript.value = when (result) {
                    is DictationController.Result.Transcript -> result.text
                    is DictationController.Result.NoSpeechDetected ->
                        "(no speech detected — try holding longer / speaking louder)"
                    is DictationController.Result.Error -> "Transcription error: ${result.message}"
                }
            }
            // Second VAD segmenter instance: VoiceInputController's
            // capture/confirm loop runs independently of the raw demo's
            // DictationController above and must not share VAD state with
            // it (both could be mid-segment at once in theory).
            voiceInputController = VoiceInputController(
                AudioCapture(this@MainActivity),
                SpeechSegmenter(this@MainActivity),
                engine,
                tts,
            )
            screenState.value = ScreenState.Ready

            requestAllDangerousPermissionsIfNeeded()
        }
    }

    private fun requestAllDangerousPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
        )
        requestDangerousPermissions.launch(permissions)
    }

    private fun startListening() {
        if (!audioCapture.hasMicPermission()) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        controller?.startListening(lifecycleScope)
    }

    private fun stopListeningAndTranscribe() {
        controller?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioCapture.stop()
        segmenter?.close()
        asrEngine?.close()
        tts.shutdown()
    }
}

private sealed class ScreenState {
    data class Downloading(val status: String, val progress: Float?) : ScreenState()
    data class Failed(val message: String) : ScreenState()
    data object Ready : ScreenState()
}

@Composable
private fun DownloadGateScreen(status: String, progress: Float?) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Visual Impaired Assistant", fontSize = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = status)
            if (progress != null) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${(progress * 100).toInt()}%")
            }
        }
    }
}

private enum class Section { CHOOSER, SIMU, UJUMBE }

@Composable
private fun AppRoot(
    lastTranscript: String,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    voiceInputController: VoiceInputController?,
    simuRepository: SimuRepository,
    ujumbeRepository: UjumbeRepository,
    tts: SwahiliTts,
) {
    var section by remember { mutableStateOf(Section.CHOOSER) }
    when (section) {
        // Gesture-first home screen (PLAN.md Phase 4, top-level nav only
        // this pass): swipe right -> Simu, swipe left -> Ujumbe, single
        // tap -> repeat the orientation prompt. No visible buttons here —
        // this whole screen IS the gesture surface, matching how a
        // non-sighted user actually needs to navigate (can't read button
        // labels). The old two-button chooser and the Phase 1 ASR demo
        // box are kept below as a secondary, explicitly-labeled debug aid
        // (still reachable by sighted testers / this build environment's
        // lack of a real device), not removed outright.
        Section.CHOOSER -> HomeGestureScreen(
            lastTranscript = lastTranscript,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            tts = tts,
            onNavigate = { section = it },
        )
        Section.SIMU -> {
            val vic = voiceInputController
            if (vic != null) {
                SimuScreen(
                    voiceInputController = vic,
                    simuRepository = simuRepository,
                    tts = tts,
                    onGoHome = { section = Section.CHOOSER },
                )
            } else {
                Text("Speech engine still loading...")
            }
        }
        Section.UJUMBE -> {
            val vic = voiceInputController
            if (vic != null) {
                UjumbeScreen(
                    voiceInputController = vic,
                    ujumbeRepository = ujumbeRepository,
                    tts = tts,
                    onGoHome = { section = Section.CHOOSER },
                )
            } else {
                Text("Speech engine still loading...")
            }
        }
    }
}

/**
 * The app's main/first screen (per explicit user direction: gesture nav
 * is the primary feature, ahead of Simu/Ujumbe). Speaks a one-time
 * orientation prompt on first composition, then listens for:
 * - swipe right -> Simu
 * - swipe left -> Ujumbe
 * - single tap -> repeat the orientation prompt (in case it was missed)
 * - long press -> also repeats the prompt (reserved for a future "help"
 *   action once there's more than one thing to disambiguate)
 *
 * Double-tap and swipe up/down are recognized by [gestureNavigation] but
 * have no action on this screen yet (swipe down is reserved for sub-
 * screens to mean "go home" — meaningless on the home screen itself).
 */
@Composable
private fun HomeGestureScreen(
    lastTranscript: String,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    tts: SwahiliTts,
    onNavigate: (Section) -> Unit,
) {
    val scope = rememberCoroutineScopeCompat()
    val orientationPrompt = "Piga simu, kaza kulia. Tuma ujumbe, kaza kushoto." +
        " Gusa mara moja kusikia tena." // "Swipe right to call. Swipe left for messages. Tap once to hear this again."

    LaunchedEffect(Unit) {
        tts.speakAndAwait(orientationPrompt)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gestureNavigation { gesture ->
                when (gesture) {
                    GestureEvent.SwipeRight -> onNavigate(Section.SIMU)
                    GestureEvent.SwipeLeft -> onNavigate(Section.UJUMBE)
                    GestureEvent.SingleTap, GestureEvent.LongPress ->
                        scope.launch { tts.speakAndAwait(orientationPrompt) }
                    else -> {}
                }
            }
            .padding(16.dp),
    ) {
        Text("Visual Impaired Assistant")
        Spacer(modifier = Modifier.height(8.dp))
        Text("Kaza kulia: Simu. Kaza kushoto: Ujumbe. Gusa: sikia tena.")
        Spacer(modifier = Modifier.height(24.dp))

        // Phase 1 milestone demo (PLAN.md item 5): press-and-hold to speak
        // Swahili, release to see the raw transcript. Kept as a
        // standalone, explicitly-labeled sanity-check tool — separate
        // touch target from the gesture surface above so it doesn't
        // interfere with swipe/tap navigation.
        Text("ASR demo (debug): hold the box below, speak, then release.")
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onPressStart()
                            tryAwaitRelease()
                            onPressEnd()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("Hold to talk")
        }
        if (lastTranscript.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = lastTranscript, fontSize = 18.sp)
        }
    }
}

/** Tiny indirection so this file doesn't need an extra Compose import line just for the common name. */
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
