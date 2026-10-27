package com.alphateam.carnetencre.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import com.alphateam.carnetencre.model.PAGE_H
import com.alphateam.carnetencre.model.PAGE_W
import com.alphateam.carnetencre.model.PageDocument
import android.graphics.Path
import com.alphateam.carnetencre.model.Lasso
import com.alphateam.carnetencre.model.PageElement
import com.alphateam.carnetencre.model.boundsOf
import com.alphateam.carnetencre.model.ScribbleEraser
import com.alphateam.carnetencre.model.Stroke
import com.alphateam.carnetencre.model.Block
import com.alphateam.carnetencre.model.TextBlock
import com.alphateam.carnetencre.model.newElementId
import com.alphateam.carnetencre.render.PageRenderer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Tool {
    PEN, HIGHLIGHTER, ERASER, ZONE, LASSO;

    val inks: Boolean get() = this == PEN || this == HIGHLIGHTER
}

/**
 * Affichage et saisie d'une page.
 *
 * - Sur Boox, l'écriture au stylet passe par le SDK Onyx (raw drawing, latence minimale) :
 *   le SDK trace l'encre à l'écran et nous renvoie les points à la levée du stylet.
 * - Ailleurs (émulateur), ou pour la gomme et la zone, on traite les MotionEvent Android.
 *
 * La page est rendue dans un bitmap de la taille de la vue, puis copié sur la surface.
 */
@SuppressLint("ViewConstructor")
class PageView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, OnyxPen.Sink {

    interface Listener {
        fun onStroke(stroke: Stroke)
        /** [continuing] : la suite d'un même geste de gomme (une seule étape d'annulation). */
        fun onErase(hits: List<PageElement>, continuing: Boolean)
        fun onZoneChanged(zone: RectF?)
        fun onTextMoved(block: Block, dx: Float, dy: Float)
        fun onPageTouched()

        /** Gestes aux doigts : toucher à deux doigts, à trois doigts, balayage horizontal. */
        fun onUndoGesture()
        fun onRedoGesture()
        fun onSwipePage(delta: Int)

        /** Partie de la page visible à l'écran, en unités de page (haut, bas). */
        fun onViewportChanged(top: Float, bottom: Float)

        /** Lasso refermé : éléments entourés (liste vide = sélection retirée) ; [drawn] : une vraie boucle, pas un simple appui. */
        fun onLassoSelected(elements: List<PageElement>, drawn: Boolean)

        /** Sélection glissée de ([dx], [dy]) unités de page. */
        fun onSelectionMoved(dx: Float, dy: Float)
    }

    /** Ordre de défilement venu de l'écran (boutons ▲ ▼, réponse posée à montrer). */
    class ScrollCommand(val key: Int, val direction: Int = 0, val revealTop: Float = 0f, val revealBottom: Float = 0f)

    var listener: Listener? = null

    private var document: PageDocument? = null
    private var tool = Tool.PEN
    private var inkWidth = 4.2f
    private var inkColor = Stroke.BLACK
    private var stylusOnly = true
    private var overlayActive = false
    private var hostResumed = true
    private var refreshKey = -1

    private val pen: OnyxPen? = if (OnyxPen.isOnyxDevice()) OnyxPen(this, this) else null

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var margin = 0f
    private val pageRect = RectF()

    /** Haut de la partie visible, en unités de page : la page peut être plus haute que l'écran. */
    private var scrollY = 0f
    private var scrolling = false
    private var lastScrollKey = -1
    private var renderedHeight = 0f

    /** Zones (pixels de la vue) couvertes par des boutons : le SDK n'y écrit pas. */
    private var rawExclusions: List<Rect> = emptyList()

    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var surfaceReady = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val borderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // ---------------------------------------------------------------- liaison avec Compose

