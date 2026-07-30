package com.nerdginger.workoutmate

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal Anthropic Messages API client for "Build me a routine".
 *
 * The call lives here rather than in the WebView for one reason: the API key.
 * The page hands over a request body containing only the prompt and the output
 * schema; this class supplies the key and the model. The key is therefore never
 * readable from JavaScript, and there is no CORS preflight to satisfy because
 * this is an ordinary HTTP request rather than a browser fetch.
 *
 * `HttpURLConnection` and Android's bundled `org.json` cover this entirely —
 * one POST does not justify pulling in an HTTP stack.
 */
class ClaudeClient(private val store: SecureStore) {

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * [requestJson] is built by the page and carries `system`, `messages`, and
     * `output_config` (including the routine-plan JSON schema). Everything
     * security-relevant is layered on here.
     */
    fun createMessage(requestJson: String): Result {
        val apiKey = store.apiKey()
        if (apiKey.isNullOrBlank()) {
            return Result.Failure(
                "No API key saved. Add one in Settings, or use the copy/paste builder instead."
            )
        }

        val body = runCatching { JSONObject(requestJson) }.getOrElse {
            return Result.Failure("Could not build the request: ${it.message}")
        }
        body.put("model", store.model())
        if (!body.has("max_tokens")) body.put("max_tokens", DEFAULT_MAX_TOKENS)

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("content-type", "application/json")
                setRequestProperty("accept-encoding", "gzip")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
            }

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val payload = readBody(connection, isError = status !in 200..299)

            if (status !in 200..299) {
                Result.Failure(describeHttpError(status, payload))
            } else {
                parseMessage(payload)
            }
        } catch (e: Exception) {
            Result.Failure("Could not reach the Claude API: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, isError: Boolean): String {
        val raw: InputStream = (if (isError) connection.errorStream else connection.inputStream)
            ?: return ""
        val stream = if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
            GZIPInputStream(raw)
        } else {
            raw
        }
        return stream.use { it.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText) }
    }

    /**
     * A refusal arrives as a perfectly ordinary 200, so `stop_reason` has to be
     * checked before touching `content` — on a refusal the content array can be
     * empty and blind indexing would throw.
     */
    private fun parseMessage(payload: String): Result {
        val json = runCatching { JSONObject(payload) }.getOrElse {
            return Result.Failure("The API returned a response that could not be parsed.")
        }

        if (json.optString("stop_reason") == "refusal") {
            return Result.Failure(
                "Claude declined this request. Try rephrasing your answers, " +
                    "particularly anything about injuries or medical conditions."
            )
        }

        val text = collectText(json.optJSONArray("content"))
        if (text.isBlank()) {
            val reason = json.optString("stop_reason", "unknown")
            return Result.Failure(
                if (reason == "max_tokens") {
                    "The routine was cut off before it finished. Try asking for fewer days per week."
                } else {
                    "Claude returned an empty response (stop reason: $reason)."
                }
            )
        }
        return Result.Ok(text)
    }

    private fun collectText(content: JSONArray?): String {
        if (content == null) return ""
        return buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
    }

    private fun describeHttpError(status: Int, payload: String): String {
        val apiMessage = runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()

        val hint = when (status) {
            401 -> "Your API key was rejected. Check it in Settings."
            403 -> "That API key doesn't have access to this model."
            404 -> "Model not found — check the model name in Settings."
            429 -> "Rate limited by the API. Wait a moment and try again."
            in 500..599 -> "The API is having trouble right now. Try again shortly."
            else -> "The API rejected the request (HTTP $status)."
        }
        return if (apiMessage.isBlank()) hint else "$hint\n\n$apiMessage"
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        /** Comfortably fits a six-day program; non-streaming, so kept under the timeout. */
        private const val DEFAULT_MAX_TOKENS = 16000
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
    }
}
