package com.alphateam.carnetencre.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.alphateam.carnetencre.R
import com.alphateam.carnetencre.Lang
import com.alphateam.carnetencre.tr
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class HelpTopic(val title: String, val lines: List<String>)

/** Mode d'emploi de Plume, rubrique par rubrique. */
private val TOPICS_FR = listOf(
    HelpTopic(
        "Premiers pas",
        listOf(
            "Dans les Réglages, collez votre clé API Anthropic (à créer sur console.anthropic.com) puis touchez Enregistrer.",
            "Modèle : Sonnet 5.5 par défaut ; Opus 5.5 plus fin, Haiku 5.5 plus économique.",
            "L'API est facturée à l'usage, en dehors de tout abonnement Claude : le compteur du mois est en bas des Réglages.",
            "« Stylet seul » : le stylet écrit, les doigts servent aux menus et aux gestes.",
        ),
    ),
    HelpTopic(
        "Les carnets",
        listOf(
            "Accueil → + Nouveau carnet : choisissez un titre, puis « Pages » ou « Page infinie ».",
            "Pages : des pages successives. En haut à droite, ‹ et › changent de page ; sur la dernière, › crée une nouvelle page.",
            "Page infinie : une seule page qui s'allonge vers le bas quand vous écrivez.",
            "✕ en haut à droite quitte le carnet (il est enregistré automatiquement).",
            "Renommer : dans le carnet, Menu → touchez le nom du carnet (✎). Depuis l'accueil : ⋯ sous le carnet.",
        ),
    ),
    HelpTopic(
        "Écrire et effacer",
        listOf(
            "Trois stylos favoris en haut de la barre : un appui choisit le stylo.",
            "Un nouvel appui (ou un appui long) sur le favori actif le règle : stylo ou surligneur, couleur, épaisseur.",
            "Le surligneur passe sous l'encre, sans la masquer.",
            "Gomme : efface le trait entier touché. Le bout gomme du stylet efface aussi.",
            "Raturer : gribouillez un mot en zigzag au stylo, il disparaît avec la rature.",
            "↶ et ↷ annulent et rétablissent, sans limite.",
        ),
    ),
    HelpTopic(
        "Zone et lasso",
        listOf(
            "Zone : tracez un cadre ; Claude ne lira que ce cadre. Un appui court le retire.",
            "Lasso : faites le tour complet des traits ou des réponses de Claude à sélectionner.",
            "Glissez dans le cadre en pointillés pour déplacer la sélection.",
            "La barre de sélection apparaît en haut ou en bas, à l'écart de la sélection : Couleur, Réduire, Agrandir, Dupliquer, Supprimer, et Claude (qui ne lit que la sélection).",
            "✕ ou un appui hors du cadre retire la sélection.",
        ),
    ),
    HelpTopic(
        "Gestes aux doigts",
        listOf(
            "Toucher à deux doigts : annuler. À trois doigts : rétablir.",
            "Balayer vers la gauche : page suivante (ou nouvelle page sur la dernière). Vers la droite : page précédente.",
            "Glisser vers le haut ou le bas : faire défiler une page plus haute que l'écran (aussi ˄ ˅ dans la barre).",
            "Appui long sur une réponse de Claude, puis glisser : la déplacer.",
        ),
    ),
    HelpTopic(
        "Demander à Claude",
        listOf(
            "Écrivez, puis touchez Claude dans la barre.",
            "Mode Auto : si la page contient une consigne (une ligne qui commence par → ou ->, @claude, un texte entouré, « Claude, … »), Claude l'exécute.",
            "Sans consigne, Auto transcrit votre écriture en texte.",
            "Exemples de consignes : → résume ; → traduis en anglais ; @claude corrige les calculs.",
            "Mode ▾ : Transcrire, Résumer, Continuer, Structurer, Schéma, Tableau à remplir, Calendrier, Vérifier.",
            "Pour qu'il ne lise qu'une partie, utilisez Zone ou Lasso avant de toucher Claude.",
        ),
    ),
    HelpTopic(
        "Le panneau de réponse",
        listOf(
            "La réponse s'écrit au fil de l'eau ; Arrêter la coupe, Relancer la redemande.",
            "‹ Préc. et Suiv. › tournent les pages de la réponse ; Agrandir l'affiche en plein écran.",
            "Question de suite : tapez-la en bas du panneau puis Envoyer.",
            "Poser sur la page : la réponse se pose en bleu sous votre texte (ou sous la zone). La page s'allonge si besoin, on ne change jamais de page.",
            "Copier met le texte de la réponse dans le presse-papiers.",
            "Menu → Historique des réponses : les 8 dernières réponses de la page.",
        ),
    ),
    HelpTopic(
        "Schémas, tableaux, calendriers",
        listOf(
            "Demandez un visuel : → calendrier de novembre ; → tableau pour suivre mes dépenses ; → schéma de ce plan.",
            "Ou choisissez le mode Schéma, Tableau à remplir ou Calendrier.",
            "Claude le dessine ; posé sur la page, il est en bleu et vous écrivez dans ses cases au stylet.",
            "Il se déplace, s'agrandit ou se supprime au lasso, et s'efface à la gomme.",
        ),
    ),
    HelpTopic(
        "Google Drive et copies PDF",
        listOf(
            "Réglages → Connecter Google Drive : connectez-vous dans la page Google qui s'ouvre, puis touchez « Revenir à Plume ».",
            "Si Google indique que l'application n'est pas validée : Paramètres avancés → Accéder à Plume.",
            "Si les Réglages disent encore « pas connecté » : copiez l'adresse de la dernière page du navigateur (http://127.0.0.1…) et collez-la dans les Réglages, puis Valider.",
            "Ensuite, chaque carnet est envoyé en PDF dans le dossier « Plume » de votre Drive quand vous le quittez.",
            "« Envoyer tous les carnets maintenant » envoie d'un coup tous les carnets.",
            "Une copie reste aussi sur la liseuse, dans Documents/Plume.",
            "Menu → Exporter la page (PNG) ou le carnet (PDF) pour un envoi ponctuel (Partager ou Enregistrer).",
        ),
    ),
    HelpTopic(
        "Astuces et problèmes",
        listOf(
            "Plus de place pour écrire : Masquer en haut de la barre ; « Outils » dans le coin la fait revenir.",
            "Barre à droite plutôt qu'à gauche : Réglages → Barre d'outils.",
            "Interface lente : dans les réglages Boox de l'application, choisissez le mode de rafraîchissement rapide.",
            "Pas de réseau : l'écriture et les carnets marchent hors ligne ; seuls Claude et Drive ont besoin du Wi-Fi.",
            "Message d'erreur de Claude : clé API invalide, limite atteinte ou service saturé ; le message dit quoi faire.",
        ),
    ),
)

