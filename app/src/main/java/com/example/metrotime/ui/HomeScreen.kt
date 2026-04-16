package com.example.metrotime.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController

@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current

    LaunchedEffect(key1 = true) {
        val intent = Intent(context, SettingsActivity::class.java)
        context.startActivity(intent)
        // Finish the AuthActivity so the user can't go back to it
        (context as? Activity)?.finish()
    }

    // You can keep a simple loading text while the transition happens
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("A entrar na aplicação...")
    }
}
