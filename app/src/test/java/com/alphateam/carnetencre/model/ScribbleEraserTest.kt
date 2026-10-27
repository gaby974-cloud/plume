package com.alphateam.carnetencre.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ScribbleEraserTest {

    private fun stroke(pts: List<Pair<Float, Float>>, highlighter: Boolean = false) = Stroke(
        newElementId(), 1, 4.2f,
        FloatArray(pts.size) { pts[it].first },
        FloatArray(pts.size) { pts[it].second },
        FloatArray(pts.size) { 0.6f },
        highlighter = highlighter,
    )

    /** Un « mot » manuscrit : une vague horizontale de [w] unités à partir de (x, y). */
    private fun word(x: Float, y: Float, w: Float = 200f): Stroke =
        stroke((0..80).map { i -> val t = i / 80f; (x + t * w) to (y + 18f * sin(t * 8 * PI).toFloat()) })

    /** Rature : allers-retours horizontaux qui descendent légèrement. */
    private fun zigzag(x: Float, y: Float, w: Float = 210f, passes: Int = 6): Stroke {
        val pts = mutableListOf<Pair<Float, Float>>()
        for (p in 0 until passes) {
            for (i in 0..15) {
                val t = i / 15f
                val px = if (p % 2 == 0) x + t * w else x + w - t * w
                pts += px to (y - 15f + p * 6f + t * 6f)
            }
        }
        return stroke(pts)
    }

    @Test
    fun zigzagOverWordErasesIt() {
        val target = word(100f, 300f)
        val other = word(500f, 300f)
        val victims = ScribbleEraser.victims(zigzag(95f, 300f), listOf(target, other))
        assertNotNull(victims)
        assertEquals(listOf<PageElement>(target), victims)
    }

    @Test
    fun zigzagOnBlankPageIsKeptAsStroke() {
        assertTrue(ScribbleEraser.looksLikeScribble(zigzag(95f, 300f)))
        assertNull(ScribbleEraser.victims(zigzag(95f, 900f), listOf(word(100f, 300f))))
    }

    @Test
    fun ordinaryHandwritingIsNotAScribble() {
        assertFalse(ScribbleEraser.looksLikeScribble(word(100f, 300f)))
        val underline = stroke((0..30).map { (100f + it * 7f) to 330f })
        assertFalse(ScribbleEraser.looksLikeScribble(underline))
    }

    @Test
    fun highlighterNeverErases() {
        val z = zigzag(95f, 300f)
        val hl = Stroke(z.id, 1, 26f, z.xs, z.ys, z.ps, highlighter = true)
        assertNull(ScribbleEraser.victims(hl, listOf(word(100f, 300f))))
    }
}
