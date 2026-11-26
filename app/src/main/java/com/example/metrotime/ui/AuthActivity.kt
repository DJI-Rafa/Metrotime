package com.example.metrotime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.metrotime.ui.theme.MetrotimeTheme
import java.io.File

class AuthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Versões antigas guardavam palavras-passe em texto simples neste DataStore; já não é usado.
        File(filesDir, "datastore/user_prefs.preferences_pb").delete()

        // A verificação de atualizações é feita no ecrã principal (aqui o diálogo ficava preso a
        // uma Activity que fecha logo a seguir).

        setContent {
            MetrotimeTheme {
                AuthNavigation()
            }
        }
    }
}
