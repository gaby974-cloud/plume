package com.alphateam.carnetencre.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ElementCodecTest {

    private fun scribble(rnd: Random, n: Int): Stroke {
        var x = rnd.nextFloat() * PAGE_W
        var y = rnd.nextFloat() * PAGE_H
        var a = rnd.nextFloat() * 6.28f
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val ps = FloatArray(n)
        for (i in 0 until n) {
            a += (rnd.nextFloat() - 0.5f) * 0.6f
            x = (x + cos(a) * 3f).coerceIn(0f, PAGE_W)
            y = (y + sin(a) * 3f).coerceIn(0f, PAGE_H)
            xs[i] = x
            ys[i] = y
            ps[i] = rnd.nextFloat()
        }
        return Stroke(newElementId(), 1, 4.2f, xs, ys, ps)
    }

    @Test
    fun roundTripKeepsPointsWithinHalfUnit() {
        val s = scribble(Random(1), 500)
        val back = ElementCodec.decodeStroke(s.id, s.z, ElementCodec.encodeStroke(s))
        assertEquals(s.size, back.size)
        assertEquals(s.width, back.width, 0.05f)
        for (i in 0 until s.size) {
            assertTrue(abs(s.xs[i] - back.xs[i]) <= 0.25f)
            assertTrue(abs(s.ys[i] - back.ys[i]) <= 0.25f)
            assertTrue(abs(s.ps[i] - back.ps[i]) <= 0.06f)
        }
    }

    @Test
    fun roundTripHandlesLargeJumps() {
        val s = Stroke("x", 3, 7f, floatArrayOf(0f, PAGE_W, 0.5f), floatArrayOf(PAGE_H, 0f, 1f), floatArrayOf(0f, 1f, 0.5f))
        val back = ElementCodec.decodeStroke(s.id, s.z, ElementCodec.encodeStroke(s))
        assertEquals(PAGE_W, back.xs[1], 0.01f)
        assertEquals(PAGE_H, back.ys[0], 0.01f)
        assertEquals(0.5f, back.xs[2], 0.01f)
    }

    @Test
    fun heavyPageStaysUnder200Kb() {
        val rnd = Random(42)
        // Page très chargée : 800 traits de 60 points, soit 48 000 points.
        val total = (0 until 800).sumOf { ElementCodec.encodeStroke(scribble(rnd, 60)).size }
        assertTrue("taille = $total octets", total < 200_000)
    }
}
