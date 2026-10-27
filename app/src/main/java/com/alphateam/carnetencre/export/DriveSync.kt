package com.alphateam.carnetencre.export

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.alphateam.carnetencre.BuildConfig
import com.alphateam.carnetencre.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** Erreur Google Drive, déjà formulée pour l'utilisateur. */
class DriveException(message: String) : Exception(message)

/**
 * Envoi des copies PDF des carnets dans un dossier « Plume » de Google Drive.
 *
 * Connexion : OAuth 2.0 pour application de bureau, par le navigateur de la liseuse et
 * une redirection vers 127.0.0.1 (PKCE). Fonctionne sans les services Google Play.
 * Portée drive.file : Plume ne voit et ne modifie que les fichiers qu'il a créés.
 * Le jeton de rafraîchissement est chiffré (Keystore) et n'est jamais écrit dans les logs.
 */
object DriveSync {
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    private const val SCOPE = "openid email https://www.googleapis.com/auth/drive.file"
    private const val FOLDER_NAME = "Plume"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000

    /** Lien « Revenir à Plume » de la page affichée après la connexion (voir le manifeste). */
    const val RETURN_URI = "plume://retour"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var accessExpiry = 0L

    /** Vrai si l'identifiant OAuth a été fourni à la compilation (local.properties). */
    val isConfigured: Boolean get() = BuildConfig.DRIVE_CLIENT_ID.isNotBlank()

    private fun prefs(context: Context) = context.getSharedPreferences("drive", Context.MODE_PRIVATE)

    @Volatile
    private var secureCache: SharedPreferences? = null

