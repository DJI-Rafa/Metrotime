package com.example.metrotime.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth

/**
 * Recuperação de palavra-passe pelo Firebase Auth (as contas vivem no Firebase; antes procurava
 * uma palavra-passe guardada localmente, que nunca existia, e dizia sempre "email não registado").
 */
@Composable
fun ForgotPasswordScreen(navController: NavController) {
    var email by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Recuperar Palavra-passe", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val trimmed = email.trim()
                if (trimmed.isBlank()) {
                    Toast.makeText(context, "Indica o email da conta.", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                sending = true
                FirebaseAuth.getInstance().sendPasswordResetEmail(trimmed)
                    .addOnCompleteListener { task ->
                        sending = false
                        if (task.isSuccessful) {
                            Toast.makeText(context, "Enviámos um link para redefinir a palavra-passe. Verifica o email.", Toast.LENGTH_LONG).show()
                            navController.popBackStack()
                        } else {
                            Toast.makeText(context, "Não foi possível enviar o email: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
            },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (sending) "A enviar..." else "Enviar link")
        }
    }
}
