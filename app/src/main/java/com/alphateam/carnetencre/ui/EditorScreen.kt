package com.alphateam.carnetencre.ui

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alphateam.carnetencre.CarnetApp
import com.alphateam.carnetencre.R
import com.alphateam.carnetencre.Lang
import com.alphateam.carnetencre.tr
import com.alphateam.carnetencre.claude.ReplyParts
import com.alphateam.carnetencre.data.AppSettings
import com.alphateam.carnetencre.data.InkPreset
import com.alphateam.carnetencre.page.PageView
import com.alphateam.carnetencre.page.Tool
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// Barre large et libellés gras : lisibles de loin sur l'encre électronique.
private val RAIL_W = 86.dp
private val RAIL_BUTTON_H = 74.dp
private const val HISTORY_PREVIEW_CHARS = 160

@Composable
fun EditorScreen(app: CarnetApp, notebookId: Long, onHome: () -> Unit, onSettings: () -> Unit) {
    val vm: EditorViewModel = viewModel(key = "editeur-$notebookId") { EditorViewModel(app, notebookId) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pageView by remember { mutableStateOf<PageView?>(null) }

    var favoriteMenu by remember { mutableStateOf(-1) }
    var modeMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var selectionColors by remember { mutableStateOf(false) }
    var navBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }

    // Plein écran : barres système masquées tant que le carnet est ouvert (balayage du bord pour les revoir).
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(lifecycleOwner, pageView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    vm.reloadSettings()
                    pageView?.onHostResume()
                }
                Lifecycle.Event.ON_PAUSE -> pageView?.onHostPause()
                Lifecycle.Event.ON_STOP -> {
                    vm.saveNow()
                    vm.publishPdfCopy()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.saveNow()
            vm.publishPdfCopy()
        }
    }

    vm.toast?.let { msg ->
        LaunchedEffect(msg) {
            delay(2500)
            if (vm.toast == msg) vm.toast = null
        }
    }

    // Le raw drawing est coupé dès qu'un menu, un dialogue ou le panneau s'affiche.
    val overlayActive = vm.panelOpen || favoriteMenu >= 0 || modeMenu || moreMenu || confirmDelete || selectionColors || renaming ||
        vm.history != null || vm.exported != null

    val rail: @Composable () -> Unit = {
        ToolRail(
            vm = vm,
            favoriteMenu = favoriteMenu, onFavoriteMenu = { favoriteMenu = it },
            modeMenu = modeMenu, onModeMenu = { modeMenu = it },
            moreMenu = moreMenu, onMoreMenu = { moreMenu = it },
            onDeletePage = { confirmDelete = true },
            onRename = { renaming = true },
            onHome = onHome,
            onSettings = onSettings,
        )
    }

    Row(Modifier.fillMaxSize().background(Paper)) {
        if (vm.toolbarLeft && !vm.toolbarCollapsed) rail()
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val panelMax = maxHeight * 0.4f
            AndroidView(
                factory = { ctx -> PageView(ctx).also { it.listener = vm; pageView = it } },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // Hauteur lue pour redessiner le cadre quand la page s'allonge (pas à chaque trait).
                    vm.pageHeight
                    view.bind(
                        doc = vm.doc,
                        refreshKey = vm.refreshKey,
                        tool = vm.tool,
                        inkColor = vm.inkColor,
                        inkWidth = vm.inkWidth,
                        stylusOnly = vm.stylusOnly,
                        overlayActive = overlayActive,
                        scroll = vm.scrollCommand,
                        selection = vm.selection,
                        rawExclusions = listOfNotNull(navBounds),
                    )
                },
            )
            // Pages et fermeture, en haut à droite de la page.
            PageNavBar(
                vm = vm,
                onClose = onHome,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .onGloballyPositioned { c ->
                        val b = c.boundsInParent()
                        navBounds = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                    },
            )
            if (vm.selection.isNotEmpty() && !vm.panelOpen) {
                SelectionBar(
                    vm,
                    colorMenu = selectionColors,
                    onColorMenu = { selectionColors = it },
                    modifier = if (vm.selectionInUpperHalf) {
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                    } else {
                        Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
                    },
                )
            }
            if (vm.toolbarCollapsed) {
                RailButton(
                    R.drawable.ic_expand,
                    tr("Outils", "Tools"),
                    { vm.collapseToolbar(false) },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .border(1.5.dp, Ink)
                        .background(Paper),
                    mirror = !vm.toolbarLeft,
                )
            }
            vm.toast?.let {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp)
                        .background(Ink)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    EText(it, color = Paper)
                }
            }
            if (vm.panelOpen) {
                ClaudePanel(vm, panelMax, maxHeight, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (!vm.toolbarLeft && !vm.toolbarCollapsed) rail()
    }

    if (renaming) {
        var text by remember { mutableStateOf(vm.title) }
        EDialog(
            title = tr("Renommer le carnet", "Rename notebook"),
            onDismiss = { renaming = false },
            buttons = {
                EButton(tr("Annuler", "Cancel"), { renaming = false })
                EButton(tr("Renommer", "Rename"), { vm.renameNotebook(text); renaming = false }, active = true, enabled = text.isNotBlank())
            },
        ) {
            ETextField(
                text,
                { text = it },
                Modifier.fillMaxWidth(),
                placeholder = tr("Titre du carnet", "Notebook title"),
                onDone = { vm.renameNotebook(text); renaming = false },
            )
        }
    }

    if (confirmDelete) {
        EConfirm(
            title = tr("Supprimer la page ?", "Delete this page?"),
            message = tr("La page ${vm.index + 1} et son historique de réponses seront supprimés définitivement.", "Page ${vm.index + 1} and its answer history will be permanently deleted."),
            confirm = tr("Supprimer", "Delete"),
            onConfirm = vm::deleteCurrentPage,
            onDismiss = { confirmDelete = false },
        )
    }

    vm.history?.let { answers ->
        EDialog(
            title = tr("Dernières réponses de cette page", "Latest answers for this page"),
            onDismiss = vm::dismissHistory,
            buttons = { EButton(tr("Fermer", "Close"), vm::dismissHistory) },
        ) {
            if (answers.isEmpty()) {
                EText(tr("Aucune réponse pour cette page.", "No answers for this page yet."))
            } else {
                val fmt = remember(Lang.locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Lang.locale) }
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (a in answers) {
                        EMenuItem(
                            "${fmt.format(Date(a.createdAt))} · ${a.mode}\n" +
                                ReplyParts.forDisplay(a.text).replace('\n', ' ').take(HISTORY_PREVIEW_CHARS),
                            { vm.openFromHistory(a) },
                        )
                    }
                }
            }
        }
    }

    vm.exported?.let { f ->
        EDialog(
            title = tr("Export prêt", "Export ready"),
            onDismiss = vm::dismissExport,
            buttons = {
                EButton(tr("Annuler", "Cancel"), vm::dismissExport)
                EButton(tr("Enregistrer", "Save"), vm::saveExportToDownloads)
                EButton(tr("Partager", "Share"), { vm.shareExport(context) }, active = true)
            },
        ) {
            EText(f.file.name)
            EText(tr("Partager vers une autre application, ou enregistrer dans Téléchargements/Plume.", "Share to another app, or save to Download/Plume."), color = Muted, size = 16.sp)
        }
    }
}

