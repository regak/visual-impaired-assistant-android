package ai.pivotstudio.via.android.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal OpenAI Chat Completions client powering the "Ongea" (Talk)
 * screen — this app's recreation of ChatGPT's voice-conversation mode
 * (user request: speak freely, hear the AI's reply, loop). Uses plain
 * [HttpURLConnection] + the Android-bundled `org.json` classes instead of
 * adding a new HTTP/JSON dependency (OkHttp/Retrofit/Moshi/Gson), since
 * this is the only network call in the whole app and doesn't justify the
 * extra APK weight or new failure surface.
 *
 * Keeps the running conversation in [history] so follow-up questions have
 * context, exactly like a real ChatGPT voice session — [reset] clears it
 * when the user exits the Ongea screen (swipe-down), per this app's
 * existing convention that leaving a flow resets its state (see
 * MainActivity's composerActive/depth=1 reset pattern for "Andika
 * ujumbe").
 *
 * The system prompt asks for short, spoken-style answers (no markdown,
 * no bullet lists, no headings) because every reply here is read aloud
 * by TTS to a non-sighted user — a long written-style answer with
 * markdown syntax read literally by TTS would be unusable.
 */
class OpenAiChatClient(private val apiKey: String) {

    sealed class ChatResult {
        data class Success(val reply: String) : ChatResult()
        data class Failure(val message: String) : ChatResult()
    }

    private val history = mutableListOf<JSONObject>()

    /** Sends [userText] plus prior turns, appends both turns to [history] on success. */
    suspend fun sendMessage(userText: String): ChatResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ChatResult.Failure(
                "No OpenAI API key is configured. Ask your developer to set one.",
            )
        }

        val userMessage = JSONObject().put("role", "user").put("content", userText)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (turn in history) messages.put(turn)
        messages.put(userMessage)

        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("max_tokens", 220)
            .put("temperature", 0.7)

        try {
            val connection = (URL(ENDPOINT).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }

            if (responseCode !in 200..299) {
                Log.w(TAG, "OpenAI error $responseCode: $responseText")
                return@withContext ChatResult.Failure(friendlyErrorFor(responseCode))
            }

            val reply = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            history.add(userMessage)
            history.add(JSONObject().put("role", "assistant").put("content", reply))
            // Keep only the last N turns so the request body doesn't grow
            // unbounded over a long conversation session.
            while (history.size > MAX_HISTORY_MESSAGES) history.removeAt(0)

            ChatResult.Success(reply)
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI request failed", e)
            ChatResult.Failure("I couldn't reach the AI service. Check your internet connection and try again.")
        }
    }

    fun reset() {
        history.clear()
    }

    private fun friendlyErrorFor(httpCode: Int): String = when (httpCode) {
        401 -> "The AI service rejected the API key. Ask your developer to check it."
        429 -> "The AI service is rate-limited or out of quota right now. Try again shortly."
        else -> "The AI service returned an error. Try again shortly."
    }

    companion object {
        private const val TAG = "VIA/OpenAiChat"
        private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-4o-mini"
        private const val MAX_HISTORY_MESSAGES = 20
        private const val SYSTEM_PROMPT =
            "You are a helpful voice assistant speaking to a visually impaired user over " +
                "text-to-speech. Keep every answer short (2-4 sentences), conversational, and " +
                "spoken-style English. Never use markdown, bullet points, headings, or emojis " +
                "— your whole reply is read aloud verbatim."
    }
}
