package com.alphateam.carnetencre.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Picture
import android.util.LruCache
import com.alphateam.carnetencre.model.FigureBlock
import com.caverock.androidsvg.SVG
import kotlin.math.roundToInt

/**
 * Rendu des visuels SVG dessinés par Claude. Chaque SVG est analysé une fois puis gardé
 * sous forme de Picture : le redessiner à chaque rafraîchissement de la page reste rapide.
 * AndroidSVG n'exécute aucun script et ne charge aucune ressource externe.
 */
object Figures {
    /** Au-delà, on refuse le SVG (réponse anormale). */
    const val MAX_SVG_CHARS = 300_000

    private class Parsed(val picture: Picture, val width: Float, val height: Float)

    private val cache = LruCache<String, Parsed>(24)
    private val missing = HashSet<String>()
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InkText.CLAUDE_BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private fun key(svg: String) = "${svg.length}:${svg.hashCode()}"

    @Synchronized
    private fun parsed(svg: String): Parsed? {
        val k = key(svg)
        cache.get(k)?.let { return it }
        if (k in missing || svg.length > MAX_SVG_CHARS) return null
        val result = try {
            val doc = SVG.getFromString(svg)
            val box = doc.documentViewBox
            val w = box?.width()?.takeIf { it > 0f } ?: doc.documentWidth.takeIf { it > 0f } ?: 1200f
            val h = box?.height()?.takeIf { it > 0f } ?: doc.documentHeight.takeIf { it > 0f } ?: 900f
            Parsed(doc.renderToPicture(w.roundToInt(), h.roundToInt()), w, h)
        } catch (e: Exception) {
            null
        }
        if (result == null) missing.add(k) else cache.put(k, result)
        return result
    }

    /** Proportions hauteur / largeur du SVG, ou null s'il est illisible. */
    fun aspect(svg: String): Float? = parsed(svg)?.let { it.height / it.width }

    @Synchronized
    fun draw(canvas: Canvas, f: FigureBlock) {
        val p = parsed(f.svg)
        if (p == null) {
            canvas.drawRect(f.x, f.y, f.x + f.w, f.y + f.height, placeholder)
            return
        }
        canvas.save()
        canvas.translate(f.x, f.y)
        canvas.scale(f.w / p.width, f.height / p.height)
        canvas.drawPicture(p.picture)
        canvas.restore()
    }

    /** Aperçu pour le panneau de réponse, à la largeur demandée en pixels. */
    fun preview(svg: String, widthPx: Int): Bitmap? {
        val p = parsed(svg) ?: return null
        val w = widthPx.coerceAtLeast(1)
        val h = (w * p.height / p.width).roundToInt().coerceIn(1, 4000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(w / p.width, h / p.height)
        synchronized(this) { c.drawPicture(p.picture) }
        return bmp
    }
}