    fun bind(
        doc: PageDocument?,
        refreshKey: Int,
        tool: Tool,
        inkColor: Int,
        inkWidth: Float,
        stylusOnly: Boolean,
        overlayActive: Boolean,
        scroll: ScrollCommand? = null,
        selection: List<PageElement> = emptyList(),
        rawExclusions: List<Rect> = emptyList(),
    ) {
        if (rawExclusions != this.rawExclusions) {
            this.rawExclusions = rawExclusions
            if (pen?.isOpen == true) {
                pen.open(Rect(0, 0, width, height), !stylusOnly, rawExclusions)
                pen.resync(rawEnabled())
            }
        }
        var rebuild = false
        var full = false
        val selectionChanged = selection !== this.selection
        this.selection = selection
        if (doc !== document) {
            document = doc
            cancelGesture()
            scrollY = 0f
            rebuild = true
            full = true
        }
        if (refreshKey != this.refreshKey) {
            this.refreshKey = refreshKey
            rebuild = true
            full = true
        }
        // La page s'est allongée (page infinie, réponse posée) : on redessine son cadre.
        if (doc != null && doc.height != renderedHeight) {
            renderedHeight = doc.height
            rebuild = true
        }
        if (stylusOnly != this.stylusOnly) {
            this.stylusOnly = stylusOnly
            pen?.setFingerWrites(!stylusOnly)
        }
        val toolChanged = tool != this.tool
        val overlayChanged = overlayActive != this.overlayActive
        if (inkWidth != this.inkWidth || inkColor != this.inkColor || toolChanged) {
            this.inkWidth = inkWidth
            this.inkColor = inkColor
            this.tool = tool
            configurePen()
        }
        this.tool = tool
        this.overlayActive = overlayActive
        if (toolChanged && tool != Tool.ZONE) zoneDraft = null

        if (scroll != null && scroll.key != lastScrollKey) {
            lastScrollKey = scroll.key
            if (scroll.direction != 0) {
                scrollY += scroll.direction * viewportUnits() * 0.85f
            } else {
                val vp = viewportUnits()
                if (scroll.revealBottom > scrollY + vp) scrollY = scroll.revealBottom - vp + REVEAL_PAD
                if (scroll.revealTop < scrollY) scrollY = scroll.revealTop - REVEAL_PAD
            }
            clampScroll()
            rebuild = true
            full = true
        }

        when {
            rebuild -> refresh(full)
            overlayChanged || toolChanged -> {
                present()
                pen?.resync(rawEnabled())
            }
            selectionChanged -> present()
        }
        // Au lasso, le SDK trace la boucle tant que rien n'est sélectionné ; une sélection se glisse ensuite à la main.
        if (selectionChanged && tool == Tool.LASSO) pen?.resync(rawEnabled())
    }

    /** Style du trait tracé par le SDK : l'encre de l'outil, ou un fil gris fin pour la boucle du lasso. */
    private fun configurePen() {
        if (tool == Tool.LASSO) {
            pen?.configure(false, LASSO_INK, 3f * scale)
        } else {
            pen?.configure(tool == Tool.HIGHLIGHTER, inkColor, inkWidth * scale)
        }
    }

    fun onHostResume() {
        hostResumed = true
        refresh(full = false)
    }

    fun onHostPause() {
        hostResumed = false
        // Les derniers traits ne sont peut-être que dans le bitmap : on les affiche avant de couper le SDK.
        if (surfaceStale) present()
        pen?.setEnabled(false)
    }

