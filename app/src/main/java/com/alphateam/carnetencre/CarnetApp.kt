package com.alphateam.carnetencre

import android.app.Application
import com.alphateam.carnetencre.claude.ClaudeClient
import com.alphateam.carnetencre.claude.ModeConfig
import com.alphateam.carnetencre.data.AppDatabase
import com.alphateam.carnetencre.data.AppSettings
import com.alphateam.carnetencre.data.NotesRepository
import com.alphateam.carnetencre.page.OnyxPen
import com.alphateam.carnetencre.render.InkText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.lsposed.hiddenapibypass.HiddenApiBypass

class CarnetApp : Application() {
    lateinit var repository: NotesRepository
        private set
    lateinit var settings: AppSettings
        private set
    private lateinit var modesFr: ModeConfig
    private lateinit var modesEn: ModeConfig

    /** Modes de Claude dans la langue courante (mêmes identifiants dans les deux langues). */
    val modes: ModeConfig get() = if (Lang.english) modesEn else modesFr
    val claude: ClaudeClient by lazy { ClaudeClient() }

    /** Portée de l'application : les sauvegardes continuent même si l'écran est quitté. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (OnyxPen.isOnyxDevice()) {
            // Le SDK Onyx appelle des API système cachées (Android 11+).
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (_: Throwable) {
            }
        }
        InkText.init(this)
        repository = NotesRepository(AppDatabase.create(this))
        settings = AppSettings(this)
        Lang.apply(settings.language)
        modesFr = ModeConfig.load(this)
        modesEn = ModeConfig.load(this, "modes-en.json")
    }
}
