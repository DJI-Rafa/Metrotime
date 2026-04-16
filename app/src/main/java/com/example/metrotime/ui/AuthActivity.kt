package com.example.metrotime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.metrotime.ui.theme.MetrotimeTheme

class AuthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MetrotimeTheme {
                AuthNavigation()
            }
        }
    }
}