/**
 * Barre d'outils verticale, sur le côté de la page : stylos favoris, outils, pages, Claude.
 * Les menus s'ouvrent à côté de la barre, vers la page.
 */
@Composable
private fun ToolRail(
    vm: EditorViewModel,
    favoriteMenu: Int, onFavoriteMenu: (Int) -> Unit,
    modeMenu: Boolean, onModeMenu: (Boolean) -> Unit,
    moreMenu: Boolean, onMoreMenu: (Boolean) -> Unit,
    onDeletePage: () -> Unit,
    onRename: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
) {

    val left = vm.toolbarLeft
    val menuTop = if (left) Alignment.TopStart else Alignment.TopEnd
    val menuBottom = if (left) Alignment.BottomStart else Alignment.BottomEnd
    val menuOffset = DpOffset(if (left) RAIL_W else -RAIL_W, 0.dp)
    val showPages = !vm.infinite || vm.pages.size > 1

    Column(
        Modifier
            .width(RAIL_W)
            .fillMaxHeight()
            .background(Paper)
            .border(1.dp, Ink)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailButton(R.drawable.ic_collapse, tr("Masquer", "Hide"), { vm.collapseToolbar(true) }, mirror = !left)
        RailDivider()

        vm.favorites.forEachIndexed { i, preset ->
            val selected = vm.tool.inks && vm.selectedFavorite == i
            Box {
                FavoriteButton(
                    preset = preset,
                    selected = selected,
                    onClick = { if (selected) onFavoriteMenu(i) else vm.selectFavorite(i) },
                    onLongClick = { onFavoriteMenu(i) },
                )
                if (favoriteMenu == i) {
                    FavoriteEditor(
                        preset = preset,
                        onChange = { vm.updateFavorite(i, it) },
                        onDone = { onFavoriteMenu(-1) },
                        alignment = menuTop,
                        offset = menuOffset,
                    )
                }
            }
        }
        RailButton(R.drawable.ic_eraser, tr("Gomme", "Eraser"), { vm.selectTool(Tool.ERASER) }, active = vm.tool == Tool.ERASER)
        RailButton(R.drawable.ic_zone, if (vm.hasZone) tr("Zone ■", "Area ■") else tr("Zone", "Area"), { vm.selectTool(Tool.ZONE) }, active = vm.tool == Tool.ZONE)
        RailButton(R.drawable.ic_lasso, "Lasso", { vm.selectTool(Tool.LASSO) }, active = vm.tool == Tool.LASSO)
        RailPair(
            caption = null,
            first = { RailHalf(R.drawable.ic_undo, tr("Annuler", "Undo"), { vm.undo() }, enabled = vm.canUndo) },
            second = { RailHalf(R.drawable.ic_redo, tr("Rétablir", "Redo"), { vm.redo() }, enabled = vm.canRedo) },
        )

        if (vm.infinite || vm.pageScrolls) {
            RailDivider()
            RailPair(
                caption = tr("Défiler", "Scroll"),
                first = { RailHalf(R.drawable.ic_prev, tr("Haut", "Up"), { vm.scrollPage(-1) }, rotation = 90f) },
                second = { RailHalf(R.drawable.ic_next, tr("Bas", "Down"), { vm.scrollPage(1) }, rotation = 90f) },
            )
        }

        RailDivider()
        RailButton(R.drawable.ic_claude, "Claude", vm::ask, active = true, accent = ClaudeBlue)
        Box {
            RailButton(R.drawable.ic_menu_mode, vm.currentMode.label, { onModeMenu(true) }, accent = ClaudeBlue, labelLines = 2)
            if (modeMenu) {
                EMenu(onDismiss = { onModeMenu(false) }, alignment = menuTop, offset = menuOffset) {
                    EText(tr("Mode de Claude", "Claude mode"), bold = true, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                    for (m in vm.modes) {
                        EMenuItem(m.label, { vm.selectMode(m.id); onModeMenu(false) }, active = m.id == vm.modeId)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Box {
            RailButton(R.drawable.ic_more, "Menu", { onMoreMenu(true) })
            if (moreMenu) {
                EMenu(onDismiss = { onMoreMenu(false) }, alignment = menuBottom, offset = menuOffset) {
                    // Le nom du carnet : un appui ouvre le renommage.
                    EMenuItem("${vm.title}  ✎", { onMoreMenu(false); onRename() })
                    EMenuItem(tr("Historique des réponses", "Answer history"), { onMoreMenu(false); vm.showHistory() })
                    EMenuItem(tr("Exporter la page (PNG)", "Export page (PNG)"), { onMoreMenu(false); vm.exportPage() })
                    EMenuItem(tr("Exporter le carnet (PDF)", "Export notebook (PDF)"), { onMoreMenu(false); vm.exportNotebook() })
                    if (showPages) EMenuItem(tr("Supprimer la page", "Delete page"), { onMoreMenu(false); onDeletePage() })
                    EMenuItem(tr("Masquer la barre d'outils", "Hide toolbar"), { onMoreMenu(false); vm.collapseToolbar(true) })
                    EMenuItem(tr("Réglages", "Settings"), { onMoreMenu(false); onSettings() })
                    EMenuItem(tr("Tous les carnets", "All notebooks"), { onMoreMenu(false); onHome() })
                }
            }
        }
    }
}

/** Deux boutons d'icône côte à côte (annuler / rétablir, page précédente / suivante…), légende dessous. */
@Composable
private fun RailPair(caption: String?, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
        Row {
            first()
            second()
        }
        caption?.let { EText(it, size = 14.sp, bold = true, maxLines = 1) }
    }
}

@Composable
private fun RailHalf(icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true, rotation: Float = 0f) {
    Box(
        Modifier
            .width(RAIL_W / 2)
            .height(54.dp)
            .tap(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(icon),
            contentDescription = description,
            colorFilter = ColorFilter.tint(if (enabled) Ink else Muted),
            modifier = Modifier.size(34.dp).rotate(rotation),
        )
    }
}

@Composable
private fun RailDivider() {
    Spacer(
        Modifier
            .padding(vertical = 4.dp)
            .width(RAIL_W - 16.dp)
            .height(1.dp)
            .background(Ink),
    )
}

/** Bouton de la barre : icône et petit libellé, cible de 68 × 56 dp, inversé quand actif. */
@Composable
private fun RailButton(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    accent: Color = Ink,
    mirror: Boolean = false,
    iconRotation: Float = 0f,
    labelLines: Int = 1,
) {
    val fg = when {
        active -> Paper
        enabled -> accent
        else -> Muted
    }
    Column(
        modifier
            .width(RAIL_W)
            .defaultMinSize(minHeight = RAIL_BUTTON_H)
            .background(if (active) accent else Color.Transparent)
            .tap(enabled, onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(icon),
            contentDescription = label,
            colorFilter = ColorFilter.tint(fg),
            modifier = Modifier
                .size(34.dp)
                .rotate(if (mirror) 180f else iconRotation),
        )
        EText(
            label,
            size = 15.sp,
            bold = true,
            color = fg,
            maxLines = labelLines,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Favori : pastille de l'encre (point pour le stylo, bande pour le surligneur). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteButton(preset: InkPreset, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier
            .width(RAIL_W)
            .height(RAIL_BUTTON_H)
            .background(if (selected) Ink else Paper)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        InkSwatch(preset, if (selected) Paper else Ink)
        if (selected) {
            EText("▾", color = Paper, size = 16.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 2.dp))
        }
    }
}

@Composable
private fun InkSwatch(preset: InkPreset, outline: Color) {
    Canvas(Modifier.size(width = 58.dp, height = 50.dp)) {
        val c = Color(preset.color)
        if (preset.highlighter) {
            val h = (10 + preset.widthIndex * 6).dp.toPx()
            val top = (size.height - h) / 2f
            drawRoundRect(c.copy(alpha = 0.75f), Offset(2f, top), Size(size.width - 4f, h), CornerRadius(h / 2f))
        } else {
            val r = (7 + preset.widthIndex * 3).dp.toPx()
            drawCircle(outline, radius = r + 2.dp.toPx(), center = center)
            drawCircle(c, radius = r, center = center)
        }
    }
}

/** Réglage d'un favori : type (stylo / surligneur), couleur, épaisseur. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteEditor(
    preset: InkPreset,
    onChange: (InkPreset) -> Unit,
    onDone: () -> Unit,
    alignment: Alignment,
    offset: DpOffset,
) {
    val colors = if (preset.highlighter) AppSettings.HIGHLIGHTER_COLORS else AppSettings.PEN_COLORS
    val widths = if (preset.highlighter) AppSettings.HIGHLIGHTER_LABELS else AppSettings.PEN_LABELS
    EMenu(onDismiss = onDone, alignment = alignment, offset = offset) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (hl in listOf(false, true)) {
                EButton(
                    if (hl) tr("Surligneur", "Highlighter") else tr("Stylo", "Pen"),
                    {
                        if (hl != preset.highlighter) {
                            val palette = if (hl) AppSettings.HIGHLIGHTER_COLORS else AppSettings.PEN_COLORS
                            onChange(InkPreset(hl, palette[0].argb, 1))
                        }
                    },
                    active = hl == preset.highlighter,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        EText(tr("Couleur", "Color"), bold = true, modifier = Modifier.padding(start = 16.dp))
        FlowRow(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (c in colors) {
                val selected = c.argb == preset.color
                Box(
                    Modifier
                        .size(48.dp)
                        .border(if (selected) 4.dp else 1.dp, Ink)
                        .padding(if (selected) 6.dp else 3.dp)
                        .background(Color(c.argb))
                        .tap { onChange(preset.copy(color = c.argb)) },
                )
            }
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Ink))
        EText(tr("Épaisseur", "Width"), bold = true, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        widths.forEachIndexed { i, label ->
            EMenuItem(label, { onChange(preset.copy(widthIndex = i)); onDone() }, active = i == preset.widthIndex)
        }
    }
}

/**
 * Actions sur la sélection au lasso. On la déplace en la glissant (stylet ou doigt dans son
 * cadre) ; un appui hors du cadre ou une nouvelle boucle la remplace.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    vm: EditorViewModel,
    colorMenu: Boolean,
    onColorMenu: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = vm.selection.size
    FlowRow(
        modifier
            .padding(horizontal = 8.dp)
            .background(Paper)
            .border(2.dp, Ink)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EText(
            if (count == 1) tr("1 élément", "1 item") else tr("$count éléments", "$count items"),
            bold = true,
            modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 6.dp),
        )
        Box {
            EButton(tr("Couleur ▾", "Color ▾"), { onColorMenu(true) })
            if (colorMenu) {
                // Barre en bas de l'écran : le menu s'ouvre vers le haut.
                val up = vm.selectionInUpperHalf
                EMenu(
                    onDismiss = { onColorMenu(false) },
                    alignment = if (up) Alignment.BottomStart else Alignment.TopStart,
                    offset = DpOffset(0.dp, if (up) (-58).dp else 52.dp),
                ) {
                    EText(tr("Encre", "Ink"), bold = true, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                    SwatchRow(AppSettings.PEN_COLORS.map { it.argb }) { vm.recolorSelection(it, highlighter = false); onColorMenu(false) }
                    EText(tr("Surligneur", "Highlighter"), bold = true, modifier = Modifier.padding(start = 16.dp))
                    SwatchRow(AppSettings.HIGHLIGHTER_COLORS.map { it.argb }) { vm.recolorSelection(it, highlighter = true); onColorMenu(false) }
                }
            }
        }
        EButton(tr("Réduire", "Shrink"), { vm.scaleSelection(0.8f) })
        EButton(tr("Agrandir", "Enlarge"), { vm.scaleSelection(1.25f) })
        EButton(tr("Dupliquer", "Duplicate"), vm::duplicateSelection)
        EButton(tr("Supprimer", "Delete"), vm::deleteSelection)
        EButton("Claude", vm::ask, active = true, accent = ClaudeBlue)
        EButton("✕", vm::clearSelection)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(colors: List<Int>, onPick: (Int) -> Unit) {
    FlowRow(
        Modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (c in colors) {
            Box(
                Modifier
                    .size(48.dp)
                    .border(1.dp, Ink)
                    .padding(3.dp)
                    .background(Color(c))
                    .tap { onPick(c) },
            )
        }
    }
}

/**
 * En haut à droite de la page : page précédente, compteur, page suivante (qui crée une
 * nouvelle page sur la dernière), et la croix pour quitter le carnet.
 */
@Composable
private fun PageNavBar(vm: EditorViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val showPages = !vm.infinite || vm.pages.size > 1
    Row(
        modifier
            .background(Paper)
            .border(1.5.dp, Ink),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showPages) {
            val last = vm.index >= vm.pages.lastIndex
            NavIcon(R.drawable.ic_prev, tr("Page précédente", "Previous page"), { vm.goTo(vm.index - 1) }, enabled = vm.index > 0)
            EText(
                "${vm.index + 1} / ${vm.pages.size.coerceAtLeast(1)}",
                bold = true,
                size = 22.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            NavIcon(
                if (last) R.drawable.ic_page_add else R.drawable.ic_next,
                if (last) tr("Nouvelle page", "New page") else tr("Page suivante", "Next page"),
                vm::nextOrNewPage,
            )
            Spacer(Modifier.width(1.5.dp).height(44.dp).background(Ink))
        }
        NavIcon(R.drawable.ic_close, tr("Fermer le carnet", "Close notebook"), onClose)
    }
}

@Composable
private fun NavIcon(icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .size(60.dp)
            .tap(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(icon),
            contentDescription = description,
            colorFilter = ColorFilter.tint(if (enabled) Ink else Muted),
            modifier = Modifier.size(34.dp),
        )
    }
}
