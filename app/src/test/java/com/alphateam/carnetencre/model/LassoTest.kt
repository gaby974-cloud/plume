package com.alphateam.carnetencre.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LassoTest {
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 20) = Stroke(
        newElementId(), 1, 4f,
        FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) },
        FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) },
        FloatArray(n) { 0.5f },
    )

    // Boucle carrée de (100,100) à (400,400).
    private val lx = floatArrayOf(100f, 400f, 400f, 100f)
    private val ly = floatArrayOf(100f, 100f, 400f, 400f)

    @Test
    fun pointInPolygon() {
        assertTrue(Lasso.contains(lx, ly, 250f, 250f))
        assertFalse(Lasso.contains(lx, ly, 50f, 250f))
        assertFalse(Lasso.contains(lx, ly, 250f, 450f))
    }

    @Test
    fun selectsOnlyStrokesMostlyInside() {
        val inside = line(150f, 200f, 350f, 220f)
        val outside = line(500f, 200f, 700f, 220f)
        val straddling = line(300f, 300f, 700f, 300f) // un quart dedans
        val picked = Lasso.select(lx, ly, listOf(inside, outside, straddling))
        assertEquals(listOf<PageElement>(inside), picked)
    }

    @Test
    fun denseLoopOnBusyPageStaysFast() {
        // Page chargée : 400 traits de 400 points ; boucle de 6 000 points (stylet rapide, grand tour).
        val strokes = List(400) { i -> line(50f + (i % 20) * 60f, 50f + (i / 20) * 90f, 90f + (i % 20) * 60f, 80f + (i / 20) * 90f, n = 400) }
        val n = 6000
        val cx = FloatArray(n) { 700f + 500f * kotlin.math.cos(2 * Math.PI * it / n).toFloat() }
        val cy = FloatArray(n) { 900f + 500f * kotlin.math.sin(2 * Math.PI * it / n).toFloat() }
        Lasso.select(cx, cy, strokes) // échauffement
        val t0 = System.nanoTime()
        val picked = Lasso.select(cx, cy, strokes)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("sélection trop lente : $ms ms", ms < 150)
        assertTrue(picked.isNotEmpty() && picked.size < strokes.size)
    }

    @Test
    fun transformedMovesAndScales() {
        val s = line(100f, 100f, 200f, 100f, n = 2)
        val t = s.transformed(dx = 10f, dy = 20f, s = 2f, cx = 100f, cy = 100f) as Stroke
        assertEquals(110f, t.xs[0], 0.001f)
        assertEquals(310f, t.xs[1], 0.001f)
        assertEquals(120f, t.ys[1], 0.001f)
        assertEquals(8f, t.width, 0.001f)
        assertEquals(s.id, t.id)
    }
}
