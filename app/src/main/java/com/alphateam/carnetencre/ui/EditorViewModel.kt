package com.alphateam.carnetencre.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphateam.carnetencre.CarnetApp
import com.alphateam.carnetencre.tr
import com.alphateam.carnetencre.claude.ClaudeException
import com.alphateam.carnetencre.claude.Mode
import com.alphateam.carnetencre.claude.ReplyPart
import com.alphateam.carnetencre.claude.ReplyParts
import com.alphateam.carnetencre.claude.StreamProgress
import com.alphateam.carnetencre.claude.Turn
import com.alphateam.carnetencre.data.Answer
import com.alphateam.carnetencre.data.InkPreset
import com.alphateam.carnetencre.data.Page
import com.alphateam.carnetencre.export.AutoExport
import com.alphateam.carnetencre.export.Exporter
import com.alphateam.carnetencre.model.PAGE_H
import com.alphateam.carnetencre.model.PAGE_MARGIN
import com.alphateam.carnetencre.model.PAGE_W
import com.alphateam.carnetencre.model.PageDocument
import com.alphateam.carnetencre.model.PageElement
import com.alphateam.carnetencre.model.Stroke
import com.alphateam.carnetencre.model.Block
import com.alphateam.carnetencre.model.FigureBlock
import com.alphateam.carnetencre.model.TextBlock
import com.alphateam.carnetencre.model.boundsOf
import com.alphateam.carnetencre.model.duplicated
import com.alphateam.carnetencre.model.newElementId
import com.alphateam.carnetencre.model.recolored
import com.alphateam.carnetencre.model.transformed
import com.alphateam.carnetencre.page.PageView
import com.alphateam.carnetencre.page.Tool
import com.alphateam.carnetencre.render.Figures
import com.alphateam.carnetencre.render.InkText
import com.alphateam.carnetencre.render.PageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Élément affiché dans le panneau Claude. */
sealed class PanelItem {
    class Sent(val modeLabel: String, val zone: Boolean) : PanelItem()
    class Question(val text: String) : PanelItem()
    class Reply(val text: String) : PanelItem()
}

class EditorViewModel(private val app: CarnetApp, val notebookId: Long) : ViewModel(), PageView.Listener {
    private val repo = app.repository
    private val settings = app.settings
    private val modeConfig get() = app.modes
    val modes: List<Mode> get() = modeConfig.modes

    // ------------------------------------------------------------ page et outils
    var title by mutableStateOf("")
        private set

    /** Carnet en page infinie : une seule page qui s'allonge vers le bas. */
    var infinite by mutableStateOf(false)
        private set
    var pages by mutableStateOf<List<Page>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var doc by mutableStateOf<PageDocument?>(null)
        private set

    /** Incrémenté quand la page doit être entièrement redessinée (annuler, réponse posée…). */
    var refreshKey by mutableIntStateOf(0)
        private set