    // ---------------------------------------------------------------- surface

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        layoutPage(width, height)
        configurePen()
        pen?.open(Rect(0, 0, width, height), !stylusOnly, rawExclusions)
        refresh(full = false)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        pen?.close()
    }

    /** Échelle choisie pour qu'une page standard (3:4) tienne entière à l'écran. */
    private fun layoutPage(w: Int, h: Int) {
        margin = 8f * resources.displayMetrics.density
        scale = min((w - 2 * margin) / PAGE_W, (h - 2 * margin) / PAGE_H)
        offX = (w - PAGE_W * scale) / 2f
        if (cache?.width != w || cache?.height != h) {
            cache?.recycle()
            cache = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            cacheCanvas = Canvas(cache!!)
        }
        clampScroll()
    }

    private fun pageHeight() = document?.height ?: PAGE_H

    /** Hauteur visible, en unités de page. */
    private fun viewportUnits() = ((height - 2 * margin) / scale).coerceAtLeast(1f)

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(0f, max(0f, pageHeight() - viewportUnits()))
        offY = margin - scrollY * scale
        pageRect.set(offX, offY, offX + PAGE_W * scale, offY + pageHeight() * scale)
    }

    private fun reportViewport() {
        listener?.onViewportChanged(scrollY, scrollY + viewportUnits())
    }

    private fun rawEnabled(): Boolean =
        pen?.isOpen == true && (tool.inks || tool == Tool.LASSO && selection.isEmpty()) && !overlayActive && hostResumed &&
            document != null && mode != Mode.MOVE_TEXT && !scrolling

    /** Re-rend la page, l'affiche, et nettoie l'écran e-ink si [full]. */
    private fun refresh(full: Boolean) {
        clampScroll()
        rebuildCache()
        present()
        pen?.resync(rawEnabled())
        if (full) OnyxPen.fullRefresh(this)
        reportViewport()
    }

    private fun rebuildCache() {
        val c = cacheCanvas ?: return
        c.drawColor(Color.WHITE)
        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)
        val visible = RectF(0f, scrollY - 20f, PAGE_W, scrollY + viewportUnits() + 20f)
        document?.let { d ->
            // Pendant un glissé, la sélection est dessinée à part, décalée, par present().
            val shown = if (mode == Mode.DRAG_SELECTION) d.elements.filter { it !in selection } else d.elements
            PageRenderer.drawElements(c, shown, visible)
        }
        c.restore()
        c.drawRect(pageRect, borderPaint)
    }

    private fun present() {
        if (!surfaceReady) return
        val bmp = cache ?: return
        val c = try {
            holder.lockCanvas()
        } catch (e: Exception) {
            null
        } ?: return
        c.drawBitmap(bmp, 0f, 0f, null)
        surfaceStale = false
        val zone = zoneDraft ?: document?.zone
        if (zone != null) c.drawRect(toView(zone), dashPaint)
        movingBlock?.let { c.drawRect(toView(it.bounds()).apply { inset(-6f, -6f) }, dashPaint) }
        if (mode == Mode.DRAG_SELECTION) {
            c.save()
            c.translate(offX + dragDx * scale, offY + dragDy * scale)
            c.scale(scale, scale)
            PageRenderer.drawElements(c, selection)
            c.restore()
        }
        selectionBox()?.let { c.drawRect(toView(it).apply { offset(dragDx * scale, dragDy * scale) }, dashPaint) }
        if (lasso.size > 1) {
            val path = Path()
            path.moveTo(offX + lasso.xAt(0) * scale, offY + lasso.yAt(0) * scale)
            for (i in 1 until lasso.size) path.lineTo(offX + lasso.xAt(i) * scale, offY + lasso.yAt(i) * scale)
            c.drawPath(path, dashPaint)
        }
        holder.unlockCanvasAndPost(c)
    }

    /** Cadre de la sélection (unités de page), un peu élargi, ou null. */
    private fun selectionBox(): RectF? = boundsOf(selection)?.apply { inset(-SELECTION_PAD, -SELECTION_PAD) }

    private fun toView(r: RectF) = RectF(
        offX + r.left * scale, offY + r.top * scale,
        offX + r.right * scale, offY + r.bottom * scale,
    )

    private fun pageX(vx: Float) = ((vx - offX) / scale).coerceIn(0f, PAGE_W)
    private fun pageY(vy: Float) = ((vy - offY) / scale).coerceIn(0f, pageHeight())

    private fun withPageTransform(block: (Canvas) -> Unit) {
        val c = cacheCanvas ?: return
        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)
        block(c)
        c.restore()
    }

    // ---------------------------------------------------------------- SDK Onyx

    override fun onPenStroke(xs: FloatArray, ys: FloatArray, ps: FloatArray) {
        val doc = document ?: return
        val px = FloatArray(xs.size) { pageX(xs[it]) }
        val py = FloatArray(ys.size) { pageY(ys[it]) }
        if (tool == Tool.LASSO) {
            closeLasso(doc, px, py)
            refresh(full = false)
            return
        }
        if (!tool.inks) return
        commitStroke(newStroke(doc, px, py, ps))
    }

    /** Boucle du lasso refermée : sélectionne ce qu'elle entoure (un simple appui retire la sélection). */
    private fun closeLasso(doc: PageDocument, xs: FloatArray, ys: FloatArray) {
        var w = 0f
        var h = 0f
        if (xs.isNotEmpty()) {
            w = xs.max() - xs.min()
            h = ys.max() - ys.min()
        }
        val drawn = xs.size >= 3 && max(w, h) >= MIN_ZONE
        val picked = if (drawn) Lasso.select(xs, ys, doc.elements) else emptyList()
        listener?.onLassoSelected(picked, drawn)
    }

    private fun newStroke(doc: PageDocument, xs: FloatArray, ys: FloatArray, ps: FloatArray) =
        Stroke(newElementId(), doc.nextZ(), inkWidth, xs, ys, ps, inkColor, tool == Tool.HIGHLIGHTER)

    /**
     * Ajoute un trait terminé, sauf s'il rature de l'encre existante : il efface alors
     * ce qu'il recouvre et disparaît lui-même.
     */
    private fun commitStroke(stroke: Stroke) {
        val doc = document ?: return
        val victims = ScribbleEraser.victims(stroke, doc.elements)
        if (victims != null) {
            listener?.onErase(victims, false)
            refresh(full = true)
            return
        }
        listener?.onStroke(stroke)
        if (rawEnabled()) {
            // Écriture au SDK : l'encre est déjà à l'écran. On ne touche pas à la surface
            // (une copie plein écran entre deux traits rapides fait ramer l'e-ink) : on met
            // seulement le bitmap à jour, il sera affiché au prochain rafraîchissement.
            if (stroke.highlighter) rebuildCache() else withPageTransform { PageRenderer.drawStroke(it, stroke) }
            surfaceStale = true
        } else if (stroke.highlighter) {
            // Le surligneur passe sous l'encre : on recompose la page.
            refresh(full = false)
        } else {
            withPageTransform { PageRenderer.drawStroke(it, stroke) }
            present()
        }
    }

    /** Vrai si le bitmap contient des traits pas encore copiés sur la surface. */
    private var surfaceStale = false

    override fun onPenErase(xs: FloatArray, ys: FloatArray) {
        if (document == null) return
        var erased = false
        for (i in xs.indices) {
            val j = max(0, i - 1)
            erased = eraseAlong(pageX(xs[j]), pageY(ys[j]), pageX(xs[i]), pageY(ys[i]), erased) || erased
        }
        if (erased) refresh(full = true) else pen?.resync(rawEnabled())
    }

    // ---------------------------------------------------------------- saisie tactile

    private enum class Mode { NONE, DRAW, ERASE, ZONE, MOVE_TEXT, GESTURE, LASSO, DRAG_SELECTION }

    /** Sélection au lasso (tenue par l'écran), boucle en cours de tracé, décalage du glissé. */
    private var selection: List<PageElement> = emptyList()
    private val lasso = LiveStroke()
    private var lastLassoPresent = 0L
    private var dragDx = 0f
    private var dragDy = 0f

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    /** Geste commencé au doigt (jamais au stylet) et nombre maximal de doigts posés. */
    private var fingerGesture = false
    private var maxPointers = 1
    private var lastPx = 0f
    private var lastPy = 0f
    private var erasedDuringGesture = false

    private var zoneDraft: RectF? = null
    private var pressedBlock: Block? = null
    private var movingBlock: Block? = null
    private var moveDx = 0f
    private var moveDy = 0f

    private val live = LiveStroke()

    private val longPress = Runnable {
        val block = pressedBlock
        if (!moved && block != null && mode != Mode.NONE && mode != Mode.ERASE) {
            if (mode == Mode.DRAW) {
                live.clear()
                rebuildCache()
            }
            mode = Mode.MOVE_TEXT
            movingBlock = block
            moveDx = 0f
            moveDy = 0f
            pen?.setEnabled(false)
            present()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val doc = document ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e, doc)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_UP -> onUp(e, doc)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun onDown(e: MotionEvent, doc: PageDocument) {
        listener?.onPageTouched()
        val type = e.getToolType(0)
        val isFinger = type == MotionEvent.TOOL_TYPE_FINGER
        val eraserTip = type == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val sdkDraws = rawEnabled() && (!isFinger || !stylusOnly)

        downX = e.x
        downY = e.y
        downTime = e.eventTime
        moved = false
        fingerGesture = isFinger
        maxPointers = 1
        erasedDuringGesture = false
        val px = pageX(e.x)
        val py = pageY(e.y)
        lastPx = px
        lastPy = py

        mode = when {
            eraserTip -> Mode.ERASE
            // Une sélection se déplace au stylet comme au doigt, en la prenant dans son cadre.
            tool == Tool.LASSO && selectionBox()?.contains(px, py) == true -> Mode.DRAG_SELECTION
            isFinger && stylusOnly -> Mode.GESTURE
            sdkDraws -> Mode.GESTURE
            tool == Tool.ERASER -> Mode.ERASE
            tool == Tool.ZONE -> Mode.ZONE
            tool == Tool.LASSO -> Mode.LASSO
            else -> Mode.DRAW
        }

        pressedBlock = if (mode != Mode.ERASE) doc.blockAt(px, py) else null
        if (pressedBlock != null) postDelayed(longPress, LONG_PRESS_MS)

        when (mode) {
            Mode.DRAW -> {
                live.start(px, py, pressureOf(e, -1))
                withPageTransform { PageRenderer.drawSegment(it, px, py, px, py, segWidth(live.lastP), liveColor()) }
                present()
            }
            Mode.ERASE -> if (eraseAlong(px, py, px, py, continuing = false)) {
                erasedDuringGesture = true
                rebuildCache()
                present()
            }
            Mode.ZONE -> zoneDraft = RectF(px, py, px, py)
            Mode.LASSO -> lasso.start(px, py, 0f)
            Mode.DRAG_SELECTION -> {
                removeCallbacks(longPress)
                dragDx = 0f
                dragDy = 0f
                rebuildCache()
                present()
            }
            else -> Unit
        }
    }

    private fun onMove(e: MotionEvent) {
        if (!moved && hypot(e.x - downX, e.y - downY) > touchSlop) {
            moved = true
            if (mode != Mode.MOVE_TEXT) removeCallbacks(longPress)
        }
        when (mode) {
            Mode.DRAW -> {
                for (h in 0 until e.historySize) {
                    addLivePoint(pageX(e.getHistoricalX(h)), pageY(e.getHistoricalY(h)), pressureOf(e, h))
                }
                addLivePoint(pageX(e.x), pageY(e.y), pressureOf(e, -1))
                present()
            }
            Mode.ERASE -> {
                val px = pageX(e.x)
                val py = pageY(e.y)
                if (eraseAlong(lastPx, lastPy, px, py, erasedDuringGesture)) {
                    erasedDuringGesture = true
                    rebuildCache()
                    present()
                }
                lastPx = px
                lastPy = py
            }
            Mode.ZONE -> {
                zoneDraft?.let { it.right = pageX(e.x); it.bottom = pageY(e.y) }
                present()
            }
            Mode.MOVE_TEXT -> {
                val block = movingBlock ?: return
                val px = pageX(e.x)
                val py = pageY(e.y)
                val dx = px - lastPx
                val dy = py - lastPy
                block.x += dx
                block.y += dy
                moveDx += dx
                moveDy += dy
                lastPx = px
                lastPy = py
                rebuildCache()
                present()
            }
            Mode.GESTURE -> if (fingerGesture && maxPointers == 1 && moved) dragScroll(e)
            Mode.LASSO -> {
                for (h in 0 until e.historySize) lasso.add(pageX(e.getHistoricalX(h)), pageY(e.getHistoricalY(h)), 0f)
                lasso.add(pageX(e.x), pageY(e.y), 0f)
                // Sans le SDK (émulateur), une copie de l'écran par point fait ramer : on espace les affichages.
                if (e.eventTime - lastLassoPresent >= LASSO_FRAME_MS) {
                    lastLassoPresent = e.eventTime
                    present()
                }
            }
            Mode.DRAG_SELECTION -> {
                val px = pageX(e.x)
                val py = pageY(e.y)
                dragDx += px - lastPx
                dragDy += py - lastPy
                lastPx = px
                lastPy = py
                present()
            }
            else -> Unit
        }
    }

    /** Glisser vertical d'un doigt : fait défiler une page plus haute que l'écran. */
    private fun dragScroll(e: MotionEvent) {
        if (!scrolling) {
            if (abs(e.y - downY) <= abs(e.x - downX) || pageHeight() <= viewportUnits() + 1f) return
            scrolling = true
            scrollAnchorY = e.y
            pen?.setEnabled(false)
        }
        scrollY += (scrollAnchorY - e.y) / scale
        scrollAnchorY = e.y
        clampScroll()
        rebuildCache()
        present()
    }

    private var scrollAnchorY = 0f

    private fun onUp(e: MotionEvent, doc: PageDocument) {
        removeCallbacks(longPress)
        val quickTap = !moved && e.eventTime - downTime < TAP_MS
        when (mode) {
            Mode.DRAW -> finishLiveStroke(doc)
            Mode.ERASE -> if (erasedDuringGesture) refresh(full = true)
            Mode.ZONE -> {
                val d = zoneDraft
                zoneDraft = null
                val r = d?.let { RectF(min(it.left, it.right), min(it.top, it.bottom), max(it.left, it.right), max(it.top, it.bottom)) }
                if (r == null || !moved || r.width() < MIN_ZONE || r.height() < MIN_ZONE) {
                    listener?.onZoneChanged(null)
                } else {
                    listener?.onZoneChanged(r)
                }
                present()
            }
            Mode.MOVE_TEXT -> {
                movingBlock?.let { listener?.onTextMoved(it, moveDx, moveDy) }
                movingBlock = null
                mode = Mode.NONE
                refresh(full = true)
            }
            Mode.GESTURE -> if (fingerGesture) onFingerGestureEnd(e, doc, quickTap)
            Mode.LASSO -> {
                val xs = if (moved) lasso.xs() else FloatArray(0)
                val ys = if (moved) lasso.ys() else FloatArray(0)
                lasso.clear()
                closeLasso(doc, xs, ys)
                present()
            }
            Mode.DRAG_SELECTION -> {
                mode = Mode.NONE
                val dx = dragDx
                val dy = dragDy
                dragDx = 0f
                dragDy = 0f
                if (moved && (dx != 0f || dy != 0f)) listener?.onSelectionMoved(dx, dy) else refresh(full = false)
            }
            Mode.NONE -> Unit
        }
        mode = Mode.NONE
        pressedBlock = null
    }

    /** Un doigt de plus se pose : on passe en geste (et on abandonne un trait commencé au doigt). */
    private fun onPointerDown(e: MotionEvent) {
        if (!fingerGesture) return
        maxPointers = max(maxPointers, e.pointerCount)
        removeCallbacks(longPress)
        if (mode == Mode.DRAW) {
            live.clear()
            rebuildCache()
            present()
        }
        val wasDragging = mode == Mode.DRAG_SELECTION
        if (mode != Mode.MOVE_TEXT) mode = Mode.GESTURE
        if (wasDragging) {
            dragDx = 0f
            dragDy = 0f
            rebuildCache()
            present()
        }
    }

    /**
     * Fin d'un geste au doigt : deux doigts = annuler, trois doigts = rétablir, balayage
     * horizontal = page voisine, appui court = retirer la zone.
     */
    private fun onFingerGestureEnd(e: MotionEvent, doc: PageDocument, quickTap: Boolean) {
        if (scrolling) {
            scrolling = false
            refresh(full = true)
            return
        }
        val elapsed = e.eventTime - downTime
        val dx = e.x - downX
        val dy = e.y - downY
        val still = hypot(dx, dy) < touchSlop * 3
        when {
            maxPointers == 2 && elapsed < MULTI_TAP_MS && still -> listener?.onUndoGesture()
            maxPointers >= 3 && elapsed < MULTI_TAP_MS && still -> listener?.onRedoGesture()
            maxPointers == 1 && abs(dx) > width * SWIPE_FRACTION && abs(dx) > 2 * abs(dy) && elapsed < SWIPE_MS ->
                listener?.onSwipePage(if (dx < 0) 1 else -1)
            maxPointers == 1 && quickTap && doc.zone != null -> {
                listener?.onZoneChanged(null)
                present()
                pen?.resync(rawEnabled())
            }
        }
    }

    private fun cancelGesture() {
        removeCallbacks(longPress)
        val wasMoving = movingBlock
        if (wasMoving != null) {
            wasMoving.x -= moveDx
            wasMoving.y -= moveDy
            movingBlock = null
        }
        live.clear()
        zoneDraft = null
        pressedBlock = null
        scrolling = false
        lasso.clear()
        dragDx = 0f
        dragDy = 0f
        if (mode != Mode.NONE) {
            mode = Mode.NONE
            refresh(full = false)
        }
    }

    private fun pressureOf(e: MotionEvent, historyIndex: Int): Float {
        val type = e.getToolType(0)
        if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_ERASER) return 0.6f
        val p = if (historyIndex < 0) e.pressure else e.getHistoricalPressure(historyIndex)
        return if (p <= 0f) 0.5f else p.coerceIn(0f, 1f)
    }

    private fun segWidth(p: Float) =
        if (tool == Tool.HIGHLIGHTER) inkWidth else PageRenderer.segmentWidth(inkWidth, p)

    private fun liveColor() =
        if (tool == Tool.HIGHLIGHTER) PageRenderer.highlighterColor(inkColor) else inkColor

    private fun addLivePoint(px: Float, py: Float, p: Float) {
        if (abs(px - live.lastX) < 0.5f && abs(py - live.lastY) < 0.5f) return
        val x0 = live.lastX
        val y0 = live.lastY
        val w = segWidth((live.lastP + p) / 2f)
        live.add(px, py, p)
        withPageTransform { PageRenderer.drawSegment(it, x0, y0, px, py, w, liveColor()) }
    }

    private fun finishLiveStroke(doc: PageDocument) {
        if (live.size == 0) return
        val stroke = newStroke(doc, live.xs(), live.ys(), live.ps())
        live.clear()
        commitStroke(stroke)
    }

    /** Gomme par trait entier le long du segment (x0,y0)→(x1,y1). Renvoie vrai si quelque chose a été effacé. */
    private fun eraseAlong(x0: Float, y0: Float, x1: Float, y1: Float, continuing: Boolean): Boolean {
        val doc = document ?: return false
        val dist = hypot(x1 - x0, y1 - y0)
        val steps = max(1, (dist / 5f).toInt())
        val hits = LinkedHashSet<PageElement>()
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            for (el in doc.elements) {
                if (el in hits) continue
                val hit = when (el) {
                    is Stroke -> el.hit(x, y, ERASER_RADIUS)
                    is Block -> el.contains(x, y)
                }
                if (hit) hits.add(el)
            }
        }
        if (hits.isEmpty()) return false
        listener?.onErase(hits.toList(), continuing)
        return true
    }

    companion object {
        private const val LONG_PRESS_MS = 550L
        private const val TAP_MS = 350L
        private const val MIN_ZONE = 24f
        private const val ERASER_RADIUS = 14f
        private const val REVEAL_PAD = 60f
        private const val SELECTION_PAD = 14f
        private const val LASSO_FRAME_MS = 40L
        private val LASSO_INK = Color.rgb(110, 110, 110)
        private const val MULTI_TAP_MS = 450L
        private const val SWIPE_MS = 700L
        private const val SWIPE_FRACTION = 0.2f
    }
}

/** Points du trait en cours de saisie (mode dégradé). */
private class LiveStroke {
    private var x = FloatArray(256)
    private var y = FloatArray(256)
    private var p = FloatArray(256)
    var size = 0
        private set

    val lastX get() = x[max(0, size - 1)]
    val lastY get() = y[max(0, size - 1)]
    val lastP get() = p[max(0, size - 1)]

    fun start(px: Float, py: Float, pp: Float) {
        size = 0
        add(px, py, pp)
    }

    fun add(px: Float, py: Float, pp: Float) {
        if (size == x.size) {
            x = x.copyOf(size * 2)
            y = y.copyOf(size * 2)
            p = p.copyOf(size * 2)
        }
        x[size] = px
        y[size] = py
        p[size] = pp
        size++
    }

    fun clear() {
        size = 0
    }

    fun xAt(i: Int) = x[i]
    fun yAt(i: Int) = y[i]
    fun xs() = x.copyOf(size)
    fun ys() = y.copyOf(size)
    fun ps() = p.copyOf(size)
}
