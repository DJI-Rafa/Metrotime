package com.example.metrotime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
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
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import coil.compose.AsyncImage
import com.example.metrotime.data.UserPreferencesRepository
import com.example.metrotime.data.cp.*
import com.example.metrotime.data.history.PendingDetection
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+ (required for lock screen notifications)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        // Initialize CpRouteRepository and CpGtfsRepository early in the background
        CpRouteRepository.initialize(applicationContext)
        com.example.metrotime.data.cp.CpGtfsRepository.initialize(applicationContext)

        // Start background service immediately (for admin messages, location sharing, etc.)
        val serviceIntent = android.content.Intent(this, com.example.metrotime.work.WidgetUpdateService::class.java).apply {
            action = com.example.metrotime.work.WidgetUpdateService.ACTION_FETCH_DATA
        }
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)

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
    val currentUser = FirebaseAuth.getInstance().currentUser
    val loggedInEmail = currentUser?.email
    val initials = loggedInEmail?.take(1)?.uppercase() ?: ""
    val coroutineScope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var showAdminDialog by remember { mutableStateOf(false) }
    var showPendingDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf("Metro") }

    var photoUrl by remember { mutableStateOf<String?>(null) }
    var photoBase64 by remember { mutableStateOf<String?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    
    var pendingDetections by remember { mutableStateOf(TripHistoryManager.getReadyDetections(activity)) }
    var globalAlertText by remember { mutableStateOf<String?>(null) }
    var appUpdateInfo by remember { mutableStateOf<com.example.metrotime.utils.AppUpdater.UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
        
        // Verificação de atualização
        db.getReference("app_update").get().addOnSuccessListener { snapshot ->
            try {
                val vCode = snapshot.child("versionCode").getValue(Int::class.java) ?: 0
                val vName = snapshot.child("versionName").getValue(String::class.java) ?: ""
                val url = snapshot.child("downloadUrl").getValue(String::class.java) ?: ""
                val notes = snapshot.child("releaseNotes").getValue(String::class.java) ?: ""
                
                val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }

                if (vCode > currentVersionCode && url.isNotBlank()) {
                    appUpdateInfo = com.example.metrotime.utils.AppUpdater.UpdateInfo(vCode, vName, url, notes)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        db.getReference("global_alert").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
                if (active) {
                    globalAlertText = snapshot.child("text").getValue(String::class.java)
                } else {
                    globalAlertText = null
                }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })

        while(isActive) {
            pendingDetections = TripHistoryManager.getReadyDetections(activity)
            delay(5000)
        }
    }

    // Fetch existing photoUrl and base64
    LaunchedEffect(currentUser?.uid) {
        if (currentUser != null) {
            val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
            try {
                val snapUrl = db.getReference("users").child(currentUser.uid).child("photoUrl").get().await()
                photoUrl = snapUrl.getValue(String::class.java)
                val snapB64 = db.getReference("users").child(currentUser.uid).child("photoBase64").get().await()
                photoBase64 = snapB64.getValue(String::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Photo picker
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && currentUser != null) {
            isUploading = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri))
                    } else {
                        MediaStore.Images.Media.getBitmap(activity.contentResolver, uri)
                    }

                    // Resize to 250x250 max
                    val maxSide = 250
                    val ratio = maxSide.toFloat() / Math.max(bitmap.width, bitmap.height)
                    val width = Math.round(ratio * bitmap.width)
                    val height = Math.round(ratio * bitmap.height)
                    val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)

                    val baos = ByteArrayOutputStream()
                    resized.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                    val encodedImage = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

                    val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                    db.getReference("users").child(currentUser.uid).child("photoBase64").setValue(encodedImage).await()
                    db.getReference("users").child(currentUser.uid).child("photoUrl").removeValue() // Clear google photo if custom used
                    
                    withContext(Dispatchers.Main) {
                        photoBase64 = encodedImage
                        photoUrl = null
                        Toast.makeText(activity, "Foto atualizada com sucesso!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        isUploading = false
                    }
                }
            }
        }
    }

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
                            if (photoBase64 != null) {
                                val bytes = Base64.decode(photoBase64, Base64.DEFAULT)
                                val btmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                androidx.compose.foundation.Image(
                                    bitmap = btmp.asImageBitmap(),
                                    contentDescription = "Profile Picture",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else if (photoUrl != null) {
                                AsyncImage(
                                    model = photoUrl,
                                    contentDescription = "Profile Picture",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(text = initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }
                            if (isUploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Mudar Foto") },
                            onClick = {
                                showMenu = false
                                launcher.launch("image/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Logout") },
                            onClick = {
                                showMenu = false
                                coroutineScope.launch {
                                    FirebaseAuth.getInstance().signOut()
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
                Box {
                    IconButton(onClick = { showPendingDialog = true }) {
                        Icon(imageVector = Icons.Default.Notifications, contentDescription = "Notifications", tint = Color.White)
                    }
                    if (pendingDetections.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 8.dp, top = 8.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                        )
                    }
                }
                IconButton(onClick = { 
                    activity.startActivity(Intent(activity, FriendsActivity::class.java))
                }) {
                    Icon(imageVector = Icons.Default.Person, contentDescription = "Friends", tint = Color.White)
                }
                IconButton(onClick = { showAdminDialog = true }) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Admin Settings", tint = Color.White)
                }
            }
        )

        if (showMenu) {
            AppSettingsDialog(activity) { showMenu = false }
        }

        if (showPendingDialog && pendingDetections.isNotEmpty()) {
            PendingDetectionsDialog(
                activity = activity,
                pendingDetections = pendingDetections,
                onDismiss = { showPendingDialog = false },
                onRefresh = { pendingDetections = TripHistoryManager.getReadyDetections(activity) }
            )
        }

        // ── App Update Dialog ──
        if (appUpdateInfo != null) {
            AlertDialog(
                onDismissRequest = { appUpdateInfo = null },
                title = {
                    Text(
                        "Nova Atualização",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    Column {
                        Text(
                            "A versão ${appUpdateInfo!!.versionName} está disponível para transferência.",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        if (appUpdateInfo!!.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Novidades:",
                                color = Color.Gray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                appUpdateInfo!!.releaseNotes,
                                color = Color.LightGray,
                                fontSize = 14.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            com.example.metrotime.utils.AppUpdater.downloadAndInstall(activity, appUpdateInfo!!.downloadUrl)
                            appUpdateInfo = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                    ) {
                        Text("Atualizar", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { appUpdateInfo = null }) {
                        Text("Mais tarde", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (showAdminDialog) {
            AppSettingsDialog(activity, onDismiss = { showAdminDialog = false })
        }
        
        if (globalAlertText != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE53935))
                    .padding(12.dp)
            ) {
                Text(
                    text = "⚠️ Alerta: $globalAlertText",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        // ── Metro / Comboio tab toggle ──
        TransportModeToggle(
            currentMode = selectedTab,
            onModeSelected = { selectedTab = it }
        )

        // ── Tab content ──
        when (selectedTab) {
            "Metro" -> MetroTabContent(activity)
            "Comboio" -> ComboioTabContent(activity)
        }
    }
}

@Composable
fun TransportModeToggle(currentMode: String, onModeSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(4.dp)
    ) {
        val modes = listOf("Metro" to com.example.metrotime.R.drawable.metro_logo, "Comboio" to com.example.metrotime.R.drawable.cp_logo)
        modes.forEach { (mode, iconResId) ->
            val isSelected = currentMode == mode
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) Color(0xFF3B82F6) else Color.Transparent,
                animationSpec = tween(250),
                label = "tabBg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable { onModeSelected(mode) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = iconResId),
                        contentDescription = mode,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = mode,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Metro Tab (existing functionality)
// ──────────────────────────────────────────────

@Composable
fun MetroTabContent(activity: ComponentActivity) {
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BottomActionButton("Live Map", "🗺️", modifier = Modifier.weight(1f)) {
                activity.startActivity(
                    android.content.Intent(activity, LiveMapActivity::class.java)
                )
            }
            BottomActionButton("Estatísticas", "📊", modifier = Modifier.weight(1f)) {
                activity.startActivity(
                    android.content.Intent(activity, StatsActivity::class.java)
                )
            }
            BottomActionButton("Favoritos", "⭐", modifier = Modifier.weight(1f)) {
                activity.startActivity(
                    android.content.Intent(activity, FavoritesActivity::class.java)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ──────────────────────────────────────────────
// Comboio Tab (new — hybrid: search + all trains)
// ──────────────────────────────────────────────

// ── Global cache so data isn't lost when switching tabs ──
object CpTabCache {
    var searchQuery by androidx.compose.runtime.mutableStateOf("")
    var stations by androidx.compose.runtime.mutableStateOf<List<CpStation>>(emptyList())
    var allVehicles by androidx.compose.runtime.mutableStateOf<List<CpVehicle>>(emptyList())
    var isLoading by androidx.compose.runtime.mutableStateOf(true)
    var viewMode by androidx.compose.runtime.mutableStateOf("stations") // "stations" or "trains"
    var errorMsg by androidx.compose.runtime.mutableStateOf<String?>(null)
    var avisosCp by androidx.compose.runtime.mutableStateOf<List<CpAviso>>(emptyList())
}

@Composable
fun ComboioTabContent(activity: ComponentActivity) {
    // Reference the global cache object
    val cache = CpTabCache
    var searchQuery by remember { mutableStateOf(cache.searchQuery) }
    var viewMode by remember { mutableStateOf(cache.viewMode) }
    
    // Sync local query changes to cache
    LaunchedEffect(searchQuery) { cache.searchQuery = searchQuery }
    LaunchedEffect(viewMode) { cache.viewMode = viewMode }

    LaunchedEffect(Unit) {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
        db.getReference("cp_avisos/lista").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val list = mutableListOf<CpAviso>()
                for (child in snapshot.children) {
                    val aviso = child.getValue(CpAviso::class.java)
                    if (aviso != null) list.add(aviso)
                }
                cache.avisosCp = list
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })
    }

    // Load stations once
    LaunchedEffect(Unit) {
        if (cache.stations.isNotEmpty()) return@LaunchedEffect
        try {
            val response = withContext(Dispatchers.IO) {
                CpApi.service.getStations()
            }
            cache.stations = response.stations.sortedBy { it.designation }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Auto-refresh vehicles
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                val response = withContext(Dispatchers.IO) {
                    CpApi.service.getVehicles()
                }
                val activeVehicles = response.vehicles.filter {
                    it.status != VehicleStatus.COMPLETED && it.status != VehicleStatus.CANCELLED
                }
                cache.allVehicles = activeVehicles
                
                // Queue unknown vehicles for route caching
                CpRouteRepository.queueUnknownVehicles(activeVehicles)
                cache.errorMsg = null
            } catch (e: Exception) {
                e.printStackTrace()
                if (cache.allVehicles.isEmpty()) {
                    cache.errorMsg = if (e.message == "RateLimitExceeded" || e is com.squareup.moshi.JsonDataException) {
                        "Acesso bloqueado por excesso de pedidos (Rate Limit). Tente novamente daqui a uns minutos."
                    } else {
                        "Erro de ligação: ${e.javaClass.simpleName}"
                    }
                }
            }
            cache.isLoading = false
            delay(60_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Pesquisar estação...", color = Color.Gray) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF3B82F6),
                unfocusedBorderColor = Color(0xFF3A3A3A),
                focusedContainerColor = CardBackground,
                unfocusedContainerColor = CardBackground,
                cursorColor = Color.White,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true
        )

        // Toggle: Estações / Todos os Comboios
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CardBackground)
                .padding(3.dp)
        ) {
            listOf("stations" to "Estações", "trains" to "Comboios em circulação").forEach { (key, label) ->
                val isSelected = viewMode == key
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) Color(0xFF3B82F6) else Color.Transparent,
                    animationSpec = tween(200),
                    label = "subTabBg"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bgColor)
                        .clickable { viewMode = key }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── CP Avisos Accordion ──
        androidx.compose.animation.AnimatedVisibility(visible = cache.avisosCp.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF57C00))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("\u26a0\ufe0f", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Avisos CP (${cache.avisosCp.size})",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Text(
                        text = if (expanded) "\u25b2" else "\u25bc",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                            .heightIn(max = 250.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        cache.avisosCp.forEach { aviso ->
                            Row(
                                modifier = Modifier
                                    .padding(vertical = 5.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .clickable {
                                        try {
                                            val url = if (aviso.link.isNotBlank()) aviso.link else "https://www.cp.pt/pt/avisos"
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            activity.startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("\u2022", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(aviso.titulo, color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        if (cache.isLoading && cache.allVehicles.isEmpty() && cache.stations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CpIntercidades)
            }
        } else if (cache.errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = cache.errorMsg.orEmpty(),
                    color = Color.Red,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            when (viewMode) {
                "stations" -> {
                    // Filtered station list
                    val filteredStations = if (searchQuery.isBlank()) {
                        // Show popular / regional stations when no query
                        cache.stations.filter { it.region != null }
                    } else {
                        cache.stations.filter {
                            it.designation.contains(searchQuery, ignoreCase = true)
                        }
                    }

                    if (searchQuery.isBlank()) {
                        Text(
                            text = "Estações principais",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredStations, key = { it.code }) { station ->
                            CpStationListCard(station = station, allVehicles = cache.allVehicles) {
                                activity.startActivity(
                                    CpStationActivity.newIntent(activity, station.code, station.designation)
                                )
                            }
                        }
                    }
                }

                "trains" -> {
                    // All active trains filtered by search
                    val filteredVehicles = if (searchQuery.isBlank()) {
                        cache.allVehicles
                    } else {
                        cache.allVehicles.filter {
                            it.origin?.designation?.contains(searchQuery, ignoreCase = true) == true ||
                            it.destination?.designation?.contains(searchQuery, ignoreCase = true) == true ||
                            it.trainNumber.toString().contains(searchQuery) ||
                            it.service?.designation?.contains(searchQuery, ignoreCase = true) == true
                        }
                    }

                    Text(
                        text = "${filteredVehicles.size} comboios em circulação",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredVehicles, key = { it.trainNumber }) { vehicle ->
                            CpVehicleCard(vehicle = vehicle) {
                                activity.startActivity(
                                    CpTripDetailActivity.newIntent(activity, vehicle.trainNumber)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CpStationListCard(station: CpStation, allVehicles: List<CpVehicle>, onClick: () -> Unit) {
    // Count trains at/from/to this station
    val trainCount = allVehicles.count {
        it.origin?.code == station.code ||
        it.destination?.code == station.code ||
        it.lastStation == station.code
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = TrainIcon,
                contentDescription = "Station",
                tint = CpIntercidades,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.designation,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (station.region != null) {
                    Text(
                        text = station.region,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
            if (trainCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(CpIntercidades.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$trainCount",
                        color = CpIntercidades,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
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
fun BottomActionButton(text: String, emoji: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Button(
        onClick = onClick,
        modifier = modifier.height(80.dp),
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

@Composable
fun AppSettingsDialog(activity: ComponentActivity, onDismiss: () -> Unit) {
    var stations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val cache = remember { CpRouteRepository.getCacheSnapshot() }
    
    // Preferences
    var isContinuous by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.isContinuousLocation(activity)) }
    
    LaunchedEffect(Unit) {
        try {
            val response = withContext(Dispatchers.IO) {
                CpApi.service.getStations()
            }
            stations = response.stations.associate { it.code to it.designation }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    var allAppUsers by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedAdminUids by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isAllUsersSelected by remember { mutableStateOf(false) }
    var secretClickCount by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "Definições", 
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    secretClickCount++
                }
            ) 
        },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
                
                // ── Privacy Settings ──
                item {
                    Text("Privacidade", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Partilhar em background", color = Color.White, fontSize = 15.sp)
                            Text(
                                text = if (isContinuous) "A localização é partilhada enquanto a app ou a notificação estiverem ativas." else "A localização só é partilhada quando abres o Mapa ao Vivo.",
                                color = Color.LightGray, 
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = isContinuous,
                            onCheckedChange = { 
                                isContinuous = it
                                com.example.metrotime.prefs.Prefs.setContinuousLocation(activity, it)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6), checkedTrackColor = Color(0xFF3B82F6).copy(alpha = 0.5f))
                        )
                    }
                }
                
                if (secretClickCount >= 5) {
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Divider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(16.dp))

                        // ── Admin Panel ──
                        var passInput by remember { mutableStateOf("") }
                        var isAdminUnlocked by remember { mutableStateOf(false) }
                        
                        var adminGlobalAlertText by remember { mutableStateOf("") }
                        var adminGlobalAlertActive by remember { mutableStateOf(false) }
                        var allStationReports by remember { mutableStateOf<List<Triple<String, String, Long>>>(emptyList()) }
                        
                        LaunchedEffect(isAdminUnlocked) {
                            if (isAdminUnlocked) {
                                val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                db.getReference("users").get().addOnSuccessListener { snap ->
                                    val list = mutableListOf<Pair<String, String>>()
                                    for (child in snap.children) {
                                        val u = child.key ?: continue
                                        val n = child.child("name").getValue(String::class.java) ?: "Anónimo"
                                        list.add(u to n)
                                    }
                                    allAppUsers = list
                                }
                                db.getReference("global_alert").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                                    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                                        adminGlobalAlertActive = snapshot.child("active").getValue(Boolean::class.java) ?: false
                                        adminGlobalAlertText = snapshot.child("text").getValue(String::class.java) ?: ""
                                    }
                                    override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                                })
                                db.getReference("station_reports").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                                    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                                        val list = mutableListOf<Triple<String, String, Long>>()
                                        val now = System.currentTimeMillis()
                                        val maxAgeMs = 3 * 24 * 60 * 60 * 1000L
                                        for (stationSnap in snapshot.children) {
                                            val sid = stationSnap.key ?: continue
                                            for (issueSnap in stationSnap.children) {
                                                val issue = issueSnap.key ?: continue
                                                val ts = issueSnap.getValue(Long::class.java) ?: continue
                                                if (now - ts > maxAgeMs) {
                                                    list.add(Triple(sid, issue, ts))
                                                } else {
                                                    list.add(Triple(sid, issue, ts))
                                                }
                                            }
                                        }
                                        allStationReports = list.sortedByDescending { it.third }
                                    }
                                    override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                                })
                            }
                        }

                        if (!isAdminUnlocked) {
                            Text("Área Reservada (Equipa)", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = passInput,
                                onValueChange = { 
                                    passInput = it
                                    if (it == "DJI2026") isAdminUnlocked = true
                                },
                                placeholder = { Text("Palavra-passe Admin") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        } else {
                            var adminMessage by remember { mutableStateOf("") }
                            
                            Text("Área de Administrador", color = Color.Yellow, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(16.dp))

                            // ── Global Alert ──
                            Text("Alerta Global", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = adminGlobalAlertText,
                                onValueChange = { adminGlobalAlertText = it },
                                placeholder = { Text("Texto do Alerta (Ex: Greve no Metro)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row {
                                    Button(onClick = { adminGlobalAlertText = "Greve de 24h na CP" }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("CP") }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Button(onClick = { adminGlobalAlertText = "Greve no Metro" }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("Metro") }
                                }
                                Switch(
                                    checked = adminGlobalAlertActive,
                                    onCheckedChange = { active ->
                                        val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                        db.getReference("global_alert").setValue(mapOf("active" to active, "text" to adminGlobalAlertText))
                                        if (active && adminGlobalAlertText.isNotBlank()) {
                                            for (u in allAppUsers) {
                                                db.getReference("adminMessages").child(u.first).push().setValue("ALERT:" + adminGlobalAlertText)
                                            }
                                            android.widget.Toast.makeText(activity, "Alerta enviado como notificação push para todos!", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            Divider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(16.dp))

                            // ── Station Reports ──
                            Text("Avarias Reportadas", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    Button(onClick = { expanded = true }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                                        Text("Por Linha", color = Color.White)
                                    }
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        listOf("Amarela", "Azul", "Verde", "Vermelha").forEach { linha ->
                                            DropdownMenuItem(
                                                text = { Text("Linha $linha") },
                                                onClick = {
                                                    expanded = false
                                                    val metroStations = com.example.metrotime.data.StationsRepo.loadStations(activity)
                                                    val lineStations = metroStations.filter { it.line.contains(linha) }.map { it.id }
                                                    val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                                    allStationReports.forEach { (sid, issue, _) ->
                                                        if (lineStations.contains(sid)) {
                                                            db.getReference("station_reports").child(sid).child(issue).removeValue()
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                                Button(onClick = {
                                    val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                    allStationReports.forEach { (sid, issue, _) ->
                                        db.getReference("station_reports").child(sid).child(issue).removeValue()
                                    }
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) {
                                    Text("Limpar Todas", color = Color.White)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (allStationReports.isEmpty()) {
                                Text("Nenhuma avaria ativa.", color = Color.Gray, fontSize = 13.sp)
                            } else {
                                allStationReports.forEach { (sid, issue, ts) ->
                                    val ageDays = (System.currentTimeMillis() - ts) / (24 * 60 * 60 * 1000L)
                                    val stName = stations[sid] ?: sid
                                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(stName, color = Color.White, fontWeight = FontWeight.Bold)
                                                Text(issue, color = Color(0xFFF57C00), fontSize = 13.sp)
                                                Text(if (ageDays == 0L) "Hoje" else "Há $ageDays dias", color = if (ageDays >= 3) Color.Red else Color.Gray, fontSize = 12.sp)
                                            }
                                            IconButton(onClick = {
                                                val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                                db.getReference("station_reports").child(sid).child(issue).removeValue()
                                            }) {
                                                Icon(Icons.Default.Check, contentDescription = "Resolvido", tint = Color(0xFF10B981))
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            Divider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(16.dp))

                            Text("Notificações Push", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Selecionar destinatários:", color = Color.White, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isAllUsersSelected) Color(0xFF3B82F6) else Color.DarkGray)
                                        .clickable { isAllUsersSelected = !isAllUsersSelected }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("🌍 Todos", color = Color.White, fontSize = 12.sp)
                                }
                                allAppUsers.forEach { user ->
                                    val isSelected = selectedAdminUids.contains(user.first)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (isSelected) Color(0xFF3B82F6) else Color.DarkGray)
                                            .clickable { 
                                                val newSet = selectedAdminUids.toMutableSet()
                                                if (newSet.contains(user.first)) newSet.remove(user.first) else newSet.add(user.first)
                                                selectedAdminUids = newSet
                                                if (isAllUsersSelected) isAllUsersSelected = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(user.second, color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = adminMessage,
                                onValueChange = { adminMessage = it },
                                placeholder = { Text("Escreve a mensagem de notificação...") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                                minLines = 3,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if ((selectedAdminUids.isNotEmpty() || isAllUsersSelected) && adminMessage.isNotBlank()) {
                                        val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                        if (isAllUsersSelected) {
                                            for (u in allAppUsers) {
                                                db.getReference("adminMessages").child(u.first).push().setValue(adminMessage)
                                            }
                                            android.widget.Toast.makeText(activity, "Enviado para todos!", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            for (uid in selectedAdminUids) {
                                                db.getReference("adminMessages").child(uid).push().setValue(adminMessage)
                                            }
                                            android.widget.Toast.makeText(activity, "Enviado para ${selectedAdminUids.size} pessoas!", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        adminMessage = ""
                                        selectedAdminUids = emptySet()
                                        isAllUsersSelected = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow)
                            ) {
                                Text("Enviar Notificação Push", color = Color.Black, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    if (selectedAdminUids.isNotEmpty() || isAllUsersSelected) {
                                        val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                        val uidsToReset = if (isAllUsersSelected) allAppUsers.map { it.first } else selectedAdminUids
                                        
                                        for (uid in uidsToReset) {
                                            db.getReference("users").child(uid).child("stats").removeValue()
                                            db.getReference("user_stats").child(uid).removeValue()
                                            
                                            // Se o admin resetar a si próprio, limpa a memória local
                                            if (uid == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid) {
                                                com.example.metrotime.data.history.TripHistoryManager.clearHistory(activity)
                                            }
                                        }
                                        android.widget.Toast.makeText(activity, "Estatísticas de ${uidsToReset.size} utilizadores repostas a zero!", android.widget.Toast.LENGTH_LONG).show()
                                        
                                        selectedAdminUids = emptySet()
                                        isAllUsersSelected = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                            ) {
                                Text("Resetar Estatísticas", color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            Divider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(16.dp))

                            // ── Gestor de Atualizações OTA ──
                            Text("Lançar Atualização (OTA)", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Preenche para forçar uma atualização na app de todos:", color = Color.Gray, fontSize = 13.sp)
                            
                            var otaVersionCode by remember { mutableStateOf("") }
                            var otaVersionName by remember { mutableStateOf("") }
                            var otaNotes by remember { mutableStateOf("") }
                            var otaLink by remember { mutableStateOf("") }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = otaVersionCode,
                                    onValueChange = { otaVersionCode = it.filter { char -> char.isDigit() } },
                                    placeholder = { Text("Ver. Code (Ex: 2)") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                                OutlinedTextField(
                                    value = otaVersionName,
                                    onValueChange = { otaVersionName = it },
                                    placeholder = { Text("Nome (Ex: 2.0)") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = otaNotes,
                                onValueChange = { otaNotes = it },
                                placeholder = { Text("Novidades (Ex: Novo ecrã de perfil)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = otaLink,
                                onValueChange = { otaLink = it },
                                placeholder = { Text("Link de Download Direto (.apk)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val code = otaVersionCode.toIntOrNull()
                                    if (code != null && otaVersionName.isNotBlank() && otaLink.isNotBlank()) {
                                        val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                        val updateData = mapOf(
                                            "versionCode" to code,
                                            "versionName" to otaVersionName,
                                            "releaseNotes" to otaNotes,
                                            "downloadUrl" to otaLink
                                        )
                                        db.getReference("app_update").setValue(updateData).addOnSuccessListener {
                                            android.widget.Toast.makeText(activity, "Atualização Lançada! Todos vão receber no próximo arranque.", android.widget.Toast.LENGTH_LONG).show()
                                            otaVersionCode = ""
                                            otaVersionName = ""
                                            otaNotes = ""
                                            otaLink = ""
                                        }
                                    } else {
                                        android.widget.Toast.makeText(activity, "Preenche pelo menos o código, nome e o link!", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)) // Verde
                            ) {
                                Text("Lançar Atualização Agora", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Divider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // ── Admin Cache ──
                        Text("Sistema (Admin - Cache)", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${cache.size} rotas memorizadas", fontWeight = FontWeight.Medium, color = Color.Gray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (cache.isNotEmpty()) {
                        items(cache.toList()) { (trainNum, routeCodes) ->
                            val originName = stations[routeCodes.firstOrNull()] ?: "A carregar..."
                            val destName = stations[routeCodes.lastOrNull()] ?: "A carregar..."
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Comboio $trainNum", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("${routeCodes.size} paragens ($originName ➔ $destName)", color = CpIntercidades, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = Color(0xFF3B82F6))
            }
        },
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingDetectionsDialog(
    activity: ComponentActivity,
    pendingDetections: List<PendingDetection>,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Estiveste aqui?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            if (pendingDetections.isEmpty()) {
                Text(
                    "Não tens viagens pendentes de confirmação.",
                    color = Color.LightGray
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(pendingDetections) { detection ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val isCp = detection.type == "cp"
                                    Text(
                                        text = "${if (isCp) "🚆" else "🚇"} ${detection.stationName}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = if (isCp) detection.lineName else "Linha ${detection.lineName}",
                                        color = Color.Gray,
                                        fontSize = 14.sp
                                    )
                                    val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    Text(
                                        text = "${timeFormat.format(java.util.Date(detection.firstSeenMs))} - ${timeFormat.format(java.util.Date(detection.lastSeenMs))}",
                                        color = Color.LightGray,
                                        fontSize = 12.sp
                                    )
                                }
                                
                                Row {
                                    IconButton(
                                        onClick = {
                                            TripHistoryManager.confirmDetection(activity, detection)
                                            onRefresh()
                                        },
                                        modifier = Modifier.size(36.dp).background(Color(0xFF10B981).copy(alpha=0.2f), CircleShape)
                                    ) {
                                        Text("✓", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            TripHistoryManager.dismissDetection(activity, detection)
                                            onRefresh()
                                        },
                                        modifier = Modifier.size(36.dp).background(Color(0xFFEF4444).copy(alpha=0.2f), CircleShape)
                                    ) {
                                        Text("✗", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = Color(0xFF3B82F6))
            }
        },
        containerColor = DarkBackground
    )
}