    private fun secure(context: Context): SharedPreferences? = secureCache ?: try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "drive_secrets", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { secureCache = it }
    } catch (e: Exception) {
        null
    }

    fun isConnected(context: Context): Boolean = isConfigured && secure(context)?.getString("refresh", null) != null

    fun email(context: Context): String? = prefs(context).getString("email", null)

    /** Dernier envoi (date et résultat), pour les Réglages. */
    fun lastStatus(context: Context): String? = prefs(context).getString("status", null)

    private fun setStatus(context: Context, ok: Boolean, detail: String? = null) {
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        val text = if (ok) tr("Dernier envoi : ", "Last upload: ") + date else tr("Dernier envoi échoué", "Last upload failed") + " ($date)" + (detail?.let { ": $it" } ?: "")
        prefs(context).edit().putString("status", text).apply()
    }

    // ------------------------------------------------------------ connexion

    /** Avancement de la connexion, affiché par les Réglages. */
    sealed interface Login {
        data object Idle : Login
        data object Waiting : Login
        data class Done(val email: String) : Login
        data class Failed(val message: String) : Login
    }

    private val loginState = MutableStateFlow<Login>(Login.Idle)
    val login: StateFlow<Login> = loginState

    // Chaque tentative a son numéro : une tentative abandonnée ne peut plus changer l'état.
    @Volatile
    private var loginId = 0

    @Volatile
    private var loginServer: ServerSocket? = null

    /**
     * Ouvre la page de connexion Google dans le navigateur et attend la réponse (5 min au plus).
     * À lancer dans la portée de l'application : la connexion doit survivre à la sortie des
     * Réglages pendant que le navigateur est au premier plan.
     */
    fun startLogin(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        val id = newAttempt()
        loginState.value = Login.Waiting
        scope.launch {
            val result = try {
                Login.Done(connect(app))
            } catch (e: DriveException) {
                Login.Failed(e.message ?: tr("Connexion impossible.", "Could not connect."))
            } catch (e: Exception) {
                Login.Failed(tr("Connexion impossible : vérifiez le Wi-Fi puis réessayez.", "Could not connect: check Wi-Fi and try again."))
            }
            if (id == loginId) loginState.value = result
        }
    }

    /**
     * Secours : termine la connexion avec l'adresse http://127.0.0.1… que le navigateur affiche
     * après la connexion, si Plume n'a pas reçu la réponse lui-même.
     */
    suspend fun completeFromAddress(context: Context, address: String): Login = withContext(Dispatchers.IO) {
        val id = newAttempt()
        val result = try {
            val text = address.trim()
            val start = text.indexOf("127.0.0.1")
            if (start < 0 || "code=" !in text && "error=" !in text) {
                throw DriveException(tr("Collez l'adresse complète de la page, qui commence par http://127.0.0.1 et contient « code= ».", "Paste the full page address, which starts with http://127.0.0.1 and contains \"code=\"."))
            }
            val pending = secure(context)?.getString("pending_state", null)
            if (pending == null && isConnected(context)) {
                Login.Done(email(context) ?: tr("compte Google", "Google account"))
            } else {
                Login.Done(finish(context, Uri.parse("http://" + text.substring(start))))
            }
        } catch (e: DriveException) {
            Login.Failed(e.message ?: tr("Connexion impossible.", "Could not connect."))
        } catch (e: Exception) {
            Login.Failed(tr("Connexion impossible : vérifiez le Wi-Fi puis réessayez.", "Could not connect: check Wi-Fi and try again."))
        }
        if (id == loginId) loginState.value = result
        result
    }

    /** Oublie le dernier message de connexion (affiché une seule fois). */
    fun clearLoginMessage() {
        if (loginState.value !is Login.Waiting) loginState.value = Login.Idle
    }

    private fun newAttempt(): Int {
        loginId++
        // Libère le port de la tentative précédente : son attente s'arrête aussitôt.
        try {
            loginServer?.close()
        } catch (_: Exception) {
        }
        loginServer = null
        return loginId
    }

    private suspend fun connect(context: Context): String = withContext(Dispatchers.IO) {
        if (!isConfigured) throw DriveException(tr("Identifiant Google manquant : voir la configuration dans le README.", "Google client ID missing: see setup in the README."))
        val store = secure(context) ?: throw DriveException(tr("Stockage chiffré indisponible sur cet appareil.", "Encrypted storage is not available on this device."))
        val verifier = randomToken(48)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomToken(16)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        loginServer = server
        server.use {
            server.soTimeout = LOGIN_TIMEOUT_MS
            val redirect = "http://127.0.0.1:${server.localPort}"
            // Gardés pour finir la connexion avec l'adresse collée si Plume a été interrompu.
            store.edit()
                .putString("pending_state", state)
                .putString("pending_verifier", verifier)
                .putString("pending_redirect", redirect)
                .commit()
            val url = Uri.parse(AUTH_URL).buildUpon()
                .appendQueryParameter("client_id", BuildConfig.DRIVE_CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .build()
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val reply = try {
                waitForRedirect(server)
            } catch (e: SocketTimeoutException) {
                throw DriveException(tr("Aucune réponse de Google en 5 minutes. Collez l'adresse de la page du navigateur ci-dessous, ou réessayez.", "No answer from Google within 5 minutes. Paste the browser page address below, or try again."))
            } catch (e: SocketException) {
                throw DriveException(tr("Connexion interrompue. Réessayez.", "Sign-in interrupted. Try again."))
            }
            finish(context, reply)
        }
    }

    /** Attend la redirection du navigateur vers 127.0.0.1 et lui répond par une page simple. */
    private fun waitForRedirect(server: ServerSocket): Uri {
        while (true) {
            server.accept().use { socket ->
                val line = socket.getInputStream().bufferedReader().readLine() ?: return@use
                val path = line.split(" ").getOrNull(1) ?: return@use
                val uri = Uri.parse("http://127.0.0.1$path")
                val done = uri.getQueryParameter("code") != null || uri.getQueryParameter("error") != null
                val body = if (done) {
                    "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"></head>" +
                        "<body style=\"font-family:sans-serif;padding:40px;font-size:26px\">" +
                        "<h1>Plume</h1><p>" + tr("C'est fait.", "All done.") + "</p>" +
                        "<p><a href=\"$RETURN_URI\" style=\"display:inline-block;padding:18px 28px;border:3px solid #000;" +
                        "color:#000;text-decoration:none;font-weight:bold\">" + tr("Revenir à Plume", "Back to Plume") + "</a></p></body></html>"
                } else {
                    ""
                }
                val bytes = body.toByteArray()
                val head = (if (done) "HTTP/1.1 200 OK" else "HTTP/1.1 404 Not Found") +
                    "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray())
                    write(bytes)
                    flush()
                }
                if (done) return uri
            }
        }
    }

    /** Termine la connexion avec la réponse de Google (redirection reçue ou adresse collée). */
    @Synchronized
    private fun finish(context: Context, reply: Uri): String {
        val store = secure(context) ?: throw DriveException(tr("Stockage chiffré indisponible sur cet appareil.", "Encrypted storage is not available on this device."))
        val state = store.getString("pending_state", null)
            ?: throw DriveException(tr("Aucune connexion en cours : touchez d'abord « Connecter Google Drive ».", "No sign-in in progress: tap \"Connect Google Drive\" first."))
        if (reply.getQueryParameter("state") != state) {
            throw DriveException(tr("Cette adresse ne correspond pas à la dernière connexion. Touchez « Connecter Google Drive » et recommencez.", "This address doesn't match the last sign-in. Tap \"Connect Google Drive\" and start again."))
        }
        reply.getQueryParameter("error")?.let { throw DriveException(tr("Connexion refusée par Google", "Sign-in refused by Google") + " ($it).") }
        val code = reply.getQueryParameter("code") ?: throw DriveException(tr("Réponse de Google incomplète. Réessayez.", "Incomplete answer from Google. Try again."))
        val email = exchangeCode(
            context,
            code,
            store.getString("pending_verifier", null).orEmpty(),
            store.getString("pending_redirect", null).orEmpty(),
        )
        store.edit().remove("pending_state").remove("pending_verifier").remove("pending_redirect").commit()
        return email
    }

    private fun exchangeCode(context: Context, code: String, verifier: String, redirect: String): String {
        val form = FormBody.Builder()
            .add("code", code)
            .add("client_id", BuildConfig.DRIVE_CLIENT_ID)
            .add("client_secret", BuildConfig.DRIVE_CLIENT_SECRET)
            .add("redirect_uri", redirect)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .build()
        val json = http.newCall(Request.Builder().url(TOKEN_URL).post(form).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val obj = try {
                JSONObject(body)
            } catch (e: Exception) {
                JSONObject()
            }
            if (!resp.isSuccessful) {
                val why = obj.optString("error").ifEmpty { "erreur ${resp.code}" }
                throw DriveException(tr("Google a refusé la connexion", "Google refused the sign-in") + " ($why). " + tr("Touchez « Connecter Google Drive » et recommencez.", "Tap \"Connect Google Drive\" and start again."))
            }
            obj
        }
        val refresh = json.optString("refresh_token").ifEmpty {
            throw DriveException(tr("Google n'a pas fourni d'accès durable. Réessayez la connexion.", "Google did not grant lasting access. Try signing in again."))
        }
        val store = secure(context) ?: throw DriveException(tr("Stockage chiffré indisponible sur cet appareil.", "Encrypted storage is not available on this device."))
        if (!store.edit().putString("refresh", refresh).commit()) {
            throw DriveException(tr("Impossible d'enregistrer l'accès Drive sur la liseuse.", "Could not save Drive access on the tablet."))
        }
        accessToken = json.optString("access_token")
        accessExpiry = System.currentTimeMillis() + json.optLong("expires_in", 3000) * 1000 - 60_000
        val email = emailFromIdToken(json.optString("id_token")) ?: tr("compte Google", "Google account")
        prefs(context).edit().putString("email", email).remove("status").commit()
        return email
    }

    /** Déconnecte Drive et révoque l'accès chez Google. Les copies déjà envoyées restent dans Drive. */
    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        val refresh = secure(context)?.getString("refresh", null)
        if (refresh != null) {
            try {
                http.newCall(
                    Request.Builder().url(REVOKE_URL).post(FormBody.Builder().add("token", refresh).build()).build(),
                ).execute().close()
            } catch (_: Exception) {
            }
        }
        secure(context)?.edit()?.clear()?.apply()
        prefs(context).edit().remove("email").remove("status").apply()
        accessToken = null
        loginState.value = Login.Idle
    }

    @Synchronized
    private fun token(context: Context): String? {
        accessToken?.takeIf { System.currentTimeMillis() < accessExpiry }?.let { return it }
        val refresh = secure(context)?.getString("refresh", null) ?: return null
        val form = FormBody.Builder()
            .add("client_id", BuildConfig.DRIVE_CLIENT_ID)
            .add("client_secret", BuildConfig.DRIVE_CLIENT_SECRET)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val call = http.newCall(Request.Builder().url(TOKEN_URL).post(form).build())
        call.execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 400 && "invalid_grant" in body) {
                // Accès révoqué dans le compte Google : il faut se reconnecter.
                secure(context)?.edit()?.remove("refresh")?.apply()
                setStatus(context, false, tr("reconnectez Google Drive dans les Réglages", "reconnect Google Drive in Settings"))
                return null
            }
            if (!resp.isSuccessful) return null
            val json = JSONObject(body)
            accessToken = json.optString("access_token")
            accessExpiry = System.currentTimeMillis() + json.optLong("expires_in", 3000) * 1000 - 60_000
            return accessToken
        }
    }

    // ------------------------------------------------------------ fichiers

    /** Envoie (ou remplace) la copie PDF du carnet dans le dossier Drive « Plume ». */
    suspend fun upload(context: Context, notebookId: Long, name: String, pdf: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = token(context) ?: return@withContext false
            val p = prefs(context)
            val key = "file_$notebookId"
            p.getString(key, null)?.let { fileId ->
                val code = sendFile(token, "$UPLOAD_URL/$fileId?uploadType=multipart&fields=id", "PATCH", JSONObject().put("name", name), pdf)
                if (code in 200..299) {
                    setStatus(context, true)
                    return@withContext true
                }
                if (code != 404) {
                    setStatus(context, false, tr("erreur", "error") + " $code")
                    return@withContext false
                }
                p.edit().remove(key).apply()
            }
            for (attempt in 0..1) {
                val folder = folderId(context, token) ?: break
                val meta = JSONObject()
                    .put("name", name)
                    .put("mimeType", "application/pdf")
                    .put("parents", JSONArray().put(folder))
                val request = Request.Builder()
                    .url("$UPLOAD_URL?uploadType=multipart&fields=id")
                    .header("Authorization", "Bearer $token")
                    .post(multipart(meta, pdf))
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val id = JSONObject(resp.body?.string().orEmpty()).optString("id")
                        p.edit().putString(key, id).apply()
                        setStatus(context, true)
                        return@withContext true
                    }
                    // Dossier « Plume » supprimé dans Drive : on le recrée une fois.
                    if (resp.code == 404) p.edit().remove("folder").apply() else {
                        setStatus(context, false, tr("erreur", "error") + " ${resp.code}")
                        return@withContext false
                    }
                }
            }
            setStatus(context, false)
            false
        } catch (e: Exception) {
            setStatus(context, false, tr("pas de connexion", "no connection"))
            false
        }
    }

    /** Met la copie Drive d'un carnet supprimé à la corbeille de Drive. */
    suspend fun trash(context: Context, notebookId: Long) = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val fileId = p.getString("file_$notebookId", null) ?: return@withContext
        try {
            val token = token(context) ?: return@withContext
            val body = JSONObject().put("trashed", true).toString().toRequestBody(JSON)
            http.newCall(
                Request.Builder().url("$FILES_URL/$fileId").header("Authorization", "Bearer $token").patch(body).build(),
            ).execute().close()
        } catch (_: Exception) {
        }
        p.edit().remove("file_$notebookId").apply()
    }

    /** Dossier « Plume » à la racine du Drive : retrouvé (créé par Plume) ou créé. */
    private fun folderId(context: Context, token: String): String? {
        val p = prefs(context)
        p.getString("folder", null)?.let { return it }
        val q = "name='$FOLDER_NAME' and mimeType='$FOLDER_MIME' and trashed=false"
        val search = Uri.parse(FILES_URL).buildUpon()
            .appendQueryParameter("q", q)
            .appendQueryParameter("fields", "files(id)")
            .build().toString()
        val found = get(Request.Builder().url(search).header("Authorization", "Bearer $token").build())
            ?.optJSONArray("files")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
        val id = found ?: post(
            Request.Builder().url("$FILES_URL?fields=id").header("Authorization", "Bearer $token")
                .post(JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME).toString().toRequestBody(JSON))
                .build(),
        )?.optString("id")?.takeIf { it.isNotEmpty() } ?: return null
        p.edit().putString("folder", id).apply()
        return id
    }

    private fun sendFile(token: String, url: String, method: String, meta: JSONObject, pdf: File): Int {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, multipart(meta, pdf)).build()
        return http.newCall(request).execute().use { it.code }
    }

    private fun multipart(meta: JSONObject, pdf: File) = MultipartBody.Builder()
        .setType("multipart/related".toMediaType())
        .addPart(meta.toString().toRequestBody(JSON))
        .addPart(pdf.asRequestBody("application/pdf".toMediaType()))
        .build()

    private fun get(request: Request): JSONObject? = http.newCall(request).execute().use { resp ->
        if (resp.isSuccessful) JSONObject(resp.body?.string().orEmpty()) else null
    }

    private fun post(request: Request): JSONObject? = get(request)

    private fun emailFromIdToken(idToken: String): String? = try {
        val payload = idToken.split(".")[1]
        JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).optString("email")
            .takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun randomToken(bytes: Int): String = base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun base64Url(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private val JSON = "application/json; charset=UTF-8".toMediaType()
}