/** The Plume user guide, topic by topic (English version of [TOPICS_FR]). */
private val TOPICS_EN = listOf(
    HelpTopic(
        "Getting started",
        listOf(
            "In Settings, paste your Anthropic API key (create one at console.anthropic.com), then tap Save.",
            "Model: Sonnet 5.5 by default; Opus 5.5 is sharper, Haiku 5.5 is cheaper.",
            "The API is billed per use, separately from any Claude subscription: this month's counter is at the bottom of Settings.",
            "\"Stylus only\": the stylus writes, fingers are for menus and gestures.",
        ),
    ),
    HelpTopic(
        "Notebooks",
        listOf(
            "Home → + New notebook: choose a title, then \"Pages\" or \"Infinite page\".",
            "Pages: one page after another. Top right, ‹ and › change page; on the last page, › adds a new one.",
            "Infinite page: a single page that grows downward as you write.",
            "✕ at the top right closes the notebook (it is saved automatically).",
            "Rename: in the notebook, Menu → tap the notebook name (✎). From Home: ⋯ under the notebook.",
        ),
    ),
    HelpTopic(
        "Writing and erasing",
        listOf(
            "Three favorite pens at the top of the toolbar: one tap picks the pen.",
            "Tap the active favorite again (or long-press it) to set it up: pen or highlighter, color, width.",
            "The highlighter goes under the ink, without hiding it.",
            "Eraser: erases the whole stroke it touches. The stylus eraser end works too.",
            "Scratch out: scribble over a word in a zigzag with the pen, and it disappears along with the scribble.",
            "↶ and ↷ undo and redo, without limit.",
        ),
    ),
    HelpTopic(
        "Area and lasso",
        listOf(
            "Area: draw a frame; Claude will only read inside it. A short tap removes it.",
            "Lasso: draw a full loop around strokes or Claude answers.",
            "The selection bar appears at the top or bottom, away from the selection: Color, Shrink, Enlarge, Duplicate, Delete, and Claude (which only reads the selection).",
            "Drag inside the dotted frame to move the selection.",
            "✕ or a tap outside the frame clears the selection.",
        ),
    ),
    HelpTopic(
        "Finger gestures",
        listOf(
            "Two-finger tap: undo. Three-finger tap: redo.",
            "Swipe left: next page (or a new page on the last one). Swipe right: previous page.",
            "Drag up or down: scroll a page taller than the screen (also ˄ ˅ in the toolbar).",
            "Long-press a Claude answer, then drag: move it.",
        ),
    ),
    HelpTopic(
        "Asking Claude",
        listOf(
            "Write, then tap Claude in the toolbar.",
            "Auto mode: if the page holds an instruction (a line starting with → or ->, @claude, circled text, \"Claude, …\"), Claude carries it out.",
            "Without an instruction, Auto transcribes your handwriting into text.",
            "Example instructions: → summarize; → translate into French; @claude check the math.",
            "Mode ▾: Transcribe, Summarize, Continue, Structure, Diagram, Table to fill in, Calendar, Check.",
            "To have it read only part of the page, use Area or Lasso before tapping Claude.",
        ),
    ),
    HelpTopic(
        "The answer panel",
        listOf(
            "The answer appears as it is written; Stop cuts it short, Retry asks again.",
            "‹ Prev and Next › turn the pages of the answer; Enlarge shows it full screen.",
            "Follow-up question: type it at the bottom of the panel, then Send.",
            "Place on page: the answer is placed in blue under your text (or under the area). The page grows if needed; it never changes page.",
            "Copy puts the answer text on the clipboard.",
            "Menu → Answer history: the last 8 answers for the page.",
        ),
    ),
    HelpTopic(
        "Diagrams, tables, calendars",
        listOf(
            "Ask for a visual: → November calendar; → table to track my expenses; → diagram of this outline.",
            "Or pick the Diagram, Table to fill in or Calendar mode.",
            "Claude draws it; once placed on the page it is blue and you write in its cells with the stylus.",
            "Move, enlarge or delete it with the lasso; the eraser removes it too.",
        ),
    ),
    HelpTopic(
        "Google Drive and PDF copies",
        listOf(
            "Settings → Connect Google Drive: sign in on the Google page that opens, then tap \"Back to Plume\".",
            "If Google says the app isn't verified: Advanced → Go to Plume.",
            "If Settings still says \"not connected\": copy the address of the browser's last page (http://127.0.0.1…), paste it in Settings, then Confirm.",
            "From then on, each notebook is uploaded as a PDF to the \"Plume\" folder of your Drive when you leave it.",
            "\"Upload all notebooks now\" uploads every notebook at once.",
            "A copy also stays on the tablet, in Documents/Plume.",
            "Menu → Export page (PNG) or notebook (PDF) for a one-off share (Share or Save).",
        ),
    ),
    HelpTopic(
        "Tips and troubleshooting",
        listOf(
            "More room to write: Hide at the top of the toolbar; \"Tools\" in the corner brings it back.",
            "Toolbar on the right instead of the left: Settings → Toolbar.",
            "Sluggish screen: in the Boox settings for the app, choose the fast refresh mode.",
            "No network: writing and notebooks work offline; only Claude and Drive need Wi-Fi.",
            "Claude error message: invalid API key, limit reached or service overloaded; the message says what to do.",
        ),
    ),
)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    var open by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EButton(tr("‹ Retour", "‹ Back"), onBack)
            Spacer(Modifier.width(16.dp))
            EText(tr("Mode d'emploi", "User guide"), size = 28.sp, bold = true)
        }
        EText(tr("Touchez une rubrique pour l'ouvrir.", "Tap a topic to open it."), color = Muted, size = 16.sp)
        Spacer(Modifier.height(4.dp))
        (if (Lang.english) TOPICS_EN else TOPICS_FR).forEachIndexed { i, topic ->
            val expanded = open == i
            Column(Modifier.fillMaxWidth().border(1.5.dp, Ink)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .background(if (expanded) Ink else Paper)
                        .tap { open = if (expanded) -1 else i }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EText(
                        topic.title,
                        size = 21.sp,
                        bold = true,
                        color = if (expanded) Paper else Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Image(
                        painterResource(if (expanded) R.drawable.ic_menu_mode else R.drawable.ic_next),
                        contentDescription = if (expanded) tr("Replier", "Collapse") else tr("Ouvrir", "Open"),
                        colorFilter = ColorFilter.tint(if (expanded) Paper else Ink),
                        modifier = Modifier.size(32.dp),
                    )
                }
                if (expanded) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (line in topic.lines) {
                            Row {
                                EText("•", size = 19.sp, modifier = Modifier.padding(end = 10.dp))
                                EText(line, size = 19.sp, lineHeight = 27.sp)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
