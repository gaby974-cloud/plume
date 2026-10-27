package com.alphateam.carnetencre

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Langue de l'interface : français ou anglais. Par défaut celle de l'appareil (français si
 * la liseuse est en français, anglais sinon) ; modifiable dans les Réglages.
 * Lue par Compose : changer de langue redessine l'écran aussitôt.
 */
object Lang {
    const val AUTO = "auto"
    const val FRENCH = "fr"
    const val ENGLISH = "en"

    var english by mutableStateOf(false)
        private set

    val locale: Locale get() = if (english) Locale.ENGLISH else Locale.FRENCH

    fun apply(choice: String) {
        english = when (choice) {
            ENGLISH -> true
            FRENCH -> false
            else -> Locale.getDefault().language != "fr"
        }
    }
}

/** Texte de l'interface dans la langue courante : la version française, puis l'anglaise. */
fun tr(fr: String, en: String): String = if (Lang.english) en else fr
