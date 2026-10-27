package com.alphateam.carnetencre.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.alphateam.carnetencre.model.PAGE_H
import com.alphateam.carnetencre.model.PAGE_W
import com.alphateam.carnetencre.model.PageElement
import com.alphateam.carnetencre.model.Stroke
import com.alphateam.carnetencre.model.FigureBlock
import com.alphateam.carnetencre.model.TextBlock
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Dessin des éléments d'une page. Le canvas reçu est déjà en unités de page. */
object PageRenderer {
    /** Opacité du surligneur : l'encre et le papier restent visibles à travers. */
    const val HIGHLIGHTER_ALPHA = 0x70

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlighterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun segmentWidth(base: Float, pressure: Float): Float = base * (0.35f + 0.65f * pressure.coerceIn(0f, 1f))

    fun highlighterColor(color: Int): Int = (color and 0x00FFFFFF) or (HIGHLIGHTER_ALPHA shl 24)

    /** Surligneur d'abord (sous l'encre), puis traits au stylo et blocs de Claude dans l'ordre. */
    @Synchronized
    fun drawElements(canvas: Canvas, elements: List<PageElement>, clip: RectF? = null) {
        for (e in elements) {
            if (e is Stroke && e.highlighter && (clip == null || RectF.intersects(clip, e.bounds()))) drawStroke(canvas, e)
        }
        for (e in elements) {
            if (clip != null && !RectF.intersects(clip, e.bounds())) continue
            when (e) {
                is Stroke -> if (!e.highlighter) drawStroke(canvas, e)
                is TextBlock -> InkText.draw(canvas, e)
                is FigureBlock -> Figures.draw(canvas, e)
            }
        }
    }

    @Synchronized
    fun drawStroke(canvas: Canvas, s: Stroke) {
        if (s.size == 0) return
        if (s.highlighter) {
            highlighterPaint.color = highlighterColor(s.color)
            highlighterPaint.strokeWidth = s.width
            val path = Path()
            path.moveTo(s.xs[0], s.ys[0])
            if (s.size == 1) path.lineTo(s.xs[0] + 0.1f, s.ys[0])
            for (i in 1 until s.size) path.lineTo(s.xs[i], s.ys[i])
            canvas.drawPath(path, highlighterPaint)
            return
        }
        if (s.size == 1) {
            dotPaint.color = s.color
            canvas.drawCircle(s.xs[0], s.ys[0], segmentWidth(s.width, s.ps[0]) / 2f, dotPaint)
            return
        }
        strokePaint.color = s.color
        for (i in 1 until s.size) {
            strokePaint.strokeWidth = segmentWidth(s.width, (s.ps[i - 1] + s.ps[i]) / 2f)
            canvas.drawLine(s.xs[i - 1], s.ys[i - 1], s.xs[i], s.ys[i], strokePaint)
        }
    }

    /** Segment d'un trait en cours (rendu incrémental pendant l'écriture en mode dégradé). */
    @Synchronized
    fun drawSegment(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) {
        strokePaint.strokeWidth = width
        strokePaint.color = color
        canvas.drawLine(x0, y0, x1, y1, strokePaint)
    }

    /**
     * Rend une région de la page (toute la page par défaut) sur fond blanc,
     * avec un côté le plus long d'environ [maxSide] pixels.
     */
    fun renderBitmap(elements: List<PageElement>, region: RectF? = null, maxSide: Int = 1400): Bitmap {
        val r = region ?: RectF(0f, 0f, PAGE_W, PAGE_H)
        val scale = min(maxSide / max(r.width(), r.height()), 3f)
        val w = (r.width() * scale).roundToInt().coerceAtLeast(1)
        val h = (r.height() * scale).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        c.translate(-r.left, -r.top)
        drawElements(c, elements, r)
        return bmp
    }

    fun renderPng(elements: List<PageElement>, region: RectF? = null, maxSide: Int = 1400): ByteArray {
        val bmp = renderBitmap(elements, region, maxSide)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
