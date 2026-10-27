package com.alphateam.carnetencre.claude

import com.alphateam.carnetencre.tr

/** Morceau d'une réponse de Claude : du texte, ou un visuel SVG (schéma, tableau, calendrier…). */
sealed class ReplyPart {
    data class Text(val text: String) : ReplyPart()
    data class Svg(val svg: String) : ReplyPart()
}

/** Découpe d'une réponse brute en texte et visuels SVG. */
object ReplyParts {
    private val SVG_BLOCK = Regex("<svg[\\s\\S]*?</svg\\s*>", RegexOption.IGNORE_CASE)
    private val SVG_START = Regex("<svg", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("^\\s*```[a-zA-Z]*\\s*$", RegexOption.MULTILINE)

    fun split(raw: String): List<ReplyPart> {
        val parts = mutableListOf<ReplyPart>()
        var last = 0
        for (m in SVG_BLOCK.findAll(raw)) {
            addText(parts, raw.substring(last, m.range.first))
            parts += ReplyPart.Svg(m.value)
            last = m.range.last + 1
        }
        addText(parts, raw.substring(last))
        return parts
    }

    /** Le texte seul, sans les visuels (pour Copier). */
    fun textOnly(raw: String): String =
        split(raw).filterIsInstance<ReplyPart.Text>().joinToString("\n\n") { it.text }

    /**
     * Texte affichable pendant le streaming : un visuel terminé ou en cours d'écriture
     * n'apparaît pas en code, mais comme une mention.
     */
    fun forDisplay(live: String): String {
        val done = SVG_BLOCK.replace(live, tr("[dessin]", "[drawing]"))
        val open = SVG_START.find(done) ?: return clean(done)
        return clean(done.substring(0, open.range.first)) + "\n" + tr("(dessin en cours…)", "(drawing in progress…)")
    }

    private fun addText(parts: MutableList<ReplyPart>, s: String) {
        val t = clean(s)
        if (t.isNotBlank()) parts += ReplyPart.Text(t)
    }

    /** Retire les clôtures de code Markdown que Claude aurait laissées autour d'un SVG. */
    private fun clean(s: String) = FENCE.replace(s, "").trim()
}
