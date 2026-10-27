package com.alphateam.carnetencre.page

import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Enveloppe du SDK stylet Onyx (TouchHelper + EpdController).
 *
 * Le SDK dessine lui-même le trait à l'écran avec une latence minimale (« raw drawing »),
 * puis nous transmet la liste des points à la levée du stylet. Toute erreur du SDK est
 * absorbée : la page retombe alors sur le mode dégradé (événements tactiles Android).
 */
class OnyxPen(private val host: View, private val sink: Sink) {

    /** Points en coordonnées de la vue, pression normalisée entre 0 et 1. */
    interface Sink {
        fun onPenStroke(xs: FloatArray, ys: FloatArray, ps: FloatArray)
        fun onPenErase(xs: FloatArray, ys: FloatArray)
    }

    private var helper: TouchHelper? = null
    private val maxPressure: Float = try {
        EpdController.getMaxTouchPressure().takeIf { it > 0f } ?: 4096f
    } catch (t: Throwable) {
        4096f
    }

    var isOpen = false
        private set

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint?) {}
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint?) {}
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint?) {}

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            val pts = list?.points ?: return
            if (pts.isEmpty()) return
            val n = pts.size
            val xs = FloatArray(n) { pts[it].x }
            val ys = FloatArray(n) { pts[it].y }
            val ps = FloatArray(n) { (pts[it].pressure / maxPressure).coerceIn(0f, 1f) }
            host.post { sink.onPenStroke(xs, ys, ps) }
        }

        override fun onBeginRawErasing(b: Boolean, p: TouchPoint?) {}
        override fun onEndRawErasing(b: Boolean, p: TouchPoint?) {}
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint?) {}

        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {
            val pts = list?.points ?: return
            if (pts.isEmpty()) return
            val xs = FloatArray(pts.size) { pts[it].x }
            val ys = FloatArray(pts.size) { pts[it].y }
            host.post { sink.onPenErase(xs, ys) }
        }
    }

    private var highlighter = false
    private var color = Color.BLACK
    private var widthPx = 4f

    /** (Ré)ouvre le raw drawing sur la zone de la vue. À appeler après chaque changement de taille. */
    fun open(limit: Rect, fingerWrites: Boolean, exclude: List<Rect> = emptyList()): Boolean = guard {
        val h = helper ?: TouchHelper.create(host, callback).also { helper = it }
        if (isOpen) {
            h.setRawDrawingEnabled(false)
            h.closeRawDrawing()
        }
        // [exclude] : boutons posés sur la page (pages, croix) où le stylet ne doit pas écrire.
        h.setStrokeWidth(widthPx)
            .setLimitRect(limit, exclude)
            .openRawDrawing()
        applyStyle(h)
        h.enableSideBtnErase(true)
        h.enableFingerTouch(fingerWrites)
        isOpen = true
    }

    /** Outil courant : stylo (style « fountain », sensible à la pression) ou surligneur (« marker »). */
    fun configure(highlighter: Boolean, color: Int, widthPx: Float) = guard {
        this.highlighter = highlighter
        this.color = color
        this.widthPx = widthPx
        helper?.let { applyStyle(it) }
    }

    private fun applyStyle(h: TouchHelper) {
        h.setStrokeStyle(if (highlighter) TouchHelper.STROKE_STYLE_MARKER else TouchHelper.STROKE_STYLE_FOUNTAIN)
        h.setStrokeColor(color)
        h.setStrokeWidth(widthPx)
    }

    fun setEnabled(enabled: Boolean) = guard {
        helper?.takeIf { isOpen }?.setRawDrawingEnabled(enabled)
    }

    fun setFingerWrites(enabled: Boolean) = guard { helper?.enableFingerTouch(enabled) }

    /**
     * Après un nouveau rendu de la surface : désactive puis réactive le raw drawing pour que
     * l'écran affiche le nouveau contenu (le SDK « gèle » l'affichage pendant l'écriture).
     */
    fun resync(enabled: Boolean) = guard {
        val h = helper ?: return@guard
        if (!isOpen) return@guard
        h.setRawDrawingEnabled(false)
        if (enabled) h.setRawDrawingEnabled(true)
    }

    fun close() = guard {
        helper?.let {
            it.setRawDrawingEnabled(false)
            it.closeRawDrawing()
        }
        isOpen = false
    }

    private inline fun guard(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: Throwable) {
        false
    }

    companion object {
        fun isOnyxDevice(): Boolean =
            Build.MANUFACTURER.equals("ONYX", ignoreCase = true) ||
                Build.BRAND.equals("ONYX", ignoreCase = true) ||
                Build.BRAND.equals("BOOX", ignoreCase = true)

        /** Rafraîchissement complet (mode GC) pour effacer les rémanences de l'encre. */
        fun fullRefresh(view: View) {
            if (!isOnyxDevice()) return
            try {
                EpdController.refreshScreen(view, UpdateMode.GC)
            } catch (t: Throwable) {
                try {
                    EpdController.repaintEveryThing(UpdateMode.GC)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
