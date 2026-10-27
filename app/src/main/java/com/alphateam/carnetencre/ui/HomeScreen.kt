package com.alphateam.carnetencre.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alphateam.carnetencre.CarnetApp
import com.alphateam.carnetencre.Lang
import com.alphateam.carnetencre.tr
import com.alphateam.carnetencre.data.Notebook
import com.alphateam.carnetencre.export.AutoExport
import android.graphics.RectF
import com.alphateam.carnetencre.model.PAGE_H
import com.alphateam.carnetencre.model.PAGE_W
import com.alphateam.carnetencre.render.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

class HomeViewModel(private val app: CarnetApp) : ViewModel() {
    private val repo = app.repository
    val notebooks = repo.observeNotebooks().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Miniatures de la première page, indexées par (id, date de modification). */
    val thumbnails = mutableStateMapOf<Pair<Long, Long>, ImageBitmap>()

    fun loadThumbnail(nb: Notebook) {
        val key = nb.id to nb.updatedAt
        if (key in thumbnails) return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val first = repo.pages(nb.id).firstOrNull() ?: return@withContext null
                // Miniature : le haut de la page, au format 3:4, même si la page s'est allongée.
                PageRenderer.renderBitmap(repo.loadElements(first.id), RectF(0f, 0f, PAGE_W, PAGE_H), 420)
            } ?: return@launch
            thumbnails.keys.filter { it.first == nb.id }.forEach { thumbnails.remove(it) }
            thumbnails[key] = bmp.asImageBitmap()
        }
    }

    fun create(title: String, infinite: Boolean, onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(repo.createNotebook(title.trim().ifEmpty { defaultTitle() }, infinite)) }
    }

    fun rename(nb: Notebook, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repo.renameNotebook(nb, title.trim()) }
    }

    fun delete(nb: Notebook) {
        viewModelScope.launch {
            repo.deleteNotebook(nb)
            AutoExport.remove(app, nb.id)
        }
    }

    fun hasApiKey() = app.settings.apiKey.isNotBlank()

    companion object {
        fun defaultTitle(): String =
            tr("Carnet du ", "Notebook — ") + SimpleDateFormat(tr("d MMMM yyyy", "MMMM d, yyyy"), Lang.locale).format(Date())
    }
}

@Composable
fun HomeScreen(app: CarnetApp, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val vm: HomeViewModel = viewModel(key = "accueil") { HomeViewModel(app) }
    val notebooks by vm.notebooks.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Notebook?>(null) }
    var deleting by remember { mutableStateOf<Notebook?>(null) }
    val hasKey = remember(notebooks) { vm.hasApiKey() }

    Column(Modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EText("Plume", size = 30.sp, bold = true, modifier = Modifier.weight(1f))
            EButton(tr("+ Nouveau carnet", "+ New notebook"), { creating = true }, active = true)
            Spacer(Modifier.padding(4.dp))
            EButton(tr("Réglages", "Settings"), onSettings)
        }
        Spacer(Modifier.height(12.dp))
        if (!hasKey) {
            Box(Modifier.fillMaxWidth().border(1.5.dp, ClaudeBlue).padding(12.dp)) {
                EText(
                    tr("Pour utiliser Claude, ajoutez votre clé API Anthropic dans les Réglages. ", "To use Claude, add your Anthropic API key in Settings. ") +
                        tr("L'écriture et les carnets fonctionnent sans réseau.", "Writing and notebooks work offline."),
                    color = ClaudeBlue,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        val list = notebooks
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EText(tr("Aucun carnet. Créez-en un avec « + Nouveau carnet ».", "No notebooks yet. Create one with \"+ New notebook\"."), color = Muted)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(200.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { nb ->
                    LaunchedEffect(nb.id, nb.updatedAt) { vm.loadThumbnail(nb) }
                    NotebookCard(
                        nb,
                        vm.thumbnails[nb.id to nb.updatedAt],
                        onOpen = { onOpen(nb.id) },
                        onMore = { editing = nb },
                    )
                }
            }
        }
    }

    if (creating) {
        NewNotebookDialog(
            onConfirm = { title, infinite -> vm.create(title, infinite, onOpen) },
            onDismiss = { creating = false },
        )
    }
    editing?.let { nb ->
        TitleDialog(
            title = tr("Carnet", "Notebook"),
            initial = nb.title,
            confirm = tr("Renommer", "Rename"),
            onConfirm = { vm.rename(nb, it) },
            onDismiss = { editing = null },
            extra = { EButton(tr("Supprimer…", "Delete…"), { editing = null; deleting = nb }) },
        )
    }
    deleting?.let { nb ->
        EConfirm(
            title = tr("Supprimer « ${nb.title} » ?", "Delete \"${nb.title}\"?"),
            message = tr("Toutes les pages et réponses de ce carnet seront supprimées définitivement.", "All pages and answers of this notebook will be permanently deleted."),
            confirm = tr("Supprimer", "Delete"),
            onConfirm = { vm.delete(nb) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun NotebookCard(nb: Notebook, thumb: ImageBitmap?, onOpen: () -> Unit, onMore: () -> Unit) {
    val date = remember(nb.updatedAt) { DateFormat.getDateInstance(DateFormat.MEDIUM, Lang.locale).format(Date(nb.updatedAt)) }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .border(1.5.dp, Ink)
                .background(Paper)
                .tap(onClick = onOpen),
        ) {
            if (thumb != null) {
                Image(thumb, contentDescription = nb.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).tap(onClick = onOpen)) {
                EText(nb.title, bold = true, maxLines = 2)
                EText((if (nb.infinite) tr("Page infinie · ", "Infinite page · ") else "") + tr("Modifié le ", "Edited ") + date, size = 14.sp, color = Muted)
            }
            EButton("⋯", onMore)
        }
    }
}

@Composable
private fun NewNotebookDialog(onConfirm: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(HomeViewModel.defaultTitle()) }
    var infinite by remember { mutableStateOf(false) }
    EDialog(
        title = tr("Nouveau carnet", "New notebook"),
        onDismiss = onDismiss,
        buttons = {
            EButton(tr("Annuler", "Cancel"), onDismiss)
            EButton(tr("Créer", "Create"), { onConfirm(text, infinite); onDismiss() }, active = true, enabled = text.isNotBlank())
        },
    ) {
        ETextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = tr("Titre du carnet", "Notebook title"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EButton("Pages", { infinite = false }, active = !infinite, modifier = Modifier.weight(1f))
            EButton(tr("Page infinie", "Infinite page"), { infinite = true }, active = infinite, modifier = Modifier.weight(1f))
        }
        EText(
            if (infinite) tr("Une seule page qui s'allonge vers le bas à mesure que vous écrivez.", "A single page that grows downward as you write.")
            else tr("Des pages successives : tourner à droite sur la dernière en ajoute une.", "One page after another: turning right on the last one adds a page."),
            color = Muted,
            size = 15.sp,
        )
    }
}

@Composable
private fun TitleDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    EDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            extra?.invoke()
            EButton(tr("Annuler", "Cancel"), onDismiss)
            EButton(confirm, { onConfirm(text); onDismiss() }, active = true, enabled = text.isNotBlank())
        },
    ) {
        ETextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = tr("Titre du carnet", "Notebook title"))
    }
}
