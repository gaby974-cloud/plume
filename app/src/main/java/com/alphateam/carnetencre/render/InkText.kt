package com.alphateam.carnetencre.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat
import com.alphateam.carnetencre.R
import com.alphateam.carnetencre.model.TextBlock

/** Mise en page des réponses de Claude posées sur la page (texte bleu, trait bleu à gauche), en unités de page. */
object InkText {
    const val CLAUDE_BLUE = 0xFF1B4EA3.toInt()

    private const val PAD = 18f
    private const val BAR = 5f
    private const val BODY_SIZE = 29f

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CLAUDE_BLUE
        textSize = BODY_SIZE
    }
    private val barPaint = Paint().apply { color = CLAUDE_BLUE }
    private val cache = LruCache<String, StaticLayout>(48)

    fun init(context: Context) {
        bodyPaint.typeface = ResourcesCompat.getFont(context, R.font.literata) ?: Typeface.SERIF
        cache.evictAll()
    }

    @Synchronized
    private fun layout(text: String, width: Float): StaticLayout {
        val key = "${width.toInt()}|$text"
        cache.get(key)?.let { return it }
        val innerWidth = (width - 2 * PAD - BAR).toInt().coerceAtLeast(80)
        val l = StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, innerWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1f)
            .setIncludePad(false)
            .build()
        cache.put(key, l)
        return l
    }

    fun blockHeight(text: String, width: Float): Float =
        PAD + layout(text, width).height + PAD

    @Synchronized
    fun draw(canvas: Canvas, block: TextBlock) {
        val l = layout(block.text, block.w)
        val h = PAD + l.height + PAD
        canvas.drawRect(block.x, block.y, block.x + BAR, block.y + h, barPaint)
        val left = block.x + BAR + PAD
        canvas.save()
        canvas.translate(left, block.y + PAD)
        l.draw(canvas)
        canvas.restore()
    }
}
