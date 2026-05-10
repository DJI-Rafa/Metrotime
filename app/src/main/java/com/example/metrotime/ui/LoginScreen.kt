package com.example.metrotime.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import com.example.metrotime.ui.theme.VisibilityIcon
import com.example.metrotime.ui.theme.VisibilityOffIcon
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.metrotime.R // Make sure you have your drawables here
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(navController: NavController) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Background Image
        Image(
            painter = painterResource(id = R.drawable.background_login),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Overlay for legibility
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

        // Main content
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // This box will contain the Card and the overlapping Logo
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .wrapContentHeight(),
                contentAlignment = Alignment.TopCenter
            ) {
                // 3. Central Card
                Card(
                    modifier = Modifier.padding(top = 60.dp), // Padding to make space for the logo
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 80.dp) // Top padding to clear logo
                    ) {

                        val textFieldColors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00529B),
                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            cursorColor = Color(0xFF00529B),
                            focusedLabelColor = Color(0xFF00529B),
                            unfocusedLabelColor = Color.Gray
                        )

                        // 5. Input Fields
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color.Gray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray) },
                            trailingIcon = {
                                val image = if (passwordVisible) VisibilityIcon else VisibilityOffIcon
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(image, contentDescription = "Toggle password visibility", tint = Color.Gray)
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            colors = textFieldColors
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // 6. Buttons and Texts
                        Button(
                            onClick = {
                                if (email.isBlank() || password.isBlank()) {
                                    Toast.makeText(context, "Por favor preencha todos os campos.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
                                    .addOnCompleteListener { task ->
                                        if (task.isSuccessful) {
                                            navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                                        } else {
                                            Toast.makeText(context, "Login falhou: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00529B)) // Strong Blue
                        ) {
                            Text("Log In", color = Color.White)
                        }

                        Text(
                            text = "Forgot password?",
                            color = Color(0xFF4C82C2), // Light Blue
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                                .clickable { navController.navigate(Routes.FORGOT_PASSWORD) },
                            textAlign = TextAlign.Center
                        )

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.5f))
                            Text(" ou ", color = Color.Gray.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = 8.dp))
                            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.5f))
                        }

                        // Google Login Button
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val credentialManager = CredentialManager.create(context)
                                        val googleIdOption = GetGoogleIdOption.Builder()
                                            .setFilterByAuthorizedAccounts(false)
                                            .setServerClientId("959630000425-itu10q9tdaoca7sfhnsufqqobniistds.apps.googleusercontent.com")
                                            .setAutoSelectEnabled(true)
                                            .build()

                                        val request = GetCredentialRequest.Builder()
                                            .addCredentialOption(googleIdOption)
                                            .build()

                                        val result = credentialManager.getCredential(context, request)
                                        val credential = result.credential
                                        
                                        if (credential is androidx.credentials.CustomCredential &&
                                            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                            
                                            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                                            
                                            FirebaseAuth.getInstance().signInWithCredential(firebaseCredential)
                                                .addOnCompleteListener { task ->
                                                    if (task.isSuccessful) {
                                                        val user = task.result?.user
                                                        if (user != null) {
                                                            // Save profile data to database
                                                            val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                                            val updates = mutableMapOf<String, Any>(
                                                                "email" to (user.email ?: ""),
                                                                "name" to (user.displayName ?: "Utilizador Google")
                                                            )
                                                            user.photoUrl?.let { updates["photoUrl"] = it.toString().replace("s96-c", "s400-c") } // Higher res image
                                                            
                                                            db.getReference("users").child(user.uid).updateChildren(updates)
                                                                .addOnCompleteListener { 
                                                                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                                                                }
                                                        }
                                                    } else {
                                                        Toast.makeText(context, "Erro: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                        }
                                    } catch (e: GetCredentialException) {
                                        Toast.makeText(context, "Erro Auth: ${e.type} - ${e.errorMessage}", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_google),
                                    contentDescription = "Google Logo",
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Continuar com o Google", color = Color(0xFF00529B), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { navController.navigate(Routes.REGISTER) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
                        ) {
                            Text("Criar nova conta", color = Color.Gray)
                        }
                    }
                }

                // 4. Overlapping Logo
                Image(
                    painter = painterResource(id = R.drawable.metro_logo),
                    contentDescription = "Metro Logo",
                    modifier = Modifier
                        .size(140.dp)
                )
            }
        }
    }
}
