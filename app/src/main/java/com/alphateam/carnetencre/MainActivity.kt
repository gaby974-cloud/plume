package com.alphateam.carnetencre

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.alphateam.carnetencre.ui.EditorScreen
import com.alphateam.carnetencre.ui.HelpScreen
import com.alphateam.carnetencre.ui.HomeScreen
import com.alphateam.carnetencre.ui.Paper
import com.alphateam.carnetencre.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CarnetApp
        setContent { CarnetRoot(app) }
    }
}

/**
 * Navigation minimale : pile d'écrans encodée en texte pour survivre aux rotations.
 * Écrans : "accueil", "carnet:<id>", "reglages", "aide".
 */
@Composable
private fun CarnetRoot(app: CarnetApp) {
    var encoded by rememberSaveable { mutableStateOf("accueil") }
    val stack = encoded.split('|')
    val current = stack.last()
    fun push(screen: String) {
        encoded = "$encoded|$screen"
    }
    fun pop() {
        if (stack.size > 1) encoded = stack.dropLast(1).joinToString("|")
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize().background(Paper)) {
        when {
            current == "reglages" -> SettingsScreen(app, onBack = { pop() }, onHelp = { push("aide") })
            current == "aide" -> HelpScreen(onBack = { pop() })
            current.startsWith("carnet:") -> EditorScreen(
                app,
                notebookId = current.removePrefix("carnet:").toLong(),
                onHome = { encoded = "accueil" },
                onSettings = { push("reglages") },
            )
            else -> HomeScreen(app, onOpen = { push("carnet:$it") }, onSettings = { push("reglages") })
        }
    }
}
