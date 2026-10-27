package com.alphateam.carnetencre.claude

import android.content.Context
import com.alphateam.carnetencre.Lang
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class Mode(val id: String, val label: String, val task: String)

/**
 * Modes et prompt de base, lus depuis assets/modes.json (français) et assets/modes-en.json
 * (anglais) ; on peut en ajouter sans toucher au code. Les deux fichiers ont les mêmes identifiants.
 */
class ModeConfig(
    private val promptTemplate: String,
    private val sourcePage: String,
    private val sourceZone: String,
    private val datePattern: String,
    val modes: List<Mode>,
) {
    fun mode(id: String): Mode = modes.firstOrNull { it.id == id } ?: modes.first()

    fun prompt(mode: Mode, zone: Boolean): String = promptTemplate
        .replace("{DATE}", LocalDate.now().format(DateTimeFormatter.ofPattern(datePattern, Lang.locale)))
        .replace("{SOURCE}", if (zone) sourceZone else sourcePage)
        .replace("{TACHE}", mode.task)

    companion object {
        fun load(context: Context, file: String = "modes.json"): ModeConfig {
            val json = context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(json)
            val sources = root.getJSONObject("sources")
            val arr = root.getJSONArray("modes")
            val modes = (0 until arr.length()).map {
                val m = arr.getJSONObject(it)
                Mode(m.getString("id"), m.getString("label"), m.getString("task"))
            }
            require(modes.isNotEmpty()) { "$file ne contient aucun mode" }
            return ModeConfig(
                root.getString("prompt"),
                sources.getString("page"),
                sources.getString("zone"),
                root.optString("datePattern", "EEEE d MMMM yyyy"),
                modes,
            )
        }
    }
}
