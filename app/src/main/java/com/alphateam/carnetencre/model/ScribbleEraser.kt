package com.alphateam.carnetencre.model

import kotlin.math.hypot
import kotlin.math.max

/**
 * Effacement par rature : un trait au stylo qui fait des allers-retours serrés (zigzag ou
 * gribouillis) par-dessus de l'encre existante efface cette encre, et n'est pas conservé.
 *
 * Un zigzag tracé sur une zone vide reste un trait normal (hachures, dessin).
 */
object ScribbleEraser {
    private const val MIN_POINTS = 10
    private const val MIN_SIZE = 18f
    private const val MIN_REVERSALS = 3
    private const val MIN_LENGTH_RATIO = 2.2f
    private const val REVERSAL_FRACTION = 0.3f
    private const val COVERAGE = 0.5f
    private const val MARGIN = 12f

    /** Éléments à effacer si [scribble] est une rature, sinon null. */
    fun victims(scribble: Stroke, elements: List<PageElement>): List<PageElement>? {
        if (scribble.highlighter || !looksLikeScribble(scribble)) return null
        val box = box(scribble)
        val zone = Box(box.l - MARGIN, box.t - MARGIN, box.r + MARGIN, box.b + MARGIN)
        val reach = scribble.width / 2f + 4f
        val hits = elements.filter { el ->
            el is Stroke && el !== scribble &&
                coveredFraction(el, zone) >= COVERAGE && touches(scribble, el, reach)
        }
        return hits.ifEmpty { null }
    }

    private class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun contains(x: Float, y: Float) = x in l..r && y in t..b
    }

    private fun box(s: Stroke): Box {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until s.size) {
            l = minOf(l, s.xs[i]); r = maxOf(r, s.xs[i])
            t = minOf(t, s.ys[i]); b = maxOf(b, s.ys[i])
        }
        return Box(l, t, r, b)
    }

    fun looksLikeScribble(s: Stroke): Boolean {
        if (s.size < MIN_POINTS) return false
        val b = box(s)
        val w = b.r - b.l
        val h = b.b - b.t
        if (max(w, h) < MIN_SIZE) return false
        var length = 0f
        for (i in 1 until s.size) length += hypot(s.xs[i] - s.xs[i - 1], s.ys[i] - s.ys[i - 1])
        if (length < MIN_LENGTH_RATIO * (w + h)) return false
        val reversals = max(reversals(s.xs, w * REVERSAL_FRACTION), reversals(s.ys, h * REVERSAL_FRACTION))
        return reversals >= MIN_REVERSALS
    }

    /** Nombre de demi-tours le long d'un axe, en ignorant les variations plus petites que [threshold]. */
    private fun reversals(v: FloatArray, threshold: Float): Int {
        if (threshold <= 0f) return 0
        var count = 0
        var dir = 0
        var extreme = v[0]
        for (x in v) {
            when (dir) {
                0 -> if (x - extreme > threshold) {
                    dir = 1
                    extreme = x
                } else if (extreme - x > threshold) {
                    dir = -1
                    extreme = x
                }
                1 -> if (x > extreme) {
                    extreme = x
                } else if (extreme - x > threshold) {
                    count++
                    dir = -1
                    extreme = x
                }
                else -> if (x < extreme) {
                    extreme = x
                } else if (x - extreme > threshold) {
                    count++
                    dir = 1
                    extreme = x
                }
            }
        }
        return count
    }

    private fun coveredFraction(s: Stroke, zone: Box): Float {
        var inside = 0
        for (i in 0 until s.size) if (zone.contains(s.xs[i], s.ys[i])) inside++
        return inside.toFloat() / s.size
    }

    private fun touches(scribble: Stroke, target: Stroke, reach: Float): Boolean {
        for (i in 0 until scribble.size) {
            if (target.hit(scribble.xs[i], scribble.ys[i], reach)) return true
        }
        // Le zigzag peut passer entre deux points échantillonnés : on teste aussi les segments.
        for (j in 0 until target.size) {
            for (i in 1 until scribble.size) {
                val d = segmentDistance(
                    target.xs[j], target.ys[j],
                    scribble.xs[i - 1], scribble.ys[i - 1], scribble.xs[i], scribble.ys[i],
                )
                if (d <= reach + target.width / 2f) return true
            }
        }
        return false
    }
}
