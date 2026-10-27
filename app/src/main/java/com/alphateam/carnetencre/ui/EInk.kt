package com.alphateam.carnetencre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.alphateam.carnetencre.R
import com.alphateam.carnetencre.tr

// Interface e-ink : noir sur blanc, bleu pour ce qui vient de Claude, aucune animation ni ombre.
// Les états actifs sont signalés par l'inversion des couleurs.

val Ink = Color.Black
val Paper = Color.White
val ClaudeBlue = Color(0xFF1B4EA3)
val Muted = Color(0xFF6B6B6B)

val UiFont = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)
val SerifFont = FontFamily(Font(R.font.literata))

/** Clic sans effet d'ondulation (inutile, voire gênant, sur encre électronique). */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
fun EText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 18.sp,
    bold: Boolean = false,
    color: Color = Ink,
    font: FontFamily = UiFont,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = font,
            lineHeight = lineHeight,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Bouton e-ink : cadre noir, cible ≥ 48 dp, couleurs inversées quand il est actif. */
@Composable
fun EButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    accent: Color = Ink,
    bold: Boolean = false,
    minWidth: Dp = 48.dp,
) {
    val line = if (enabled) accent else Muted
    Box(
        modifier
            .defaultMinSize(minWidth = minWidth, minHeight = 48.dp)
            .border(1.5.dp, line)
            .background(if (active) accent else Paper)
            .tap(enabled, onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        EText(text, color = if (active) Paper else line, bold = bold || active, maxLines = 1)
    }
}

@Composable
fun EToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .tap { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .border(2.dp, Ink)
                .background(if (checked) Ink else Paper),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) EText("✓", color = Paper, bold = true)
        }
        Spacer(Modifier.width(12.dp))
        EText(label)
    }
}

@Composable
fun ETextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    singleLine: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .border(1.5.dp, Ink)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        singleLine = singleLine,
        textStyle = TextStyle(color = Ink, fontSize = 18.sp, fontFamily = UiFont),
        cursorBrush = SolidColor(Ink),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) EText(placeholder, color = Muted, maxLines = 1)
                inner()
            }
        },
    )
}

/**
 * Menu sans animation, ancré au Box qui le contient : par défaut sous le bouton ; avec
 * [alignment] et [offset], à côté d'un bouton de la barre latérale.
 */
@Composable
fun EMenu(
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.TopStart,
    offset: DpOffset = DpOffset(0.dp, 52.dp),
    content: @Composable () -> Unit,
) {
    val px = with(LocalDensity.current) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    Popup(
        alignment = alignment,
        offset = px,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .widthIn(min = 220.dp, max = 420.dp)
                .background(Paper)
                .border(2.dp, Ink)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

@Composable
fun EMenuItem(text: String, onClick: () -> Unit, active: Boolean = false, enabled: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .background(if (active) Ink else Paper)
            .tap(enabled, onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        EText(text, color = if (active) Paper else if (enabled) Ink else Muted, bold = active)
    }
}

@Composable
fun EDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 560.dp)
                .background(Paper)
                .border(2.dp, Ink)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EText(title, size = 22.sp, bold = true)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                buttons()
            }
        }
    }
}

@Composable
fun EConfirm(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    EDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            EButton(tr("Annuler", "Cancel"), onDismiss)
            EButton(confirm, { onConfirm(); onDismiss() }, active = true)
        },
    ) {
        EText(message)
    }
}
