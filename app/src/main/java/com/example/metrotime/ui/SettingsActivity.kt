package com.example.metrotime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.UserPreferencesRepository
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+ (required for lock screen notifications)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    SettingsScreen(this@SettingsActivity)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(activity: ComponentActivity) {
    val userPrefs = remember { UserPreferencesRepository(activity) }
    val loggedInEmail by userPrefs.loggedInUserEmail.collectAsState(initial = null)
    val initials = loggedInEmail?.take(1)?.uppercase() ?: ""
    val coroutineScope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Metrotime", color = Color.White) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = DarkBackground
            ),
            actions = {
                Box {
                    if (initials.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Red40)
                                .clickable { showMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Logout") },
                            onClick = {
                                showMenu = false
                                coroutineScope.launch {
                                    userPrefs.logoutUser()
                                    val intent = Intent(activity, AuthActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                    activity.startActivity(intent)
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { /* Settings action */ }) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                "Escolha uma linha:",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            LineButton("Linha Azul", LineAzul) {
                activity.startActivity(LineStationsActivity.newIntent(activity, "Azul"))
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            LineButton("Linha Amarela", LineAmarela) {
                activity.startActivity(LineStationsActivity.newIntent(activity, "Amarela"))
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            LineButton("Linha Verde", LineVerde) {
                activity.startActivity(LineStationsActivity.newIntent(activity, "Verde"))
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            LineButton("Linha Vermelha", LineVermelha) {
                activity.startActivity(LineStationsActivity.newIntent(activity, "Vermelha"))
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BottomActionButton("Live Map", "🗺️") {
                    activity.startActivity(
                        android.content.Intent(activity, LiveMapActivity::class.java)
                    )
                }
                BottomActionButton("Favoritos", "⭐") { }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun LineButton(text: String, containerColor: Color, textColor: Color = Color.White, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(65.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BottomActionButton(text: String, emoji: String, onClick: () -> Unit = {}) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(150.dp)
            .height(80.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text = emoji, fontSize = 28.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}
