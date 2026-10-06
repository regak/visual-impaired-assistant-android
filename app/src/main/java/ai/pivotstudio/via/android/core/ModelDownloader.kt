package ai.pivotstudio.via.android.core

import ai.pivotstudio.via.android.asr.OmnilingualAsrModel
import ai.pivotstudio.via.android.tts.SwahiliNeuralTtsModel
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

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

    val ttsDir: File
        get() = SwahiliNeuralTtsModel.dirFor(context)

    fun isComplete(): Boolean = asrFilesComplete() && vadFilesComplete() && ttsFilesComplete()

    private fun asrFilesComplete(): Boolean =
        File(asrDir, OmnilingualAsrModel.MODEL_FILE).exists() &&
            File(asrDir, OmnilingualAsrModel.TOKENS_FILE).exists()

    private fun vadFilesComplete(): Boolean = File(vadDir, VAD_FILE_NAME).exists()

    private fun ttsFilesComplete(): Boolean = SwahiliNeuralTtsModel.isDownloaded(context)

    /**
     * Downloads any missing ASR/VAD files, skipping files already on disk
     * — so resuming an interrupted download never re-fetches what already
     * landed. Reports progress via [onProgress].
     */
    suspend fun ensureDownloaded(onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        asrDir.mkdirs()
        vadDir.mkdirs()
        ttsDir.mkdirs()

        val jobs = listOf(
            Triple(asrDir, OmnilingualAsrModel.MODEL_FILE, "$RELEASE_BASE_URL/${OmnilingualAsrModel.MODEL_FILE}"),
            Triple(asrDir, OmnilingualAsrModel.TOKENS_FILE, "$RELEASE_BASE_URL/${OmnilingualAsrModel.TOKENS_FILE}"),
            Triple(vadDir, VAD_FILE_NAME, "$RELEASE_BASE_URL/$VAD_FILE_NAME"),
            Triple(ttsDir, SwahiliNeuralTtsModel.MODEL_FILE, "$RELEASE_BASE_URL/${SwahiliNeuralTtsModel.MODEL_FILE}"),
            Triple(ttsDir, SwahiliNeuralTtsModel.TOKENS_FILE, "$RELEASE_BASE_URL/tts-${SwahiliNeuralTtsModel.TOKENS_FILE}"),
            Triple(ttsDir, SwahiliNeuralTtsModel.ESPEAK_DATA_ARCHIVE, "$RELEASE_BASE_URL/${SwahiliNeuralTtsModel.ESPEAK_DATA_ARCHIVE}"),
        )

        // The TTS tokens.txt is uploaded under a distinct release asset
        // name (`tts-tokens.txt`) to avoid colliding with the ASR model's
        // own `tokens.txt` asset of the same base name on the same release.
        val missing = jobs.filterNot { (dir, name, _) -> File(dir, name).exists() }
            .filterNot { (dir, name, _) ->
                // Skip re-downloading the espeak-ng-data archive once it has
                // already been extracted (archive itself isn't kept on disk).
                name == SwahiliNeuralTtsModel.ESPEAK_DATA_ARCHIVE &&
                    SwahiliNeuralTtsModel.espeakDataDirFor(context).let { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }
            }
        missing.forEachIndexed { index, (dir, name, url) ->
            val destFile = File(dir, name)
            val tmpFile = File(dir, "$name.part")
            downloadToFile(url, tmpFile) { bytesDone, bytesTotal ->
                onProgress(Progress(name, index + 1, missing.size, bytesDone, bytesTotal))
            }
            tmpFile.renameTo(destFile)
            if (name == SwahiliNeuralTtsModel.ESPEAK_DATA_ARCHIVE) {
                extractTarGz(destFile, dir)
                destFile.delete()
            }
        }
    }

    /**
     * Minimal pure-JVM tar+gzip extractor — no `java.util.zip` tar support
     * exists in the standard library, and pulling in Apache Commons
     * Compress for one directory of 355 small files isn't worth a new
     * dependency. Handles regular files and directories only (what
     * `tar czf` of a plain directory tree produces); POSIX ustar header
     * layout, ignores PAX extended headers' payload beyond skipping them.
     */
    private fun extractTarGz(archive: File, destDir: File) {
        GZIPInputStream(archive.inputStream()).use { gzip ->
            val header = ByteArray(512)
            while (true) {
                val read = readFully(gzip, header)
                if (read < 512) break
                if (header.all { it == 0.toByte() }) continue // end-of-archive padding block

                val name = String(header, 0, 100).trim('\u0000').trimEnd()
                if (name.isEmpty()) continue
                val sizeOctal = String(header, 124, 12).trim('\u0000').trim()
                val size = if (sizeOctal.isBlank()) 0L else sizeOctal.toLong(8)
                val typeFlag = header[156]

                val entryFile = File(destDir, name)
                when (typeFlag) {
                    '5'.code.toByte() -> entryFile.mkdirs() // directory
                    else -> {
                        entryFile.parentFile?.mkdirs()
                        if (typeFlag == '0'.code.toByte() || typeFlag == 0.toByte()) {
                            entryFile.outputStream().use { out ->
                                copyExactly(gzip, out, size)
                            }
                        } else {
                            skipExactly(gzip, size)
                        }
                    }
                }
                // Tar pads each entry's data to a 512-byte boundary.
                val padding = ((512 - (size % 512)) % 512).toInt()
                if (padding > 0) skipExactly(gzip, padding.toLong())
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        return total
    }

    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactly(input: java.io.InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            remaining -= read
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
