package com.example.metrotime.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.metrotime.data.UserPreferencesRepository
import com.example.metrotime.ui.theme.AppBackgrounds
import com.example.metrotime.ui.theme.Red40
import com.example.metrotime.ui.util.DateVisualTransformation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(navController: NavController) {
    var fullName by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val context = LocalContext.current
    val userPrefs = UserPreferencesRepository(context)
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary)
    ) {
        AppBackgrounds.register?.let {
            Image(
                painter = painterResource(id = it),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent),
                        startY = Float.POSITIVE_INFINITY,
                        endY = 0f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Criar Conta", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))

            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                label = { Text("Nome completo") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = birthDate,
                onValueChange = { 
                    if (it.length <= 8 && it.all { char -> char.isDigit() }) {
                        birthDate = it
                    }
                },
                label = { Text("Data de nascimento (DDMMAAAA)") },
                visualTransformation = DateVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Palavra-passe") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirmar palavra-passe") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        val existingPassword = userPrefs.getPasswordForEmail(email)
                        if (existingPassword != null) {
                            Toast.makeText(context, "Este email já está registado.", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        if (fullName.isNotBlank() && email.isNotBlank() && password.isNotBlank() && password == confirmPassword && birthDate.length == 8) {
                            userPrefs.saveNewUser(email, password)
                            Toast.makeText(context, "Conta criada com sucesso!", Toast.LENGTH_SHORT).show()
                            navController.navigate(Routes.LOGIN) {
                                popUpTo(Routes.REGISTER) { inclusive = true }
                            }
                        } else {
                            Toast.makeText(context, "Por favor, preencha todos os campos corretamente.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(contentColor = Red40)
            ) {
                Text("Criar conta", color = Color.White)
            }
        }
    }
}
