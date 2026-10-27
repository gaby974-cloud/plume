package com.alphateam.carnetencre.model

import com.alphateam.carnetencre.data.Element
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Encodage compact des éléments pour Room.
 *
 * Trait : version, type (stylo / surligneur), couleur ARGB, largeur (dixièmes d'unité),
 * nombre de points, puis pour chaque point
 * dx et dy en demi-unités (varint zigzag, delta par rapport au point précédent) et la
 * pression quantifiée 0–9 sur un octet. Un point typique tient en 3 octets, si bien
 * qu'une page très chargée (≈ 50 000 points) reste sous 200 Ko.
 *
 * Bloc texte : JSON UTF-8 {x, y, w, t}. Figure : JSON UTF-8 {x, y, w, h, svg}.
 */
object ElementCodec {
    /** v1 : trait noir au stylo. v2 : ajoute le type (stylo / surligneur) et la couleur ARGB. */
    private const val STROKE_VERSION = 2

    fun toEntity(element: PageElement, pageId: Long): Element = when (element) {
        is Stroke -> Element(element.id, pageId, Element.STROKE, encodeStroke(element), element.z)
        is TextBlock -> Element(element.id, pageId, Element.TEXT, encodeText(element), element.z)
        is FigureBlock -> Element(element.id, pageId, Element.FIGURE, encodeFigure(element), element.z)
    }

    fun fromEntity(entity: Element): PageElement? = try {
        when (entity.type) {
            Element.STROKE -> decodeStroke(entity.id, entity.z, entity.payload)
            Element.TEXT -> decodeText(entity.id, entity.z, entity.payload)
            Element.FIGURE -> decodeFigure(entity.id, entity.z, entity.payload)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    fun encodeStroke(s: Stroke): ByteArray {
        val out = ByteArrayOutputStream(16 + s.size * 3)
        out.write(STROKE_VERSION)
        out.write(if (s.highlighter) 1 else 0)
        for (shift in intArrayOf(24, 16, 8, 0)) out.write((s.color ushr shift) and 0xFF)
        writeVarint(out, (s.width * 10f).roundToInt().coerceAtLeast(1))
        writeVarint(out, s.size)
        var px = 0
        var py = 0
        for (i in 0 until s.size) {
            val qx = (s.xs[i] * 2f).roundToInt()
            val qy = (s.ys[i] * 2f).roundToInt()
            writeVarint(out, zigzag(qx - px))
            writeVarint(out, zigzag(qy - py))
            out.write((s.ps[i] * 9f).roundToInt().coerceIn(0, 9))
            px = qx
            py = qy
        }
        return out.toByteArray()
    }

    fun decodeStroke(id: String, z: Int, data: ByteArray): Stroke {
        val r = Reader(data)
        val version = r.byte()
        require(version in 1..STROKE_VERSION) { "version de trait inconnue" }
        var highlighter = false
        var color = Stroke.BLACK
        if (version >= 2) {
            highlighter = r.byte() == 1
            color = (r.byte() shl 24) or (r.byte() shl 16) or (r.byte() shl 8) or r.byte()
        }
        val width = r.varint() / 10f
        val n = r.varint()
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val ps = FloatArray(n)
        var px = 0
        var py = 0
        for (i in 0 until n) {
            px += unzigzag(r.varint())
            py += unzigzag(r.varint())
            xs[i] = px / 2f
            ys[i] = py / 2f
            ps[i] = r.byte() / 9f
        }
        return Stroke(id, z, width, xs, ys, ps, color, highlighter)
    }

    private fun encodeFigure(f: FigureBlock): ByteArray = JSONObject()
        .put("x", f.x.toDouble())
        .put("y", f.y.toDouble())
        .put("w", f.w.toDouble())
        .put("h", f.height.toDouble())
        .put("svg", f.svg)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decodeFigure(id: String, z: Int, data: ByteArray): FigureBlock {
        val o = JSONObject(String(data, Charsets.UTF_8))
        return FigureBlock(
            id, z,
            o.getDouble("x").toFloat(),
            o.getDouble("y").toFloat(),
            o.getDouble("w").toFloat(),
            o.getDouble("h").toFloat(),
            o.getString("svg"),
        )
    }

    private fun encodeText(t: TextBlock): ByteArray = JSONObject()
        .put("x", t.x.toDouble())
        .put("y", t.y.toDouble())
        .put("w", t.w.toDouble())
        .put("t", t.text)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decodeText(id: String, z: Int, data: ByteArray): TextBlock {
        val o = JSONObject(String(data, Charsets.UTF_8))
        return TextBlock(
            id, z,
            o.getDouble("x").toFloat(),
            o.getDouble("y").toFloat(),
            o.getDouble("w").toFloat(),
            o.getString("t"),
        )
    }

    private fun zigzag(v: Int): Int = (v shl 1) xor (v shr 31)
    private fun unzigzag(v: Int): Int = (v ushr 1) xor -(v and 1)

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        fun byte(): Int = data[pos++].toInt() and 0xFF
        fun varint(): Int {
            var shift = 0
            var result = 0
            while (true) {
                val b = byte()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }
}
