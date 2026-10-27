package com.alphateam.carnetencre.model

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Sélection au lasso : ce qui est entouré par une boucle tracée librement. */
object Lasso {
    /** Part des points d'un trait qui doivent être dans la boucle pour qu'il soit pris. */
    private const val STROKE_COVERAGE = 0.5f

    /** Écart minimal entre deux points gardés de la boucle (unités de page). */
    private const val LOOP_STEP = 6f

    /** Points d'un trait examinés au plus : assez pour juger, sans tout parcourir. */
    private const val STROKE_SAMPLES = 24

    /**
     * Éléments entourés. Rapide même sur une page chargée : boucle allégée, traits hors du
     * cadre de la boucle écartés d'emblée, et seulement un échantillon des points de chaque trait.
     */
    fun select(loopX: FloatArray, loopY: FloatArray, elements: List<PageElement>): List<PageElement> {
        val (xs, ys) = simplify(loopX, loopY)
        if (xs.size < 3) return emptyList()
        var left = xs[0]
        var top = ys[0]
        var right = xs[0]
        var bottom = ys[0]
        for (i in xs.indices) {
            left = min(left, xs[i]); right = max(right, xs[i])
            top = min(top, ys[i]); bottom = max(bottom, ys[i])
        }
        return elements.filter { el ->
            when (el) {
                is Stroke -> {
                    val e = el.extent
                    if (e[2] < left || e[0] > right || e[3] < top || e[1] > bottom) {
                        false
                    } else {
                        val step = max(1, el.size / STROKE_SAMPLES)
                        var seen = 0
                        var inside = 0
                        var i = 0
                        while (i < el.size) {
                            seen++
                            if (contains(xs, ys, el.xs[i], el.ys[i])) inside++
                            i += step
                        }
                        inside >= seen * STROKE_COVERAGE
                    }
                }
                // Un bloc ou une figure de Claude est pris si son centre est dans la boucle.
                is Block -> contains(xs, ys, el.x + el.w / 2f, el.y + el.height / 2f)
            }
        }
    }

    /** Garde un point de la boucle tous les [LOOP_STEP] au moins. */
    fun simplify(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> {
        if (xs.isEmpty()) return xs to ys
        val outX = ArrayList<Float>()
        val outY = ArrayList<Float>()
        outX += xs[0]
        outY += ys[0]
        for (i in 1 until xs.size) {
            if (hypot(xs[i] - outX.last(), ys[i] - outY.last()) >= LOOP_STEP) {
                outX += xs[i]
                outY += ys[i]
            }
        }
        return outX.toFloatArray() to outY.toFloatArray()
    }

    /** Point dans un polygone (règle pair-impair) ; la boucle est fermée implicitement. */
    fun contains(xs: FloatArray, ys: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > y) != (ys[j] > y) &&
                x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
