package com.alphateam.carnetencre.model

import android.graphics.RectF
import com.alphateam.carnetencre.render.InkText
import java.util.UUID
import kotlin.math.hypot

/** Page logique au format 3:4 portrait, mise à l'échelle de l'écran à l'affichage. */
const val PAGE_W = 1404f
const val PAGE_H = 1872f
const val PAGE_MARGIN = 80f

fun newElementId(): String = UUID.randomUUID().toString()

sealed class PageElement {
    abstract val id: String
    abstract var z: Int
    abstract fun bounds(): RectF
}

/**
 * Trait manuscrit. Coordonnées en unités de page, pression normalisée entre 0 et 1.
 * Au stylo, l'épaisseur d'un segment varie entre 35 % et 100 % de [width] selon la pression ;
 * au surligneur ([highlighter]), elle est constante et le trait passe sous l'encre.
 */
class Stroke(
    override val id: String,
    override var z: Int,
    val width: Float,
    val xs: FloatArray,
    val ys: FloatArray,
    val ps: FloatArray,
    val color: Int = BLACK,
    val highlighter: Boolean = false,
) : PageElement() {
    companion object {
        const val BLACK = 0xFF000000.toInt()
    }

    val size: Int get() = xs.size

    /** Boîte englobante des points : gauche, haut, droite, bas. */
    val extent: FloatArray by lazy {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in xs.indices) {
            if (xs[i] < l) l = xs[i]; if (xs[i] > r) r = xs[i]
            if (ys[i] < t) t = ys[i]; if (ys[i] > b) b = ys[i]
        }
        floatArrayOf(l, t, r, b)
    }

    override fun bounds(): RectF = RectF(extent[0] - width, extent[1] - width, extent[2] + width, extent[3] + width)

    /** Vrai si le cercle (x, y, radius) touche le trait. */
    fun hit(x: Float, y: Float, radius: Float): Boolean {
        val reach = radius + width / 2f
        val e = extent
        if (x < e[0] - reach || x > e[2] + reach || y < e[1] - reach || y > e[3] + reach) return false
        if (xs.size == 1) return hypot(xs[0] - x, ys[0] - y) <= reach
        for (i in 1 until xs.size) {
            if (segmentDistance(x, y, xs[i - 1], ys[i - 1], xs[i], ys[i]) <= reach) return true
        }
        return false
    }
}

/** Élément posé par Claude, rectangulaire et déplaçable d'un bloc (texte ou figure). */
sealed class Block : PageElement() {
    abstract var x: Float
    abstract var y: Float
    abstract val w: Float
    abstract val height: Float
    override fun bounds(): RectF = RectF(x, y, x + w, y + height)
    fun contains(px: Float, py: Float): Boolean = px in x..(x + w) && py in y..(y + height)
}

/** Réponse de Claude posée sur la page : texte en encre bleue, étiqueté « CLAUDE ». */
class TextBlock(
    override val id: String,
    override var z: Int,
    override var x: Float,
    override var y: Float,
    override val w: Float,
    val text: String,
) : Block() {
    override val height: Float get() = InkText.blockHeight(text, w)
}

/**
 * Visuel dessiné par Claude (schéma, tableau à remplir, calendrier…), au format SVG,
 * affiché dans le rectangle ([x], [y], [w], [height]) de la page.
 */
class FigureBlock(
    override val id: String,
    override var z: Int,
    override var x: Float,
    override var y: Float,
    override val w: Float,
    override val height: Float,
    val svg: String,
) : Block()

fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 == 0f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/**
 * Copie transformée d'un élément : mise à l'échelle [s] autour de ([cx], [cy]) puis
 * translation ([dx], [dy]). Même identifiant et même z, pour remplacer l'original.
 */
fun PageElement.transformed(dx: Float, dy: Float, s: Float = 1f, cx: Float = 0f, cy: Float = 0f): PageElement {
    fun tx(x: Float) = cx + (x - cx) * s + dx
    fun ty(y: Float) = cy + (y - cy) * s + dy
    return when (this) {
        is Stroke -> Stroke(
            id, z, width * s,
            FloatArray(size) { tx(xs[it]) },
            FloatArray(size) { ty(ys[it]) },
            ps.copyOf(), color, highlighter,
        )
        is TextBlock -> TextBlock(id, z, tx(x), ty(y), (w * s).coerceIn(240f, PAGE_W), text)
        is FigureBlock -> {
            // Une figure garde ses proportions : seule la largeur est bornée.
            val nw = (w * s).coerceIn(160f, PAGE_W)
            FigureBlock(id, z, tx(x), ty(y), nw, height * nw / w, svg)
        }
    }
}

/** Même trait dans une autre couleur ; ce que Claude a posé garde son bleu. */
fun PageElement.recolored(argb: Int): PageElement = when (this) {
    is Stroke -> Stroke(id, z, width, xs, ys, ps, argb, highlighter)
    is Block -> this
}

/** Copie indépendante (nouvel identifiant), pour dupliquer une sélection. */
fun PageElement.duplicated(newZ: Int, dx: Float, dy: Float): PageElement = when (val t = transformed(dx, dy)) {
    is Stroke -> Stroke(newElementId(), newZ, t.width, t.xs, t.ys, t.ps, t.color, t.highlighter)
    is TextBlock -> TextBlock(newElementId(), newZ, t.x, t.y, t.w, t.text)
    is FigureBlock -> FigureBlock(newElementId(), newZ, t.x, t.y, t.w, t.height, t.svg)
}

/** Boîte englobante d'un ensemble d'éléments, ou null s'il est vide. */
fun boundsOf(elements: List<PageElement>): RectF? {
    if (elements.isEmpty()) return null
    val r = RectF(elements[0].bounds())
    for (e in elements) r.union(e.bounds())
    return r
}
