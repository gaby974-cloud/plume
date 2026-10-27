package com.alphateam.carnetencre.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyPartsTest {
    private val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1200 400\"><rect width=\"10\" height=\"10\"/></svg>"

    @Test
    fun splitsTextAndSvgInOrder() {
        val parts = ReplyParts.split("Voici le planning.\n$svg\nÀ compléter au stylet.")
        assertEquals(3, parts.size)
        assertEquals(ReplyPart.Text("Voici le planning."), parts[0])
        assertEquals(ReplyPart.Svg(svg), parts[1])
        assertEquals(ReplyPart.Text("À compléter au stylet."), parts[2])
    }

    @Test
    fun removesMarkdownFencesAroundSvg() {
        val parts = ReplyParts.split("```svg\n$svg\n```")
        assertEquals(listOf<ReplyPart>(ReplyPart.Svg(svg)), parts)
    }

    @Test
    fun plainTextStaysSinglePart() {
        assertEquals(listOf<ReplyPart>(ReplyPart.Text("- un\n- deux")), ReplyParts.split("- un\n- deux"))
    }

    @Test
    fun liveDisplayHidesSvgCode() {
        val shown = ReplyParts.forDisplay("Calendrier :\n<svg xmlns=\"http://www.w3.org/2000/svg\"><rect")
        assertTrue(shown.startsWith("Calendrier :"))
        assertTrue(shown.endsWith("(dessin en cours…)"))
        assertTrue("<svg" !in shown)
        assertEquals("Avant\n[dessin]\nAprès", ReplyParts.forDisplay("Avant\n$svg\nAprès"))
    }

    @Test
    fun textOnlyDropsFigures() {
        assertEquals("A\n\nB", ReplyParts.textOnly("A\n$svg\nB"))
    }
}
