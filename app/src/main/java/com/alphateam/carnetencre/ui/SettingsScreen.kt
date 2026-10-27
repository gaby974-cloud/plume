package com.alphateam.carnetencre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphateam.carnetencre.CarnetApp
import com.alphateam.carnetencre.Lang
import com.alphateam.carnetencre.tr
import com.alphateam.carnetencre.data.AppSettings
import com.alphateam.carnetencre.export.AutoExport
import com.alphateam.carnetencre.export.DriveSync
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(app: CarnetApp, onBack: () -> Unit, onHelp: () -> Unit) {
    val s = app.settings
    var savedKey by remember { mutableStateOf(s.apiKey) }
    var keyInput by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(s.model) }
    var language by remember { mutableStateOf(s.language) }
    var stylusOnly by remember { mutableStateOf(s.stylusOnly) }
    var toolbarLeft by remember { mutableStateOf(s.toolbarLeft) }
    var autoCopy by remember { mutableStateOf(s.autoPdfCopy) }
    var copying by remember { mutableStateOf(false) }
    var copyStatus by remember { mutableStateOf<String?>(null) }
    var driveMessage by remember { mutableStateOf<String?>(null) }
    var driveVersion by remember { mutableIntStateOf(0) }
    var pastedAddress by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeCount by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumeCount++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val usage = remember { s.monthUsage() }
    val locale = Lang.locale
    val nf = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val month = remember(locale) {
        YearMonth.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EButton(tr("‹ Retour", "‹ Back"), onBack)
            Spacer(Modifier.width(16.dp))
            EText(tr("Réglages", "Settings"), size = 28.sp, bold = true, modifier = Modifier.weight(1f))
            EButton(tr("Mode d'emploi", "User guide"), onHelp, active = true)
        }

        Section(tr("Langue", "Language"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((choice, label) in listOf(
                Lang.AUTO to tr("Automatique", "Automatic"),
                Lang.FRENCH to "Français",
                Lang.ENGLISH to "English",
            )) {
                EButton(label, { language = choice; s.language = choice }, active = language == choice)
            }
        }
        EText(
            tr(
                "Automatique : la langue de la liseuse. Claude répond dans la langue de vos notes.",
                "Automatic: the tablet's language. Claude replies in the language of your notes.",
            ),
            color = Muted,
            size = 15.sp,
        )

        Section(tr("Clé API Anthropic", "Anthropic API key"))
        if (!s.secureStorageAvailable) {
            EText(
                tr(
                    "Le stockage chiffré n'est pas disponible sur cet appareil : la clé ne peut pas être enregistrée.",
                    "Encrypted storage is not available on this device: the key cannot be saved.",
                ),
                bold = true,
            )
        }
        EText(
            if (savedKey.isBlank()) tr("Aucune clé enregistrée.", "No key saved.")
            else tr("Clé enregistrée : ", "Saved key: ") + "${savedKey.take(7)}…${savedKey.takeLast(4)}",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ETextField(
                keyInput,
                { keyInput = it },
                Modifier.weight(1f).widthIn(max = 520.dp),
                placeholder = "sk-ant-…",
                password = true,
            )
            Spacer(Modifier.width(8.dp))
            EButton(tr("Enregistrer", "Save"), {
                s.apiKey = keyInput
                savedKey = s.apiKey
                keyInput = ""
            }, enabled = keyInput.isNotBlank(), active = keyInput.isNotBlank())
            if (savedKey.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                EButton(tr("Effacer", "Clear"), {
                    s.apiKey = ""
                    savedKey = ""
                })
            }
        }
        EText(
            tr(
                "Créez une clé sur console.anthropic.com. Elle est stockée chiffrée sur la liseuse et " +
                    "n'est envoyée qu'à api.anthropic.com.",
                "Create a key at console.anthropic.com. It is stored encrypted on the tablet and " +
                    "only ever sent to api.anthropic.com.",
            ),
            color = Muted,
            size = 15.sp,
        )

        Section(tr("Modèle", "Model"))
        for (m in AppSettings.MODELS) {
            EButton(
                m.label,
                { model = m.id; s.model = m.id },
                active = model == m.id,
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            )
        }

        Section(tr("Écriture", "Writing"))
        EToggle(tr("Stylet seul (le doigt n'écrit pas)", "Stylus only (fingers don't write)"), stylusOnly, { stylusOnly = it; s.stylusOnly = it })
        EText(
            tr(
                "Au doigt : toucher à deux doigts pour annuler, à trois pour rétablir, " +
                    "balayer à gauche ou à droite pour changer de page, glisser vers le haut ou le bas pour faire défiler.",
                "With fingers: two-finger tap to undo, three-finger tap to redo, " +
                    "swipe left or right to change page, drag up or down to scroll.",
            ),
            color = Muted,
            size = 15.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EText(tr("Barre d'outils :", "Toolbar:"))
            EButton(tr("À gauche", "Left"), { toolbarLeft = true; s.toolbarLeft = true }, active = toolbarLeft)
            EButton(tr("À droite", "Right"), { toolbarLeft = false; s.toolbarLeft = false }, active = !toolbarLeft)
        }

        Section(tr("Google Drive et copies PDF", "Google Drive and PDF copies"))
        EToggle(
            tr("Copie PDF de chaque carnet en quittant le carnet", "PDF copy of each notebook when you leave it"),
            autoCopy,
            { autoCopy = it; s.autoPdfCopy = it },
        )
        EText(
            tr(
                "Chaque carnet est copié en PDF dans ${AutoExport.FOLDER} sur la liseuse et, si Google Drive est connecté, " +
                    "dans le dossier « Plume » de votre Drive. Fichiers : « Titre (n°).pdf », remplacés à chaque mise à jour.",
                "Each notebook is copied as a PDF to ${AutoExport.FOLDER} on the tablet and, if Google Drive is connected, " +
                    "to the \"Plume\" folder of your Drive. Files: \"Title (no.).pdf\", replaced on every update.",
            ),
            color = Muted,
            size = 15.sp,
        )
        // La connexion tourne dans la portée de l'application : elle continue pendant que le
        // navigateur est au premier plan, et son résultat s'affiche au retour.
        val login by DriveSync.login.collectAsState()
        val connected = remember(resumeCount, driveVersion, login) { DriveSync.isConnected(context) }
        val driveEmail = remember(resumeCount, driveVersion, login) { DriveSync.email(context) }
        val driveLast = remember(resumeCount, driveVersion, login) { DriveSync.lastStatus(context) }
        when {
            !DriveSync.isConfigured -> EText(
                tr(
                    "Google Drive : identifiant Google à configurer avant la compilation (voir le README).",
                    "Google Drive: a Google client ID must be set before building (see the README).",
                ),
                color = Muted,
                size = 15.sp,
            )
            connected -> {
                EText(tr("Google Drive connecté : ", "Google Drive connected: ") + (driveEmail ?: tr("compte Google", "Google account")), bold = true)
                if (login is DriveSync.Login.Done) {
                    EText(
                        tr(
                            "Connexion réussie. Touchez « Envoyer tous les carnets maintenant » pour une première copie.",
                            "Connected. Tap \"Upload all notebooks now\" for a first copy.",
                        ),
                    )
                }
                driveLast?.let { EText(it, color = Muted, size = 15.sp) }
                EButton(tr("Déconnecter Google Drive", "Disconnect Google Drive"), {
                    scope.launch {
                        DriveSync.disconnect(context)
                        driveMessage = tr(
                            "Google Drive déconnecté. Les copies déjà envoyées restent dans votre Drive.",
                            "Google Drive disconnected. Copies already uploaded stay in your Drive.",
                        )
                        driveVersion++
                    }
                })
            }
            else -> {
                EText(tr("Google Drive n'est pas connecté.", "Google Drive is not connected."), bold = true)
                EButton(
                    if (login is DriveSync.Login.Waiting) tr("Rouvrir la page Google", "Reopen the Google page")
                    else tr("Connecter Google Drive", "Connect Google Drive"),
                    {
                        driveMessage = null
                        DriveSync.startLogin(context, app.appScope)
                    },
                    active = true,
                )
                when (val l = login) {
                    DriveSync.Login.Waiting -> EText(
                        tr(
                            "Connectez-vous dans la page Google qui s'est ouverte. À la fin, touchez « Revenir à Plume ».",
                            "Sign in on the Google page that opened. When done, tap \"Back to Plume\".",
                        ),
                        bold = true,
                    )
                    is DriveSync.Login.Failed -> EText(l.message, bold = true)
                    else -> {}
                }
                EText(
                    tr(
                        "La connexion ne revient pas dans Plume ? Dans le navigateur, copiez l'adresse de la dernière page " +
                            "(elle commence par http://127.0.0.1) et collez-la ici :",
                        "Plume didn't pick up the sign-in? In the browser, copy the address of the last page " +
                            "(it starts with http://127.0.0.1) and paste it here:",
                    ),
                    color = Muted,
                    size = 15.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ETextField(
                        pastedAddress,
                        { pastedAddress = it },
                        Modifier.weight(1f).widthIn(max = 560.dp),
                        placeholder = "http://127.0.0.1:…",
                    )
                    Spacer(Modifier.width(8.dp))
                    EButton(tr("Valider", "Confirm"), {
                        val address = pastedAddress
                        scope.launch {
                            if (DriveSync.completeFromAddress(context, address) is DriveSync.Login.Done) pastedAddress = ""
                        }
                    }, enabled = pastedAddress.isNotBlank(), active = pastedAddress.isNotBlank())
                }
            }
        }
        driveMessage?.let { EText(it, bold = true) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EButton(
                if (connected) tr("Envoyer tous les carnets maintenant", "Upload all notebooks now")
                else tr("Copier tous les carnets maintenant", "Copy all notebooks now"),
                {
                    copying = true
                    copyStatus = tr("Copie en cours…", "Copying…")
                    scope.launch {
                        val (local, drive) = AutoExport.publishAll(app, app.repository)
                        copyStatus = buildString {
                            append(tr("$local carnet(s) copié(s) dans ${AutoExport.FOLDER}", "$local notebook(s) copied to ${AutoExport.FOLDER}"))
                            if (connected) append(tr(", $drive envoyé(s) sur Google Drive", ", $drive uploaded to Google Drive"))
                        }
                        copying = false
                        driveVersion++
                    }
                },
                enabled = !copying,
            )
            copyStatus?.let { EText(it, bold = true) }
        }

        Section(tr("Consommation de ", "Usage for ") + month)
        EText(tr("Entrée : ", "Input: ") + "${nf.format(usage.first)} tokens")
        EText(tr("Sortie : ", "Output: ") + "${nf.format(usage.second)} tokens")
        EText(
            tr(
                "L'API est facturée à l'usage, en dehors de tout abonnement Claude (Pro, Max). " +
                    "Le détail des coûts est sur console.anthropic.com.",
                "The API is billed per use, separately from any Claude subscription (Pro, Max). " +
                    "Cost details are at console.anthropic.com.",
            ),
            color = Muted,
            size = 15.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Column {
        Spacer(Modifier.height(10.dp))
        EText(title, size = 21.sp, bold = true)
        Spacer(Modifier.fillMaxWidth().height(2.dp).background(Ink))
    }
}
