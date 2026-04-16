package com.example.metrotime.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.metrotime.data.UserPreferencesRepository
import kotlinx.coroutines.launch

@Composable
fun ResetPasswordScreen(navController: NavController, email: String) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val context = LocalContext.current
    val userPrefs = UserPreferencesRepository(context)
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Redefinir Palavra-passe", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))

        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text("Nova palavra-passe") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Confirmar nova palavra-passe") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    val oldPassword = userPrefs.getPasswordForEmail(email)
                    if (newPassword == oldPassword) {
                        Toast.makeText(context, "Não pode usar uma palavra-passe antiga.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    if (newPassword.isNotBlank() && newPassword == confirmPassword) {
                        userPrefs.saveNewUser(email, newPassword) // This will overwrite the old password
                        Toast.makeText(context, "Palavra-passe redefinida com sucesso!", Toast.LENGTH_SHORT).show()
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(Routes.FORGOT_PASSWORD) { inclusive = true }
                        }
                    } else {
                        Toast.makeText(context, "As palavras-passe não coincidem.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Redefinir")
        }
    }
}
