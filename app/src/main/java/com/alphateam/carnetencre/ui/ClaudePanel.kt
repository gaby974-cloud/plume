package com.alphateam.carnetencre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.alphateam.carnetencre.claude.ReplyPart
import com.alphateam.carnetencre.claude.ReplyParts
import com.alphateam.carnetencre.render.Figures
import com.alphateam.carnetencre.tr
import kotlin.math.ceil

/**
 * Panneau de réponse en bas de l'écran (40 % de la hauteur, ou toute la hauteur une fois
 * agrandi). Le texte se lit page par page avec ‹ › plutôt qu'en défilant : sur encre
 * électronique, un défilement continu laisse des traînées.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClaudePanel(vm: EditorViewModel, maxHeight: Dp, fullHeight: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val live by vm.liveText.collectAsState()
    var question by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var lastReplyTop by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val overlap = with(LocalDensity.current) { 30.dp.roundToPx() }
    val hasReply = vm.latestReply() != null

    // Pendant l'écriture on suit la fin du texte ; une fois finie, on revient au début de la réponse.
    LaunchedEffect(live, vm.streaming, vm.panelItems.size, vm.panelError) {
        if (vm.streaming || vm.panelError != null) {
            scroll.scrollTo(scroll.maxValue)
        } else {
            scroll.scrollTo(lastReplyTop.coerceAtMost(scroll.maxValue))
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = if (expanded) fullHeight else maxHeight)
            .background(Paper)
            .border(2.dp, ClaudeBlue)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EText(
                when {
                    vm.streaming -> tr("Claude écrit…", "Claude is writing…")
                    vm.panelReadOnly -> tr("Réponse archivée", "Saved answer")
                    else -> "Claude"
                },
                color = ClaudeBlue,
                bold = true,
                size = 20.sp,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
            )
            if (vm.streaming) {
                EButton(tr("Arrêter", "Stop"), vm::stopStream, accent = ClaudeBlue)
            } else if (!vm.panelReadOnly) {
                EButton(tr("Relancer", "Retry"), vm::relaunch, accent = ClaudeBlue)
            }
            EButton(tr("Copier", "Copy"), { vm.copyReply(context) }, enabled = hasReply && !vm.streaming, accent = ClaudeBlue)
            EButton(tr("Poser sur la page", "Place on page"), vm::poseReply, enabled = hasReply && !vm.streaming, active = hasReply && !vm.streaming, accent = ClaudeBlue)
            EButton(if (expanded) tr("Réduire", "Shrink") else tr("Agrandir", "Enlarge"), { expanded = !expanded })
            EButton(tr("Fermer", "Close"), vm::closePanel)
        }

        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(scroll, enabled = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val lastReply = vm.panelItems.indexOfLast { it is PanelItem.Reply }
            vm.panelItems.forEachIndexed { i, item ->
                when (item) {
                    is PanelItem.Sent -> EText(
                        (if (item.zone) tr("Zone envoyée", "Area sent") else tr("Page envoyée", "Page sent")) + " · mode ${item.modeLabel}",
                        color = Muted,
                        size = 15.sp,
                    )
                    is PanelItem.Question -> EText(tr("Vous : ", "You: ") + item.text, bold = true)
                    is PanelItem.Reply -> ReplyView(
                        item.text,
                        modifier = if (i == lastReply) {
                            Modifier.onGloballyPositioned { lastReplyTop = it.positionInParent().y.toInt() }
                        } else {
                            Modifier
                        },
                    )
                }
            }
            if (vm.streaming) {
                EText(live.ifEmpty { "…" }, font = SerifFont, color = ClaudeBlue, size = 19.sp, lineHeight = 27.sp)
            }
            vm.panelError?.let { EText(it, bold = true) }
        }

        if (scroll.maxValue > 0 && !vm.streaming) {
            val step = (scroll.viewportSize - overlap).coerceAtLeast(1)
            val pages = 1 + ceil(scroll.maxValue / step.toFloat()).toInt()
            val current = if (scroll.value >= scroll.maxValue) pages else 1 + scroll.value / step
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EButton(tr("‹ Préc.", "‹ Prev"), { scope.launch { scroll.scrollTo(scroll.value - step) } }, enabled = scroll.value > 0)
                EText("$current / $pages", bold = true)
                EButton(tr("Suiv. ›", "Next ›"), { scope.launch { scroll.scrollTo(scroll.value + step) } }, enabled = scroll.value < scroll.maxValue)
            }
        }

        if (!vm.panelReadOnly) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val send = {
                    vm.followUp(question)
                    question = ""
                }
                ETextField(
                    question,
                    { question = it },
                    Modifier.weight(1f),
                    placeholder = tr("Question de suite…", "Follow-up question…"),
                    onDone = send,
                )
                Spacer(Modifier.width(8.dp))
                EButton(tr("Envoyer", "Send"), send, enabled = question.isNotBlank() && !vm.streaming && vm.panelItems.isNotEmpty())
            }
        }
    }
}

/** Une réponse : paragraphes en encre bleue et aperçu des dessins (schéma, tableau, calendrier). */
@Composable
private fun ReplyView(raw: String, modifier: Modifier = Modifier) {
    val parts = remember(raw) { ReplyParts.split(raw) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (part in parts) {
            when (part) {
                is ReplyPart.Text -> EText(part.text, font = SerifFont, color = ClaudeBlue, size = 19.sp, lineHeight = 27.sp)
                is ReplyPart.Svg -> {
                    val bitmap = remember(part.svg) { Figures.preview(part.svg, PREVIEW_WIDTH_PX)?.asImageBitmap() }
                    if (bitmap != null) {
                        Image(
                            bitmap,
                            contentDescription = tr("Dessin de Claude", "Claude drawing"),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, ClaudeBlue),
                        )
                    } else {
                        EText(tr("[dessin illisible]", "[unreadable drawing]"), color = Muted)
                    }
                }
            }
        }
    }
}

private const val PREVIEW_WIDTH_PX = 1000
