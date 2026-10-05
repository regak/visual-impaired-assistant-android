package ai.pivotstudio.via.android.core

import ai.pivotstudio.via.android.asr.OmnilingualAsrModel
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the Omnilingual ASR CTC (300M, int8) model files + the shared
 * Silero VAD model into app-private storage on first run, instead of
 * bundling them inside the APK.
 *
 * Ported EXACTLY from the sibling `murmur-android` project's
 * `core/ModelDownloader.kt` pattern (see that file for full rationale):
 * bundling ~350MB+ of model weights in the APK makes the *download* huge
 * even though the on-disk footprint is identical either way. Deferring the
 * fetch to first launch keeps the APK itself small (Play-Store-adjacent
 * "downloadable AI module" UX), at the cost of a one-time first-run wait
 * with a progress UI (see `MainActivity`'s download gate).
 *
 * Only one ASR engine exists in this app (unlike murmur-android's
 * swappable-model Settings screen), so there is no per-engine directory
 * dance here — just one model directory
 * ([ai.pivotstudio.via.android.asr.OmnilingualAsrModel.dirFor]) plus the
 * shared VAD directory. Files are fetched from a GitHub Release on THIS
 * repo (`regak/visual-impaired-assistant-android`, tag `models-v1`) rather
 * than the upstream `k2-fsa/sherpa-onnx` release directly, for the same
 * stability reason murmur-android re-hosts its own models: an upstream
 * release can be renamed/removed/rate-limited outside this project's
 * control, whereas a release on this repo is ours to keep stable.
 */
class ModelDownloader(private val context: Context) {

    data class Progress(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
    )

    private val modelsRoot: File
        get() = File(context.filesDir, "models")

    val asrDir: File
        get() = OmnilingualAsrModel.dirFor(context)

    val vadDir: File
        get() = File(modelsRoot, "vad")

    fun isComplete(): Boolean = asrFilesComplete() && vadFilesComplete()

    private fun asrFilesComplete(): Boolean =
        File(asrDir, OmnilingualAsrModel.MODEL_FILE).exists() &&
            File(asrDir, OmnilingualAsrModel.TOKENS_FILE).exists()

    private fun vadFilesComplete(): Boolean = File(vadDir, VAD_FILE_NAME).exists()

    /**
     * Downloads any missing ASR/VAD files, skipping files already on disk
     * — so resuming an interrupted download never re-fetches what already
     * landed. Reports progress via [onProgress].
     */
    suspend fun ensureDownloaded(onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        asrDir.mkdirs()
        vadDir.mkdirs()

        val jobs = listOf(
            Triple(asrDir, OmnilingualAsrModel.MODEL_FILE, "$RELEASE_BASE_URL/${OmnilingualAsrModel.MODEL_FILE}"),
            Triple(asrDir, OmnilingualAsrModel.TOKENS_FILE, "$RELEASE_BASE_URL/${OmnilingualAsrModel.TOKENS_FILE}"),
            Triple(vadDir, VAD_FILE_NAME, "$RELEASE_BASE_URL/$VAD_FILE_NAME"),
        )

        val missing = jobs.filterNot { (dir, name, _) -> File(dir, name).exists() }
        missing.forEachIndexed { index, (dir, name, url) ->
            val destFile = File(dir, name)
            val tmpFile = File(dir, "$name.part")
            downloadToFile(url, tmpFile) { bytesDone, bytesTotal ->
                onProgress(Progress(name, index + 1, missing.size, bytesDone, bytesTotal))
            }
            tmpFile.renameTo(destFile)
        }
    }

    private fun downloadToFile(url: String, dest: File, onBytes: (Long, Long) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        connection.connect()
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    done += read
                    onBytes(done, total)
                }
            }
        }
    }

    companion object {
        private const val RELEASE_BASE_URL =
            "https://github.com/regak/visual-impaired-assistant-android/releases/download/models-v1"
        private const val VAD_FILE_NAME = "silero_vad.onnx"
    }
}
