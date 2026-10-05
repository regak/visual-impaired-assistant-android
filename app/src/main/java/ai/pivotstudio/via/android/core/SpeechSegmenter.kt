package ai.pivotstudio.via.android.core

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Wraps sherpa-onnx's Silero VAD so held-button recordings aren't sent to
 * [ai.pivotstudio.via.android.asr.OmnilingualAsrEngine] verbatim — trims
 * leading/trailing silence and pads/gap-bridges detected segments. Ported
 * EXACTLY from the sibling `murmur-android` project's
 * `core/SpeechSegmenter.kt` (see that file for the two real-device-tested
 * failure modes this padding/bridging fixes: edge clipping and mid-
 * sentence dropouts on quiet speech).
 *
 * Model file (`silero_vad.onnx`) is NOT bundled in the APK — fetched by
 * [ModelDownloader] on first launch alongside the ASR model files. This
 * class must never be constructed before [ModelDownloader.isComplete]
 * confirms the VAD file exists on disk (same native-crash risk as the ASR
 * engine — see android-app-building skill pitfall #9).
 */
class SpeechSegmenter(context: Context) {

    private val vad: Vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "${ModelDownloader(context).vadDir.absolutePath}/silero_vad.onnx",
                threshold = 0.25f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.1f,
                windowSize = WINDOW_SIZE_SAMPLES,
                maxSpeechDuration = 30.0f,
            ),
            sampleRate = SAMPLE_RATE_HZ,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    private var rawBuffer: ShortArray = ShortArray(0)

    /**
     * Feed the full held-button recording in. VAD windows are fixed-size
     * ([WINDOW_SIZE_SAMPLES]); the final partial window (if any) is padded
     * with silence so no trailing speech is dropped.
     */
    fun accept(pcm16kMono: ShortArray) {
        rawBuffer = pcm16kMono
        var offset = 0
        while (offset < pcm16kMono.size) {
            val end = minOf(offset + WINDOW_SIZE_SAMPLES, pcm16kMono.size)
            val window = FloatArray(WINDOW_SIZE_SAMPLES)
            for (i in offset until end) {
                window[i - offset] = pcm16kMono[i] / 32768.0f
            }
            vad.acceptWaveform(window)
            offset = end
        }
        vad.flush()
    }

    /**
     * Returns the speech portion only, as one concatenated segment (see
     * class doc for padding/gap-bridge rationale). Returns an empty array
     * if VAD found no speech (e.g. the user held the button but said
     * nothing).
     */
    fun extractSpeech(): ShortArray {
        val ranges = ArrayList<IntRange>()
        while (!vad.empty()) {
            val segment = vad.front()
            val start = (segment.start - PAD_SAMPLES).coerceAtLeast(0)
            val end = (segment.start + segment.samples.size + PAD_SAMPLES).coerceAtMost(rawBuffer.size)
            if (start < end) ranges.add(start until end)
            vad.pop()
        }
        if (ranges.isEmpty()) return ShortArray(0)

        ranges.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        var current = ranges[0]
        for (next in ranges.drop(1)) {
            current = if (next.first - current.last <= GAP_BRIDGE_SAMPLES) {
                current.first until maxOf(current.last + 1, next.last + 1)
            } else {
                merged.add(current)
                next
            }
        }
        merged.add(current)

        val result = ArrayList<Short>()
        for (range in merged) {
            for (i in range) result.add(rawBuffer[i])
        }
        return result.toShortArray()
    }

    fun reset() {
        vad.reset()
    }

    fun close() {
        vad.release()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val WINDOW_SIZE_SAMPLES = 512
        const val PAD_SAMPLES = (SAMPLE_RATE_HZ * 0.24).toInt()
        const val GAP_BRIDGE_SAMPLES = (SAMPLE_RATE_HZ * 0.7).toInt()
    }
}
