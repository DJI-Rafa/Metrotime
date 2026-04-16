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
import androidx.compose.material.icons.Icons.Filled
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.example.metrotime.data.UserPreferencesRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(navController: NavController) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val userPrefs = UserPreferencesRepository(context)
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

                        // 5. Input Fields
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            trailingIcon = {
                                val image = if (passwordVisible) Filled.Visibility else Filled.VisibilityOff
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(image, contentDescription = "Toggle password visibility")
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // 6. Buttons and Texts
                        Button(
                            onClick = {
                                scope.launch {
                                    val storedPassword = userPrefs.getPasswordForEmail(email)
                                    if (storedPassword != null && storedPassword == password) {
                                        userPrefs.loginUser(email)
                                        navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                                    } else {
                                        Toast.makeText(context, "Email or password invalid.", Toast.LENGTH_SHORT).show()
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
                            Divider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.5f))
                            Text(" or ", color = Color.Gray.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = 8.dp))
                            Divider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.5f))
                        }

                        OutlinedButton(
                            onClick = { navController.navigate(Routes.REGISTER) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
                        ) {
                            Text("Sign up", color = Color(0xFF00529B))
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