    /** Incrémenté à chaque modification : sert à recalculer Annuler / Rétablir. */
    var editVersion by mutableIntStateOf(0)
        private set
    /** Les trois stylos favoris et celui qui est sélectionné. */
    var favorites by mutableStateOf(settings.favorites)
        private set
    var selectedFavorite by mutableIntStateOf(settings.selectedFavorite)
        private set
    var tool by mutableStateOf(if (settings.favorites[settings.selectedFavorite].highlighter) Tool.HIGHLIGHTER else Tool.PEN)
        private set
    var toolbarLeft by mutableStateOf(settings.toolbarLeft)
        private set
    var toolbarCollapsed by mutableStateOf(settings.toolbarCollapsed)
        private set
    var modeId by mutableStateOf(settings.modeId)
        private set
    var stylusOnly by mutableStateOf(settings.stylusOnly)
        private set
    var hasZone by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)

    val activeInk: InkPreset get() = favorites[selectedFavorite]

    /** Couleur et épaisseur de l'encre active (favori sélectionné). */
    val inkColor: Int get() = activeInk.color
    val inkWidth: Float get() = activeInk.width
    val currentMode: Mode get() = modeConfig.mode(modeId)

    // ------------------------------------------------------------ panneau Claude
    var panelOpen by mutableStateOf(false)
        private set
    var panelReadOnly by mutableStateOf(false)
        private set
    var panelItems by mutableStateOf<List<PanelItem>>(emptyList())
        private set
    var streaming by mutableStateOf(false)
        private set
    var panelError by mutableStateOf<String?>(null)
        private set
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText
    var history by mutableStateOf<List<Answer>?>(null)
        private set
    var exported by mutableStateOf<Exporter.ExportedFile?>(null)
        private set

    private val turns = mutableListOf<Turn>()
    private var streamJob: Job? = null

    /** Change quand la conversation est abandonnée : les flux périmés n'écrivent plus dans le panneau. */
    private var generation = 0
    private var saveJob: Job? = null
    private var savedRevision = 0
    private val saveMutex = Mutex()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val nb = repo.notebook(notebookId)
        title = nb?.title ?: tr("Carnet", "Notebook")
        infinite = nb?.infinite ?: false
        var list = repo.pages(notebookId)
        if (list.isEmpty()) {
            repo.insertPageAfter(notebookId, -1)
            list = repo.pages(notebookId)
        }
        pages = list
        openPage(0, save = false)
    }

    fun reloadSettings() {
        stylusOnly = settings.stylusOnly
        toolbarLeft = settings.toolbarLeft
        viewModelScope.launch { repo.notebook(notebookId)?.let { title = it.title } }
    }

    /** Renomme le carnet ; sa copie PDF dans Documents/Plume prendra le nouveau nom en sortant. */
    fun renameNotebook(newTitle: String) {
        val t = newTitle.trim()
        if (t.isEmpty() || t == title) return
        title = t
        copyDirty = true
        viewModelScope.launch {
            repo.notebook(notebookId)?.let { repo.renameNotebook(it, t) }
            toast = tr("Carnet renommé", "Notebook renamed")
        }
    }

    private suspend fun openPage(i: Int, save: Boolean = true) {
        if (save) saveNow()?.join()
        closePanel()
        selection = emptyList()
        val page = pages.getOrNull(i) ?: return
        val elements = repo.loadElements(page.id)
        val d = PageDocument(page.id, elements, page.height)
        viewTop = 0f
        viewBottom = PAGE_H
        savedRevision = d.revision
        index = i
        doc = d
        hasZone = false
        editVersion++
        syncUiState()
        refreshKey++
    }

    /**
     * État lu par l'interface. Ces valeurs ne changent qu'occasionnellement : la barre
     * d'outils n'est pas recomposée à chaque trait, ce qui garde l'écriture rapide fluide.
     */
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var pageHeight by mutableFloatStateOf(PAGE_H)
        private set

    private fun syncUiState() {
        val d = doc
        canUndo = d?.canUndo == true
        canRedo = d?.canRedo == true
        pageHeight = d?.height ?: PAGE_H
    }

    fun goTo(i: Int) {
        if (i == index || i !in pages.indices) return
        viewModelScope.launch { openPage(i) }
    }

    fun addPage() {
        val current = pages.getOrNull(index) ?: return
        viewModelScope.launch {
            saveNow()?.join()
            repo.insertPageAfter(notebookId, current.position)
            pages = repo.pages(notebookId)
            openPage(index + 1, save = false)
        }
    }

    fun deleteCurrentPage() {
        val current = pages.getOrNull(index) ?: return
        saveJob?.cancel()
        doc?.let { savedRevision = it.revision }
        viewModelScope.launch {
            saveMutex.withLock { repo.deletePage(current) }
            var list = repo.pages(notebookId)
            if (list.isEmpty()) {
                repo.insertPageAfter(notebookId, -1)
                list = repo.pages(notebookId)
            }
            pages = list
            openPage(index.coerceAtMost(list.size - 1), save = false)
            toast = tr("Page supprimée", "Page deleted")
        }
    }

    // ------------------------------------------------------------ PageView.Listener

    override fun onStroke(stroke: Stroke) {
        doc?.add(stroke) ?: return
        changed()
        ensureRoomBelow()
    }

    override fun onErase(hits: List<PageElement>, continuing: Boolean) {
        doc?.remove(hits, continuing) ?: return
        changed()
    }

    override fun onZoneChanged(zone: RectF?) {
        doc?.zone = zone
        hasZone = zone != null
    }

    override fun onTextMoved(block: Block, dx: Float, dy: Float) {
        doc?.recordMove(block, dx, dy) ?: return
        changed()
    }

    override fun onPageTouched() {
        if (toast != null) toast = null
    }

    override fun onUndoGesture() {
        toast = if (undo()) tr("Annulé", "Undone") else tr("Rien à annuler", "Nothing to undo")
    }

    override fun onRedoGesture() {
        toast = if (redo()) tr("Rétabli", "Redone") else tr("Rien à rétablir", "Nothing to redo")
    }

    /** Tourner à droite sur la dernière page en ajoute une (mode pages). */
    override fun onSwipePage(delta: Int) {
        val target = index + delta
        when {
            target in pages.indices -> goTo(target)
            delta > 0 && !infinite -> {
                addPage()
                toast = tr("Nouvelle page", "New page")
            }
            delta < 0 -> toast = tr("Première page", "First page")
        }
    }

    fun undo(): Boolean {
        if (doc?.undo() != true) return false
        selection = emptyList()
        changed()
        refreshKey++
        return true
    }

    fun redo(): Boolean {
        if (doc?.redo() != true) return false
        selection = emptyList()
        changed()
        refreshKey++
        return true
    }

    // ------------------------------------------------------------ lasso

    /** Éléments entourés au lasso ; les actions de la barre de sélection s'y appliquent. */
    var selection by mutableStateOf<List<PageElement>>(emptyList())
        private set

    override fun onLassoSelected(elements: List<PageElement>, drawn: Boolean) {
        selection = elements
        toast = if (elements.isEmpty() && drawn) tr("Rien n'est entouré : faites le tour complet des traits", "Nothing circled: draw all the way around the strokes") else null
    }

    override fun onSelectionMoved(dx: Float, dy: Float) {
        transformSelection { it.transformed(dx, dy) }
    }

    fun clearSelection() {
        selection = emptyList()
    }

    /** Recolore l'encre (stylo) ou le surlignage de la sélection, sans toucher à l'autre. */
    fun recolorSelection(argb: Int, highlighter: Boolean) = transformSelection {
        if (it is Stroke && it.highlighter == highlighter) it.recolored(argb) else it
    }

    /** Agrandit ou réduit la sélection autour de son centre. */
    fun scaleSelection(factor: Float) {
        val box = boundsOf(selection) ?: return
        transformSelection { it.transformed(0f, 0f, factor, box.centerX(), box.centerY()) }
    }

    fun duplicateSelection() {
        val d = doc ?: return
        if (selection.isEmpty()) return
        var z = d.nextZ()
        val copies = selection.map { it.duplicated(z++, DUPLICATE_OFFSET, DUPLICATE_OFFSET) }
        d.addAll(copies)
        selection = copies
        changed()
        refreshKey++
    }

    fun deleteSelection() {
        val d = doc ?: return
        if (selection.isEmpty()) return
        d.remove(selection)
        selection = emptyList()
        changed()
        refreshKey++
    }

    /** Remplace chaque élément sélectionné par sa version transformée (une étape d'annulation). */
    private fun transformSelection(f: (PageElement) -> PageElement) {
        val d = doc ?: return
        if (selection.isEmpty()) return
        val after = selection.map(f)
        d.replace(selection, after)
        selection = after
        d.growTo((boundsOf(after)?.bottom ?: 0f) + BOTTOM)
        changed()
        refreshKey++
    }

    /** Page suivante, ou nouvelle page si l'on est sur la dernière. */
    fun nextOrNewPage() {
        if (index < pages.lastIndex) goTo(index + 1) else addPage()
    }

    fun selectTool(t: Tool) {
        if (t != Tool.LASSO) selection = emptyList()
        tool = t
    }

    fun selectFavorite(i: Int) {
        selection = emptyList()
        selectedFavorite = i
        settings.selectedFavorite = i
        tool = if (favorites[i].highlighter) Tool.HIGHLIGHTER else Tool.PEN
    }

    /** Modifie un favori (type, couleur, épaisseur) et le sélectionne. */
    fun updateFavorite(i: Int, preset: InkPreset) {
        favorites = favorites.toMutableList().also { it[i] = preset }
        settings.favorites = favorites
        selectFavorite(i)
    }

    fun collapseToolbar(collapsed: Boolean) {
        toolbarCollapsed = collapsed
        settings.toolbarCollapsed = collapsed
    }

    fun selectMode(id: String) {
        modeId = id
        settings.modeId = id
    }

    /** Vrai si le carnet a changé depuis sa dernière copie PDF dans Documents/Plume. */
    private var copyDirty = false

    /**
     * En quittant le carnet ou en passant en arrière-plan : met à jour sa copie PDF dans
     * Documents/Plume, après la sauvegarde en cours.
     */
    fun publishPdfCopy() {
        if (!settings.autoPdfCopy || !copyDirty) return
        copyDirty = false
        val pendingSave = saveNow() ?: lastSave
        val name = title
        app.appScope.launch {
            pendingSave?.join()
            AutoExport.publishNotebook(app, repo, notebookId, name)
        }
    }

    private fun changed() {
        editVersion++
        copyDirty = true
        syncUiState()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }

    /**
     * Sauvegarde immédiate de la page courante si elle a changé. Tourne dans la portée de
     * l'application pour aboutir même si l'écran est fermé entre-temps.
     */
    fun saveNow(): Job? {
        val d = doc ?: return null
        val page = pages.firstOrNull { it.id == d.pageId } ?: return null
        if (d.revision == savedRevision) return null
        saveJob?.cancel()
        val snapshot = d.elements.toList()
        val height = d.height
        savedRevision = d.revision
        return app.appScope.launch {
            saveMutex.withLock {
                try {
                    repo.saveElements(page, snapshot, height)
                } catch (e: Exception) {
                    // Page supprimée entre-temps : rien à sauvegarder.
                }
            }
        }.also { lastSave = it }
    }

    /** Dernière sauvegarde lancée : la copie PDF l'attend pour inclure les derniers traits. */
    private var lastSave: Job? = null

    // ------------------------------------------------------------ Claude

    /** Zone ou sélection lue par Claude lors de la dernière demande : la réponse se pose dessous. */
    private var askAnchor: RectF? = null

    fun ask() {
        val d = doc ?: return
        abandonStream()
        turns.clear()
        panelItems = emptyList()
        panelReadOnly = false
        panelError = null
        _liveText.value = ""
        panelOpen = true
        if (settings.apiKey.isBlank()) {
            panelError = tr("Aucune clé API. Ajoutez votre clé Anthropic dans les Réglages (menu ⋯).", "No API key. Add your Anthropic key in Settings (⋯ menu).")
            return
        }
        val mode = currentMode
        // Sélection au lasso : Claude ne voit que les éléments entourés.
        val lassoed = selection.isNotEmpty()
        val zone = if (lassoed) boundsOf(selection)?.apply { inset(-20f, -20f) } else d.zone?.let { RectF(it) }
        askAnchor = zone
        // Page plus haute qu'un écran : Claude lit la partie visible (lisible à 1400 px).
        val region = zone ?: if (d.height > PAGE_H + 1f) RectF(0f, viewTop, PAGE_W, viewBottom) else null
        val elements = if (lassoed) selection.toList() else d.elements.toList()
        streaming = true
        streamJob = viewModelScope.launch {
            val png = withContext(Dispatchers.Default) { PageRenderer.renderPng(elements, region) }
            turns += Turn.User(modeConfig.prompt(mode, zone != null), png)
            panelItems = listOf(PanelItem.Sent(mode.label, zone != null))
            runStream(mode)
        }
    }

    fun followUp(question: String) {
        val q = question.trim()
        if (q.isEmpty() || streaming || panelReadOnly || turns.isEmpty()) return
        turns += Turn.User(q)
        panelItems = panelItems + PanelItem.Question(q)
        val mode = currentMode
        streamJob = viewModelScope.launch { runStream(mode) }
    }

    fun relaunch() {
        if (streaming || panelReadOnly) return
        if (turns.isEmpty()) {
            ask()
            return
        }
        if (turns.last() is Turn.Assistant) {
            turns.removeAt(turns.lastIndex)
            val lastReply = panelItems.indexOfLast { it is PanelItem.Reply }
            if (lastReply >= 0) panelItems = panelItems.filterIndexed { i, _ -> i != lastReply }
        }
        val mode = currentMode
        streamJob = viewModelScope.launch { runStream(mode) }
    }

    private suspend fun runStream(mode: Mode) {
        val gen = generation
        val progress = StreamProgress()
        val model = settings.model
        streaming = true
        panelError = null
        _liveText.value = ""
        var lastEmit = 0L
        try {
            app.claude.stream(settings.apiKey, model, turns.toList(), progress) {
                // Limiter les mises à jour de l'écran e-ink à environ 3 par seconde.
                val now = System.currentTimeMillis()
                if (now - lastEmit > STREAM_REFRESH_MS) {
                    lastEmit = now
                    _liveText.value = ReplyParts.forDisplay(progress.text.toString())
                }
            }
            finishAnswer(gen, progress, model, mode, complete = true)
        } catch (e: CancellationException) {
            finishAnswer(gen, progress, model, mode, complete = false)
            throw e
        } catch (e: ClaudeException) {
            finishAnswer(gen, progress, model, mode, complete = false)
            if (gen == generation) panelError = e.message
        } catch (e: Exception) {
            if (gen == generation) panelError = tr("Erreur inattendue", "Unexpected error") + " (${e.javaClass.simpleName}). " + tr("Réessayez.", "Please try again.")
        } finally {
            if (gen == generation) {
                streaming = false
                _liveText.value = ""
            }
        }
    }

    private fun finishAnswer(gen: Int, progress: StreamProgress, model: String, mode: Mode, complete: Boolean) {
        settings.addUsage(progress.inputTokens, progress.outputTokens)
        val text = progress.text.toString().trim()
        if (text.isEmpty() || gen != generation) return
        turns += Turn.Assistant(text, progress.content())
        panelItems = panelItems + PanelItem.Reply(if (complete) text else "$text\n" + interrupted())
        val pageId = doc?.pageId ?: return
        if (complete) {
            app.appScope.launch {
                try {
                    repo.saveAnswer(
                        Answer(
                            pageId = pageId,
                            mode = mode.label,
                            text = text,
                            model = model,
                            inputTokens = progress.inputTokens,
                            outputTokens = progress.outputTokens,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Bouton Arrêter : coupe la requête en gardant le début de réponse. */
    fun stopStream() {
        streamJob?.cancel()
    }

    private fun abandonStream() {
        generation++
        streamJob?.cancel()
        streamJob = null
        streaming = false
        _liveText.value = ""
    }

    fun closePanel() {
        abandonStream()
        panelOpen = false
        panelReadOnly = false
        panelItems = emptyList()
        panelError = null
        turns.clear()
    }

    fun latestReply(): String? =
        (panelItems.lastOrNull { it is PanelItem.Reply } as PanelItem.Reply?)?.text
            ?.removeSuffix("\n" + interrupted())

    fun copyReply(context: Context) {
        val text = latestReply()?.let { ReplyParts.textOnly(it) }?.takeIf { it.isNotBlank() } ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(tr("Réponse de Claude", "Claude answer"), text))
        toast = tr("Réponse copiée", "Answer copied")
    }

    /**
     * Pose la réponse sur la page : sous la zone ou la sélection lue par Claude, sinon sous
     * le contenu le plus bas. La page s'allonge si besoin ; on ne change jamais de page.
     */
    fun poseReply() {
        val raw = latestReply() ?: return
        val d = doc ?: return
        val zone = askAnchor ?: d.zone
        // Texte : la largeur de la zone (minimum lisible) ; dessins : plus larges, pour écrire dedans.
        fun column(minWidth: Float): Pair<Float, Float> = if (zone != null) {
            val w = max(zone.width(), minWidth).coerceAtMost(PAGE_W - 2 * SIDE)
            zone.left.coerceIn(SIDE, PAGE_W - SIDE - w) to w
        } else {
            PAGE_MARGIN to PAGE_W - 2 * PAGE_MARGIN
        }
        val top = if (zone != null) zone.bottom + 24f else (d.contentBottom()?.let { it + 32f } ?: PAGE_MARGIN)
        var y = top
        var z = d.nextZ()
        val blocks = mutableListOf<PageElement>()
        for (part in ReplyParts.split(raw)) {
            when (part) {
                is ReplyPart.Text -> {
                    val (x, w) = column(MIN_BLOCK_W)
                    blocks += TextBlock(newElementId(), z++, x, y, w, part.text)
                    y += InkText.blockHeight(part.text, w) + GAP
                }
                is ReplyPart.Svg -> {
                    val aspect = Figures.aspect(part.svg)
                    if (aspect == null) {
                        toast = tr("Un dessin de Claude est illisible : il n'a pas été posé", "A Claude drawing is unreadable: it was not placed")
                        continue
                    }
                    val (x, w) = column(MIN_FIGURE_W)
                    val h = w * aspect
                    blocks += FigureBlock(newElementId(), z++, x, y, w, h, part.svg)
                    y += h + GAP
                }
            }
        }
        if (blocks.isEmpty()) return
        closePanel()
        d.zone = null
        hasZone = false
        askAnchor = null
        selection = emptyList()
        // On ne change jamais de page : la page s'allonge vers le bas si besoin.
        d.growTo(y + BOTTOM)
        d.addAll(blocks)
        changed()
        refreshKey++
        scrollCommand = PageView.ScrollCommand(++scrollKey, revealTop = top, revealBottom = y)
    }

    // ------------------------------------------------------------ défilement et page infinie

    /** Partie visible de la page (unités), communiquée par la vue. */
    private var viewTop = 0f
    private var viewBottom = PAGE_H
    private var scrollKey = 0
    var scrollCommand by mutableStateOf<PageView.ScrollCommand?>(null)
        private set

    /** Vrai si la page courante est plus haute qu'un écran : on affiche ▲ ▼. */
    val pageScrolls: Boolean get() = pageHeight > PAGE_H + 1f

    fun scrollPage(direction: Int) {
        scrollCommand = PageView.ScrollCommand(++scrollKey, direction = direction)
    }

    /** Vrai si la sélection est dans la moitié haute de l'écran : sa barre se met alors en bas, pour ne pas la cacher. */
    val selectionInUpperHalf: Boolean
        get() = boundsOf(selection)?.let { it.centerY() < viewMiddle } ?: false

    private var viewMiddle by mutableStateOf(PAGE_H / 2f)

    override fun onViewportChanged(top: Float, bottom: Float) {
        viewTop = top
        viewBottom = bottom
        viewMiddle = (top + bottom) / 2f
        ensureRoomBelow()
    }

    /**
     * Page infinie : on garde toujours de la place libre sous l'écriture et sous la partie
     * visible, sans allonger indéfiniment une page vide.
     */
    private fun ensureRoomBelow() {
        if (!infinite) return
        val d = doc ?: return
        val content = d.contentBottom() ?: 0f
        val target = minOf(content + PAGE_H, maxOf(content + PAGE_H * 0.5f, viewBottom + PAGE_H * 0.5f))
        if (d.growTo(target)) changed()
    }

    fun showHistory() {
        val pageId = doc?.pageId ?: return
        viewModelScope.launch { history = repo.recentAnswers(pageId) }
    }

    fun dismissHistory() {
        history = null
    }

    fun openFromHistory(answer: Answer) {
        history = null
        askAnchor = null
        abandonStream()
        turns.clear()
        panelReadOnly = true
        panelError = null
        panelItems = listOf(PanelItem.Reply(answer.text))
        panelOpen = true
    }

    // ------------------------------------------------------------ export

    fun exportPage() {
        val d = doc ?: return
        val elements = d.elements.toList()
        val height = d.height
        val name = if (infinite) title else "$title - " + tr("page", "page") + " ${index + 1}"
        viewModelScope.launch {
            exported = withContext(Dispatchers.IO) { Exporter.pagePng(app, name, elements, height) }
        }
    }

    fun exportNotebook() {
        val d = doc ?: return
        val current = d.elements.toList() to d.height
        val currentId = d.pageId
        val list = pages
        viewModelScope.launch {
            toast = tr("Export du carnet en cours…", "Exporting notebook…")
            exported = withContext(Dispatchers.IO) {
                val all = list.map { p -> if (p.id == currentId) current else repo.loadElements(p.id) to p.height }
                Exporter.notebookPdf(app, title, all)
            }
            toast = null
        }
    }

    fun shareExport(context: Context) {
        exported?.let { Exporter.share(context, it) }
        exported = null
    }

    fun saveExportToDownloads() {
        val f = exported ?: return
        exported = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Exporter.saveToDownloads(app, f) }
            toast = if (ok) tr("Enregistré dans Téléchargements/Plume", "Saved to Download/Plume") else tr("Impossible d'enregistrer le fichier", "Could not save the file")
        }
    }

    fun dismissExport() {
        exported = null
    }

    override fun onCleared() {
        saveNow()
        super.onCleared()
    }

    companion object {
        /** Mention ajoutée à une réponse coupée (Arrêter, réseau perdu). */
        private fun interrupted() = tr("[réponse interrompue]", "[answer interrupted]")

        private const val SAVE_DELAY_MS = 1500L
        private const val STREAM_REFRESH_MS = 300L
        private const val MIN_BLOCK_W = 560f
        private const val SIDE = 40f
        private const val BOTTOM = 40f
        private const val DUPLICATE_OFFSET = 40f
        private const val MIN_FIGURE_W = 800f
        private const val GAP = 24f
    }
}
