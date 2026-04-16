package com.example.metrotime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavController
import com.example.metrotime.data.UserPreferencesRepository
import com.example.metrotime.ui.theme.AppBackgrounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun SplashScreen(navController: NavController) {
    val context = LocalContext.current
    val userPrefs = UserPreferencesRepository(context)

    LaunchedEffect(key1 = true) {
        delay(750) // Minimum splash screen time

        val userEmail = userPrefs.loggedInUserEmail.first()
        val destination = if (userEmail != null) Routes.HOME else Routes.LOGIN

        navController.navigate(destination) {
            popUpTo(Routes.SPLASH) { inclusive = true }
        }
    }

    Splash()
}

@Composable
fun Splash() {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AppBackgrounds.splash?.let {
            Image(
                painter = painterResource(id = it),
                contentDescription = "Background Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
