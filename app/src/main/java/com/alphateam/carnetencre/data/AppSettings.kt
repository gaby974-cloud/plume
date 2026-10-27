package com.alphateam.carnetencre.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.alphateam.carnetencre.Lang
import com.alphateam.carnetencre.page.OnyxPen
import com.alphateam.carnetencre.tr
import java.time.YearMonth

data class ClaudeModel(val id: String, val label: String)

data class InkColor(val label: String, val argb: Int)

/** Un stylo favori : stylo ou surligneur, couleur, et indice d'épaisseur dans la liste de son type. */
data class InkPreset(val highlighter: Boolean, val color: Int, val widthIndex: Int) {
    val width: Float
        get() {
            val widths = if (highlighter) AppSettings.HIGHLIGHTER_WIDTHS else AppSettings.PEN_WIDTHS
            return widths[widthIndex.coerceIn(0, widths.lastIndex)]
        }

    fun encode() = "${if (highlighter) "h" else "p"},$color,$widthIndex"

    companion object {
        fun decode(s: String): InkPreset? {
            val parts = s.split(',')
            if (parts.size != 3) return null
            return InkPreset(parts[0] == "h", parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
        }
    }
}

/**
 * Réglages de l'application. La clé API vit dans un fichier chiffré
 * (EncryptedSharedPreferences, clé maîtresse dans l'Android Keystore) ; elle n'est
 * jamais écrite dans les logs.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)
    private val secure: SharedPreferences? = openSecure(context)

    val secureStorageAvailable: Boolean get() = secure != null

    var apiKey: String
        get() = secure?.getString(KEY_API, "") ?: ""
        set(value) {
            secure?.edit()?.putString(KEY_API, value.trim())?.apply()
        }

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value).apply()

    /** « Stylet seul » : activé par défaut sur Boox ; sur émulateur le doigt doit pouvoir écrire. */
    var stylusOnly: Boolean
        get() = prefs.getBoolean("stylusOnly", OnyxPen.isOnyxDevice())
        set(value) = prefs.edit().putBoolean("stylusOnly", value).apply()

    /** Les trois stylos favoris de la barre d'outils. */
    var favorites: List<InkPreset>
        get() = prefs.getString("favorites", null)
            ?.split(';')
            ?.mapNotNull { InkPreset.decode(it) }
            ?.takeIf { it.size == FAVORITE_COUNT }
            ?: DEFAULT_FAVORITES
        set(value) = prefs.edit().putString("favorites", value.joinToString(";") { it.encode() }).apply()

    var selectedFavorite: Int
        get() = prefs.getInt("selectedFavorite", 0).coerceIn(0, FAVORITE_COUNT - 1)
        set(value) = prefs.edit().putInt("selectedFavorite", value).apply()

    /** Barre d'outils à gauche (par défaut, loin de la main d'un droitier) ou à droite. */
    var toolbarLeft: Boolean
        get() = prefs.getBoolean("toolbarLeft", true)
        set(value) = prefs.edit().putBoolean("toolbarLeft", value).apply()

    var toolbarCollapsed: Boolean
        get() = prefs.getBoolean("toolbarCollapsed", false)
        set(value) = prefs.edit().putBoolean("toolbarCollapsed", value).apply()

    /** Copie PDF de chaque carnet dans Documents/Plume (récupérable via push.boox.com → BooxDrop). */
    var autoPdfCopy: Boolean
        get() = prefs.getBoolean("autoPdfCopy", true)
        set(value) = prefs.edit().putBoolean("autoPdfCopy", value).apply()

    /** Langue de l'interface : [Lang.AUTO] (celle de l'appareil), [Lang.FRENCH] ou [Lang.ENGLISH]. */
    var language: String
        get() = prefs.getString("language", Lang.AUTO) ?: Lang.AUTO
        set(value) {
            prefs.edit().putString("language", value).apply()
            Lang.apply(value)
        }

    var modeId: String
        get() = prefs.getString("mode", "auto") ?: "auto"
        set(value) = prefs.edit().putString("mode", value).apply()

