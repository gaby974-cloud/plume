package com.alphateam.carnetencre.claude

import android.util.Base64
import com.alphateam.carnetencre.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Erreur destinée à l'utilisateur, déjà formulée en français. */
class ClaudeException(message: String) : Exception(message)

/** Un tour de conversation du panneau. L'image n'est jointe qu'au premier tour utilisateur. */
sealed class Turn {
    class User(val text: String, val imagePng: ByteArray? = null) : Turn()

    /** [content] : blocs renvoyés tels quels à l'API au tour suivant (texte et réflexion). */
    class Assistant(val text: String, val content: JSONArray) : Turn()
}

/** Progression d'une réponse en streaming ; reste lisible si la requête est interrompue. */
class StreamProgress {
    val text = StringBuilder()
    var inputTokens = 0
    var outputTokens = 0
    var stopReason: String? = null
    internal val blocks = sortedMapOf<Int, JSONObject>()

    /** Blocs à renvoyer à l'API : les blocs de réflexion incomplets (sans signature) sont écartés. */
    fun content(): JSONArray {
        val arr = JSONArray()
        for (b in blocks.values) {
            when (b.optString("type")) {
                "text" -> if (b.optString("text").isNotEmpty()) arr.put(b)
                "thinking" -> if (b.optString("signature").isNotEmpty()) arr.put(b)
                "redacted_thinking" -> arr.put(b)
            }
        }
        if (arr.length() == 0 && text.isNotEmpty()) arr.put(JSONObject().put("type", "text").put("text", text.toString()))
        return arr
    }
}

/**
 * Appel HTTP direct à l'API Messages (POST /v1/messages) en streaming SSE.
 * La clé API ne transite que dans l'en-tête x-api-key : aucun journal HTTP n'est installé.
 */
class ClaudeClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun stream(
        apiKey: String,
        model: String,
        turns: List<Turn>,
        progress: StreamProgress,
        onText: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ClaudeException(tr("Aucune clé API. Ajoutez votre clé Anthropic dans les Réglages.", "No API key. Add your Anthropic key in Settings."))
        var extras = true
        while (true) {
            val outcome = execute(apiKey, buildBody(model, turns, extras), model, extras, progress, onText)
            if (outcome == Outcome.RETRY_PLAIN && extras) {
                extras = false
                continue
            }
            break
        }
    }

    private enum class Outcome { DONE, RETRY_PLAIN }

    private suspend fun execute(
        apiKey: String,
        body: String,
        model: String,
        extras: Boolean,
        progress: StreamProgress,
        onText: (String) -> Unit,
    ): Outcome {
        val builder = Request.Builder()
            .url(API_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(body.toRequestBody(JSON))
        if (extras && supportsFallback(model)) builder.header("anthropic-beta", FALLBACK_BETA)
        val call = http.newCall(builder.build())
        val handle = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    if (resp.code == 400 && extras && rejectsExtras(err)) return Outcome.RETRY_PLAIN
                    throw httpError(resp.code, err)
                }
                val source = resp.body?.source() ?: throw ClaudeException(tr("Réponse vide du service Claude.", "Empty response from the Claude service."))
                readEvents(source, progress, onText)
            }
            return Outcome.DONE
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            if (!coroutineContext.isActive) throw CancellationException()
            throw networkError(e)
        } finally {
            handle.dispose()
        }
    }

    private suspend fun readEvents(source: BufferedSource, progress: StreamProgress, onText: (String) -> Unit) {
        while (true) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty()) continue
            val ev = try {
                JSONObject(data)
            } catch (e: Exception) {
                continue
            }
            when (ev.optString("type")) {
                "message_start" -> {
                    val usage = ev.optJSONObject("message")?.optJSONObject("usage")
                    if (usage != null) {
                        progress.inputTokens = usage.optInt("input_tokens") +
                            usage.optInt("cache_creation_input_tokens") +
                            usage.optInt("cache_read_input_tokens")
                        progress.outputTokens = usage.optInt("output_tokens")
                    }
                }
                "content_block_start" -> {
                    val block = ev.optJSONObject("content_block") ?: continue
                    progress.blocks[ev.optInt("index")] = JSONObject(block.toString())
                }
                "content_block_delta" -> {
                    val idx = ev.optInt("index")
                    val delta = ev.optJSONObject("delta") ?: continue
                    val block = progress.blocks[idx]
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val t = delta.optString("text")
                            block?.put("text", block.optString("text") + t)
                            progress.text.append(t)
                            onText(t)
                        }
                        "thinking_delta" -> block?.put("thinking", block.optString("thinking") + delta.optString("thinking"))
                        "signature_delta" -> block?.put("signature", delta.optString("signature"))
                    }
                }
                "message_delta" -> {
                    ev.optJSONObject("usage")?.let { progress.outputTokens = it.optInt("output_tokens", progress.outputTokens) }
                    ev.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotEmpty() && it != "null" }
                        ?.let { progress.stopReason = it }
                }
                "error" -> {
                    val err = ev.optJSONObject("error")
                    throw apiError(err?.optString("type").orEmpty(), err?.optString("message").orEmpty(), 0)
                }
            }
        }
        if (progress.stopReason == "refusal" && progress.text.isEmpty()) {
            throw ClaudeException(tr("Claude a décliné cette demande. Reformulez ou changez de mode.", "Claude declined this request. Rephrase it or change mode."))
        }
    }

    private fun buildBody(model: String, turns: List<Turn>, extras: Boolean): String {
        val messages = JSONArray()
        for (t in turns) {
            when (t) {
                is Turn.User -> {
                    val content = JSONArray()
                    t.imagePng?.let { png ->
                        content.put(
                            JSONObject()
                                .put("type", "image")
                                .put(
                                    "source",
                                    JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", "image/png")
                                        .put("data", Base64.encodeToString(png, Base64.NO_WRAP)),
                                ),
                        )
                    }
                    content.put(JSONObject().put("type", "text").put("text", t.text))
                    messages.put(JSONObject().put("role", "user").put("content", content))
                }
                is Turn.Assistant -> messages.put(JSONObject().put("role", "assistant").put("content", t.content))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("stream", true)
            .put("messages", messages)
        if (extras) {
            body.put("output_config", JSONObject().put("effort", "medium"))
            if (supportsFallback(model)) body.put("fallbacks", "default")
        }
        return body.toString()
    }

    private fun supportsFallback(model: String) = model.startsWith("claude-sonnet-5-5") || model.startsWith("claude-opus-5-5")

    private fun rejectsExtras(err: String): Boolean {
        val l = err.lowercase()
        return "fallback" in l || "output_config" in l || "effort" in l || "anthropic-beta" in l
    }

    private fun httpError(code: Int, body: String): ClaudeException {
        val err = try {
            JSONObject(body).optJSONObject("error")
        } catch (e: Exception) {
            null
        }
        return apiError(err?.optString("type").orEmpty(), err?.optString("message").orEmpty(), code)
    }

    private fun apiError(type: String, message: String, code: Int): ClaudeException = ClaudeException(
        when {
            code == 401 || type == "authentication_error" ->
                tr("Clé API invalide ou révoquée. Vérifiez-la dans les Réglages.", "Invalid or revoked API key. Check it in Settings.")
            code == 403 || type == "permission_error" ->
                tr("Cette clé API n'a pas accès à ce modèle. Vérifiez votre compte sur console.anthropic.com.", "This API key has no access to this model. Check your account at console.anthropic.com.")
            code == 404 || type == "not_found_error" ->
                tr("Modèle introuvable. Choisissez un autre modèle dans les Réglages.", "Model not found. Pick another model in Settings.")
            code == 413 || type == "request_too_large" ->
                tr("La page est trop lourde pour être envoyée. Sélectionnez une zone plus petite.", "The page is too large to send. Select a smaller area.")
            code == 429 || type == "rate_limit_error" ->
                tr("Limite d'utilisation atteinte. Réessayez dans une minute.", "Usage limit reached. Try again in a minute.")
            code == 529 || type == "overloaded_error" ->
                tr("Le service Claude est saturé. Réessayez dans quelques instants.", "The Claude service is overloaded. Try again shortly.")
            code >= 500 || type == "api_error" ->
                tr("Le service Claude rencontre un problème (erreur $code). Réessayez plus tard.", "The Claude service has a problem (error $code). Try again later.")
            "credit" in message.lowercase() ->
                tr("Crédit API insuffisant. Rechargez votre compte sur console.anthropic.com.", "Not enough API credit. Top up your account at console.anthropic.com.")
            else -> tr("Requête refusée par l'API", "Request rejected by the API") + (if (code > 0) " ($code)" else "") + ": " + message.ifBlank { type }
        },
    )

    private fun networkError(e: IOException): ClaudeException = ClaudeException(
        when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                tr("Pas de connexion réseau. Vérifiez le Wi-Fi puis réessayez.", "No network connection. Check Wi-Fi and try again.")
            is SocketTimeoutException -> tr("Le service Claude ne répond pas (délai dépassé). Réessayez.", "The Claude service is not responding (timed out). Try again.")
            else -> tr("Connexion interrompue. Vérifiez le réseau puis réessayez.", "Connection lost. Check the network and try again.")
        },
    )

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private const val MAX_TOKENS = 16000
        private val JSON = "application/json".toMediaType()
    }
}
