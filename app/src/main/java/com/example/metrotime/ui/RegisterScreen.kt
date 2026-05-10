package com.example.metrotime.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.metrotime.ui.theme.AppBackgrounds
import com.example.metrotime.ui.theme.Red40
import com.example.metrotime.ui.util.DateVisualTransformation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(navController: NavController) {
    var fullName by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val context = LocalContext.current
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

        // Overlay for legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.6f)),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                contentAlignment = Alignment.TopCenter
            ) {
                Card(
                    modifier = Modifier.padding(top = 40.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp)
                            .padding(top = 40.dp), // Space for logo if we add one
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Criar Conta",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.Black,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )

                        val textFieldColors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00529B),
                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            cursorColor = Color(0xFF00529B),
                            focusedLabelColor = Color(0xFF00529B),
                            unfocusedLabelColor = Color.Gray
                        )

                        OutlinedTextField(
                            value = fullName,
                            onValueChange = { fullName = it },
                            label = { Text("Nome completo") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
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
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Palavra-passe") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("Confirmar palavra-passe") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                if (fullName.isBlank() || email.isBlank() || password.isBlank() || birthDate.length != 8) {
                                    Toast.makeText(context, "Por favor preencha todos os campos corretamente.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (password != confirmPassword) {
                                    Toast.makeText(context, "As palavras-passe não coincidem.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                
                                FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password)
                                    .addOnCompleteListener { task ->
                                        if (task.isSuccessful) {
                                            val userId = task.result?.user?.uid
                                            if (userId != null) {
                                                val userMap = mapOf(
                                                    "name" to fullName.trim(),
                                                    "birthDate" to birthDate,
                                                    "email" to email.trim()
                                                )
                                                val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                                db.reference.child("users").child(userId).setValue(userMap)
                                            }
                                            Toast.makeText(context, "Conta criada com sucesso!", Toast.LENGTH_SHORT).show()
                                            navController.navigate(Routes.LOGIN) {
                                                popUpTo(Routes.REGISTER) { inclusive = true }
                                            }
                                        } else {
                                            Toast.makeText(context, "Erro: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00529B))
                        ) {
                            Text("Criar conta", color = Color.White)
                        }
                    }
                }

                // Overlapping Logo
                Image(
                    painter = painterResource(id = com.example.metrotime.R.drawable.metro_logo),
                    contentDescription = "Metro Logo",
                    modifier = Modifier.size(100.dp)
                )
            }
        }
    }
}