    fun addUsage(inputTokens: Int, outputTokens: Int) {
        val m = YearMonth.now().toString()
        prefs.edit()
            .putLong("usage_in_$m", prefs.getLong("usage_in_$m", 0) + inputTokens)
            .putLong("usage_out_$m", prefs.getLong("usage_out_$m", 0) + outputTokens)
            .apply()
    }

    /** Tokens consommés sur le mois en cours : (entrée, sortie). */
    fun monthUsage(month: YearMonth = YearMonth.now()): Pair<Long, Long> {
        val m = month.toString()
        return prefs.getLong("usage_in_$m", 0) to prefs.getLong("usage_out_$m", 0)
    }

    companion object {
        private const val KEY_API = "anthropic_api_key"
        private const val SECURE_FILE = "secrets"
        const val DEFAULT_MODEL = "claude-sonnet-5-5"

        val MODELS: List<ClaudeModel>
            get() = listOf(
                ClaudeModel("claude-sonnet-5-5", tr("Sonnet 5.5 — équilibré (défaut)", "Sonnet 5.5 — balanced (default)")),
                ClaudeModel("claude-opus-5-5", tr("Opus 5.5 — le plus fin, plus cher", "Opus 5.5 — sharpest, pricier")),
                ClaudeModel("claude-haiku-5-5", tr("Haiku 5.5 — rapide et économique", "Haiku 5.5 — fast and cheap")),
            )

        val PEN_WIDTHS = floatArrayOf(2.6f, 4.2f, 7f, 11f)
        val PEN_LABELS: List<String>
            get() = if (Lang.english) listOf("Fine", "Medium", "Thick", "Extra thick") else listOf("Fin", "Moyen", "Épais", "Très épais")
        val HIGHLIGHTER_WIDTHS = floatArrayOf(16f, 26f, 40f)
        val HIGHLIGHTER_LABELS: List<String>
            get() = if (Lang.english) listOf("Fine", "Medium", "Wide") else listOf("Fin", "Moyen", "Large")

        // Couleurs saturées : l'écran Kaleido affiche des teintes plus pâles qu'un LCD.
        // Pas de bleu proche de celui de Claude (#1B4EA3) pour ne pas confondre les deux.
        val PEN_COLORS = listOf(
            InkColor("Noir", 0xFF000000.toInt()),
            InkColor("Rouge", 0xFFD32F2F.toInt()),
            InkColor("Bleu", 0xFF0288D1.toInt()),
            InkColor("Vert", 0xFF2E7D32.toInt()),
            InkColor("Orange", 0xFFEF6C00.toInt()),
            InkColor("Violet", 0xFF7B1FA2.toInt()),
        )
        val HIGHLIGHTER_COLORS = listOf(
            InkColor("Jaune", 0xFFFFE600.toInt()),
            InkColor("Vert", 0xFF76FF03.toInt()),
            InkColor("Rose", 0xFFFF4FA3.toInt()),
            InkColor("Bleu", 0xFF40C4FF.toInt()),
            InkColor("Orange", 0xFFFF9100.toInt()),
        )

        const val FAVORITE_COUNT = 3
        val DEFAULT_FAVORITES = listOf(
            InkPreset(highlighter = false, color = PEN_COLORS[0].argb, widthIndex = 1),
            InkPreset(highlighter = false, color = PEN_COLORS[1].argb, widthIndex = 1),
            InkPreset(highlighter = true, color = HIGHLIGHTER_COLORS[0].argb, widthIndex = 1),
        )

        private fun openSecure(context: Context): SharedPreferences? {
            fun create(): SharedPreferences {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    context,
                    SECURE_FILE,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                create()
            } catch (e: Exception) {
                // Fichier illisible (clé maîtresse perdue après une restauration, par exemple) :
                // on repart d'un fichier vide plutôt que de stocker la clé en clair.
                try {
                    context.deleteSharedPreferences(SECURE_FILE)
                    create()
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}
