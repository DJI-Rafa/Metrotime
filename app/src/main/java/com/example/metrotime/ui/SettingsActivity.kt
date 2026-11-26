package com.example.metrotime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
import com.example.metrotime.data.FIREBASE_DB_URL
import com.example.metrotime.data.UserDirectory
import com.example.metrotime.data.boolOrNull
import com.example.metrotime.data.longOrNull
import com.example.metrotime.data.stringOrNull
import com.example.metrotime.data.cp.*
import com.example.metrotime.data.history.PendingTrip
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

    companion object {
        /** Separador a abrir logo ("Metro", "Comboio", "Autocarro"): vem dos botões dos widgets. */
        const val EXTRA_OPEN_CATEGORY = "open_category"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        // Primeira abertura desta versão: a intro das novidades por cima
        if (savedInstanceState == null && com.example.metrotime.ui.intro.IntroActivity.shouldShow(this)) {
            startActivity(com.example.metrotime.ui.intro.IntroActivity.newIntent(this))
        }

        // Let Compose draw the home screen before starting background work that can
        // contend for the main thread and CPU during the opening animation.
        lifecycleScope.launch {
            delay(750)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }

            com.example.metrotime.work.WidgetUpdateService.requestFetch(this@SettingsActivity, fromUi = true)
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
    var activeCategory by remember { mutableStateOf(activity.intent?.getStringExtra(SettingsActivity.EXTRA_OPEN_CATEGORY)) }

    BackHandler(enabled = activeCategory != null) {
        activeCategory = null
    }

    var photoUrl by remember { mutableStateOf<String?>(null) }
    var photoBase64 by remember { mutableStateOf<String?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    val profileImage = remember(photoBase64) {
        photoBase64?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
            }.getOrNull()
        }
    }
    
    var pendingDetections by remember { mutableStateOf<List<PendingTrip>>(emptyList()) }
    var userFirstName by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.getUserFirstName(activity)) }
    var globalAlertText by remember { mutableStateOf<String?>(null) }
    var appUpdateInfo by remember { mutableStateOf<com.example.metrotime.utils.AppUpdater.UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        // Do not compete with the first render and entrance animation.
        delay(750)

        // Initial async load
        val initial = withContext(Dispatchers.IO) {
            TripHistoryManager.pendingTrips(activity)
        }
        pendingDetections = initial

        // Verificação de atualização
        appUpdateInfo = com.example.metrotime.utils.AppUpdater.fetchAvailableUpdate(activity)

        while(isActive) {
            val ready = withContext(Dispatchers.IO) {
                TripHistoryManager.pendingTrips(activity)
            }
            pendingDetections = ready
            delay(30_000)
        }
    }

    // Alerta global — listener removido ao sair do ecrã
    DisposableEffect(Unit) {
        val ref = FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("global_alert")
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val active = snapshot.child("active").boolOrNull() ?: false
                globalAlertText = if (active) snapshot.child("text").stringOrNull() else null
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        onDispose { ref.removeEventListener(listener) }
    }

    // Fetch existing photoUrl and base64
    LaunchedEffect(currentUser?.uid) {
        if (currentUser != null) {
            // Nome para a saudação ("Bom dia, João"): o do registo, senão o da conta Google
            runCatching {
                val name = FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("users").child(currentUser.uid)
                    .child("name").get().await().stringOrNull()
                HomeText.firstName(name) ?: HomeText.firstName(currentUser.displayName)
            }.getOrNull()?.let { first ->
                userFirstName = first
                com.example.metrotime.prefs.Prefs.setUserFirstName(activity, first)
            }
            delay(1_000)
            val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
            try {
                val snapUrl = db.getReference("users").child(currentUser.uid).child("photoUrl").get().await()
                photoUrl = snapUrl.stringOrNull()
                val snapB64 = db.getReference("users").child(currentUser.uid).child("photoBase64").get().await()
                photoBase64 = snapB64.stringOrNull()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Perfil público (nome/foto) e estatuto de admin vindos do Firebase
            runCatching { UserDirectory.syncOwnProfile(currentUser.uid, currentUser.email) }
            UserDirectory.refreshAdminFlag(activity, currentUser.uid)
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

                    val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
                    db.getReference("users").child(currentUser.uid).child("photoBase64").setValue(encodedImage).await()
                    db.getReference("users").child(currentUser.uid).child("photoUrl").removeValue() // Clear google photo if custom used
                    UserDirectory.publishPhoto(currentUser.uid, encodedImage)
                    runCatching { UserDirectory.syncOwnProfile(currentUser.uid, currentUser.email) }

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

    var showCpAvisos by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (activeCategory == null) {
        TopAppBar(
            title = {
                Column {
                    val hour = java.time.LocalTime.now(java.time.ZoneId.of("Europe/Lisbon")).hour
                    Text(
                        HomeText.greeting(hour, userFirstName),
                        color = TextSecondaryDark,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text("Para onde vais?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = DarkBackground
            ),
            actions = {
                // Notificações: viagens por confirmar
                Box {
                    IconButton(onClick = {
                        if (pendingDetections.isNotEmpty()) showPendingDialog = true
                        else Toast.makeText(activity, "Sem notificações novas.", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(imageVector = Icons.Default.Notifications, contentDescription = "Notificações", tint = Color.White)
                    }
                    if (pendingDetections.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 10.dp, top = 10.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF6B6B))
                        )
                    }
                }
                // Perfil: foto, amigos, definições e sair (antes eram quatro ícones soltos no topo)
                Box(modifier = Modifier.padding(start = 4.dp, end = 12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2563EB))
                            .clickable { showMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        val letter = userFirstName?.take(1) ?: initials
                        if (profileImage != null) {
                            androidx.compose.foundation.Image(
                                bitmap = profileImage,
                                contentDescription = "Perfil",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else if (photoUrl != null) {
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = "Perfil",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else if (letter.isNotEmpty()) {
                            Text(text = letter, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        } else {
                            Icon(Icons.Default.Person, contentDescription = "Perfil", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        if (isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(CardBackground)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Mudar foto", color = Color.White) },
                            onClick = {
                                showMenu = false
                                launcher.launch("image/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Amigos", color = Color.White) },
                            onClick = {
                                showMenu = false
                                activity.startActivity(Intent(activity, FriendsActivity::class.java))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Definições", color = Color.White) },
                            onClick = {
                                showMenu = false
                                showAdminDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Novidades", color = Color.White) },
                            onClick = {
                                showMenu = false
                                activity.startActivity(com.example.metrotime.ui.intro.IntroActivity.newIntent(activity))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Terminar sessão", color = Color(0xFFFF8A7D)) },
                            onClick = {
                                showMenu = false
                                com.example.metrotime.prefs.Prefs.setUserFirstName(activity, null)
                                coroutineScope.launch {
                                    UserDirectory.signOut(activity)
                                    val intent = Intent(activity, AuthActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                    activity.startActivity(intent)
                                }
                            }
                        )
                    }
                }
            }
        )
        } else {
            TopAppBar(
                title = {
                    Text(
                        text = when (activeCategory) {
                            "Metro" -> "Metro de Lisboa"
                            "Comboio" -> "Comboios CP"
                            "Autocarro" -> "Autocarros"
                            else -> ""
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { activeCategory = null }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    // Avisos da CP: um ícone no topo em vez do retângulo laranja por cima da lista
                    val avisos = CpTabCache.avisosCp
                    if (activeCategory == "Comboio" && avisos.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { showCpAvisos = true }) {
                                Icon(Icons.Default.Warning, contentDescription = "Avisos CP", tint = Color(0xFFFFB74D))
                            }
                            val badgeSize = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toSp() }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 6.dp, end = 2.dp)
                                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF57C00))
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (avisos.size > 99) "99+" else "${avisos.size}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontSize = badgeSize,
                                        lineHeight = badgeSize,
                                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                                        )
                                    )
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }

        if (showCpAvisos) {
            CpAvisosDialog(activity, CpTabCache.avisosCp) { showCpAvisos = false }
        }

        if (showPendingDialog && pendingDetections.isNotEmpty()) {
            PendingDetectionsDialog(
                activity = activity,
                pendingDetections = pendingDetections,
                onDismiss = { showPendingDialog = false },
                onRefresh = { pendingDetections = TripHistoryManager.pendingTrips(activity) }
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

        var animVisible by remember { mutableStateOf(false) }
        var homeEntrancePlayed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(50)
            animVisible = true
            delay(800)
            homeEntrancePlayed = true
        }

        if (activeCategory == null) {
            HomeContent(activity, animVisible = animVisible, animate = !homeEntrancePlayed) { activeCategory = it }
        } else {
            // Metro, Comboios e Autocarros lado a lado, a deslizar (o título e os avisos seguem o que está à vista)
            CategoryPager(activity, activeCategory ?: "Metro") { activeCategory = it }
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
    var errorMsg by androidx.compose.runtime.mutableStateOf<String?>(null) // erro dos comboios em circulação
    var stationsError by androidx.compose.runtime.mutableStateOf<String?>(null)
    var avisosCp by androidx.compose.runtime.mutableStateOf<List<CpNotice>>(emptyList())
    var trainSort by androidx.compose.runtime.mutableStateOf("Atraso") // "Atraso", "Número" ou "Serviço"
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

    // Avisos CP — listener removido ao sair (antes acumulava um novo a cada troca de separador)
    DisposableEffect(Unit) {
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance(CpRepository.FIREBASE_URL).getReference("cp_avisos/lista")
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                // Só os avisos que ainda estão a decorrer (com título, resumo, datas e link)
                val today = CpTime.today()
                cache.avisosCp = snapshot.children.mapNotNull { CpRepository.parseNotice(it) }.filter { it.isCurrent(today) }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        onDispose { ref.removeEventListener(listener) }
    }

    // Estações: cache em disco (7 dias) e independentes dos comboios em circulação
    LaunchedEffect(Unit) {
        if (cache.stations.isNotEmpty()) return@LaunchedEffect
        try {
            cache.stations = CpRepository.getStations(activity).sortedBy { it.designation }
            cache.stationsError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            cache.stationsError = "Não foi possível carregar as estações (${e.javaClass.simpleName})."
        }
    }

    // Comboios em circulação: lidos do Firebase (Cloud Function) com fallback direto;
    // só atualiza enquanto o ecrã está visível
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    cache.allVehicles = CpRepository.getActiveVehicles()
                    cache.errorMsg = null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (cache.allVehicles.isEmpty()) {
                        val httpCode = (e as? retrofit2.HttpException)?.code()
                        cache.errorMsg = if (httpCode == 429) {
                            "Acesso bloqueado por excesso de pedidos (Rate Limit). Tente novamente daqui a uns minutos."
                        } else {
                            "Não foi possível obter os comboios em circulação (${e.javaClass.simpleName})."
                        }
                    }
                }
                cache.isLoading = false
                delay(60_000)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(if (viewMode == "trains") "Pesquisar comboio..." else "Pesquisar estação...", color = Color.Gray) },
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

        // Cada vista mostra só o seu próprio erro: uma falha nos comboios já não esconde as estações
        val viewError = if (viewMode == "stations") {
            cache.stationsError.takeIf { cache.stations.isEmpty() }
        } else {
            cache.errorMsg.takeIf { cache.allVehicles.isEmpty() }
        }
        if (cache.isLoading && cache.allVehicles.isEmpty() && cache.stations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CpIntercidades)
            }
        } else if (viewError != null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = viewError,
                    color = Color.Red,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            when (viewMode) {
                "stations" -> {
                    // Filtered station list (cached with derivedStateOf)
                    val filteredStations by remember(searchQuery, cache.stations) {
                        androidx.compose.runtime.derivedStateOf {
                            if (searchQuery.isBlank()) {
                                cache.stations.filter { it.region != null }
                            } else {
                                // Sem acentos: "Valença" encontra "Valenca" (a API não usa acentos)
                                cache.stations.filter { CpText.matches(it.designation, searchQuery) }
                            }
                        }
                    }

                    // Comboios em circulação distintos que partem, terminam ou acabaram de passar em
                    // cada estação (antes o mesmo comboio podia contar até 3 vezes)
                    val trainCountMap by remember(cache.allVehicles) {
                        androidx.compose.runtime.derivedStateOf {
                            val map = mutableMapOf<String, MutableSet<String>>()
                            for (v in cache.allVehicles) {
                                listOfNotNull(v.origin?.code, v.destination?.code, v.lastStation)
                                    .toSet()
                                    .forEach { code -> map.getOrPut(code) { mutableSetOf() }.add(v.uniqueKey) }
                            }
                            map.mapValues { it.value.size }
                        }
                    }

                    // Favoritas e as mais próximas (pela última localização conhecida)
                    val favorites = remember(cache.stations) {
                        val favCodes = com.example.metrotime.prefs.Prefs.getCpFavorites(activity).map { it.substringBefore("|") }.toSet()
                        cache.stations.filter { it.code in favCodes }
                    }
                    var myLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
                    LaunchedEffect(Unit) {
                        myLocation = runCatching {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(activity)
                                    .lastLocation.await()?.let { it.latitude to it.longitude }
                            } else null
                        }.getOrNull() ?: TripHistoryManager.getLastUserLocation(activity)
                    }
                    val nearby = remember(cache.stations, myLocation) {
                        val here = myLocation ?: return@remember emptyList()
                        cache.stations.mapNotNull { st ->
                            val lat = st.latitude?.toDoubleOrNull() ?: return@mapNotNull null
                            val lon = st.longitude?.toDoubleOrNull() ?: return@mapNotNull null
                            st to CpTrack.metres(here, lat to lon)
                        }.filter { it.second < 30_000 }.sortedBy { it.second }.take(3)
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (searchQuery.isBlank()) {
                            item(key = "plan_trip") {
                                Card(
                                    onClick = { activity.startActivity(CpJourneyActivity.newIntent(activity)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B82F6).copy(alpha = 0.18f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("🧭", fontSize = 18.sp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Planear viagem", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                            Text("Comboios diretos entre duas estações", color = Color.LightGray, fontSize = 12.sp)
                                        }
                                        Text("›", color = Color.Gray, fontSize = 22.sp)
                                    }
                                }
                            }
                            if (favorites.isNotEmpty()) {
                                item(key = "fav_header") { CpSectionHeader("Favoritas") }
                                items(favorites, key = { "fav_" + it.code }) { station ->
                                    CpStationListCard(station = station, trainCount = trainCountMap[station.code] ?: 0) {
                                        activity.startActivity(CpStationActivity.newIntent(activity, station.code, station.designation))
                                    }
                                }
                            }
                            if (nearby.isNotEmpty()) {
                                item(key = "near_header") { CpSectionHeader("Perto de ti") }
                                items(nearby, key = { "near_" + it.first.code }) { (station, metres) ->
                                    CpStationListCard(
                                        station = station,
                                        trainCount = trainCountMap[station.code] ?: 0,
                                        subtitle = if (metres < 1000) "${metres.toInt()} m" else "%.1f km".format(metres / 1000)
                                    ) {
                                        activity.startActivity(CpStationActivity.newIntent(activity, station.code, station.designation))
                                    }
                                }
                            }
                            item(key = "all_header") { CpSectionHeader("Estações principais") }
                        }
                        items(
                            items = filteredStations,
                            key = { it.code },
                            contentType = { "station" }
                        ) { station ->
                            CpStationListCard(station = station, trainCount = trainCountMap[station.code] ?: 0) {
                                activity.startActivity(
                                    CpStationActivity.newIntent(activity, station.code, station.designation)
                                )
                            }
                        }
                    }
                }

                "trains" -> {
                    // All active trains filtered by search (cached)
                    val filteredVehicles by remember(searchQuery, cache.allVehicles, cache.trainSort) {
                        androidx.compose.runtime.derivedStateOf {
                            val list = if (searchQuery.isBlank()) {
                                cache.allVehicles
                            } else {
                                cache.allVehicles.filter {
                                    CpText.matches(it.origin?.designation, searchQuery) ||
                                    CpText.matches(it.destination?.designation, searchQuery) ||
                                    it.trainNumber.toString().contains(searchQuery.trim()) ||
                                    CpText.matches(it.service?.designation, searchQuery)
                                }
                            }
                            when (cache.trainSort) {
                                "Atraso" -> list.sortedWith(compareByDescending<CpVehicle> { it.delay ?: 0 }.thenBy { it.trainNumber })
                                "Serviço" -> list.sortedWith(compareBy<CpVehicle> { CpService.ORDER.indexOf(CpService.shortName(it.service?.code)).let { i -> if (i < 0) 99 else i } }.thenBy { it.trainNumber })
                                else -> list.sortedBy { it.trainNumber }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredVehicles.size} comboios em circulação",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        listOf("Atraso", "Número", "Serviço").forEach { mode ->
                            val on = cache.trainSort == mode
                            Text(
                                mode,
                                color = if (on) Color.White else Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (on) Color(0xFF3B82F6) else Color.Transparent)
                                    .clickable { cache.trainSort = mode }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Ao mudar a ordenação (ou a pesquisa) a lista volta ao topo; sem isto o LazyColumn
                    // mantinha o mesmo comboio à vista e o mais atrasado ficava lá em cima, fora do ecrã
                    val trainListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LaunchedEffect(cache.trainSort, searchQuery) { trainListState.scrollToItem(0) }

                    LazyColumn(
                        state = trainListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredVehicles,
                            key = { it.uniqueKey }, // número + data: evita crash com chaves repetidas perto da meia-noite
                            contentType = { "vehicle" }
                        ) { vehicle ->
                            CpVehicleCard(vehicle = vehicle) {
                                activity.startActivity(
                                    CpTripDetailActivity.newIntent(activity, vehicle.trainNumber, vehicle.runDate?.take(10))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Avisos da CP (do site) que ainda estão a decorrer; toca num para o abrir no site. */
@Composable
fun CpAvisosDialog(activity: ComponentActivity, avisos: List<CpNotice>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        title = { Text("Avisos CP", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(avisos) { aviso ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CardBackground)
                            .clickable {
                                try {
                                    val url = aviso.link.ifBlank { "https://www.cp.pt/pt/avisos" }
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    activity.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(aviso.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            val dates = listOfNotNull(aviso.start, aviso.end).distinct()
                                .joinToString(" a ") { it.format(java.time.format.DateTimeFormatter.ofPattern("d/M")) }
                            val sub = listOf(aviso.summary, dates).filter { it.isNotBlank() }.joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, color = Color.LightGray, fontSize = 12.sp)
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.padding(start = 8.dp).size(14.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar", color = MetrotimeBlue) }
        }
    )
}

@Composable
fun CpStationListCard(station: CpStation, trainCount: Int, subtitle: String? = null, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
                val sub = listOfNotNull(subtitle, station.region).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
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
    
    // Preferences
    var isContinuous by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.isContinuousLocation(activity)) }

    // Partilhar em background precisa de "Permitir sempre" (ACCESS_BACKGROUND_LOCATION), que nunca era pedido
    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(activity, "Sem \"Permitir sempre\", a localização só é partilhada com a app aberta.", Toast.LENGTH_LONG).show()
        }
        com.example.metrotime.work.WidgetUpdateService.requestFetch(activity, fromUi = true)
    }
    val foregroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            backgroundLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(activity, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    LaunchedEffect(Unit) {
        try {
            // Usa a cache de estações (disco/memória) em vez de um novo pedido à CP
            stations = CpRepository.getStations(activity).associate { it.code to it.designation }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
                            onCheckedChange = { enabled ->
                                isContinuous = enabled
                                com.example.metrotime.prefs.Prefs.setContinuousLocation(activity, enabled)
                                if (enabled) {
                                    when {
                                        !hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
                                            !hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ->
                                            foregroundLocationLauncher.launch(arrayOf(
                                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                                            ))
                                        !hasPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                                            backgroundLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                    }
                                } else {
                                    // Deixar de partilhar também apaga a última posição (antes ficava visível para sempre)
                                    FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                                        FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("locations").child(uid).removeValue()
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6), checkedTrackColor = Color(0xFF3B82F6).copy(alpha = 0.5f))
                        )
                    }
                }
                
                // ── Registo de viagens (estatísticas): só no telemóvel, separado da partilha ──
                item {
                    var tracking by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.isTripTracking(activity)) }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Registar as minhas viagens", color = Color.White, fontSize = 15.sp)
                            Text(
                                "Reconhece as viagens de metro e comboio para as estatísticas. Fica só no telemóvel; os amigos só veem os totais.",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = tracking,
                            onCheckedChange = { enabled ->
                                tracking = enabled
                                com.example.metrotime.prefs.Prefs.setTripTracking(activity, enabled)
                                if (enabled) {
                                    when {
                                        !hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
                                            !hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ->
                                            foregroundLocationLauncher.launch(arrayOf(
                                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                                            ))
                                        !hasPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                                            backgroundLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                        else -> com.example.metrotime.work.WidgetUpdateService.requestFetch(activity, fromUi = true)
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6), checkedTrackColor = Color(0xFF3B82F6).copy(alpha = 0.5f))
                        )
                    }
                }

                // ── Notificação permanente: discreta por omissão; os tempos do metro são opcionais ──
                item {
                    var liveMetro by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.isLiveMetroNotification(activity)) }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("Notificação", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tempos do metro na notificação", color = Color.White, fontSize = 15.sp)
                            Text(
                                if (liveMetro) "Os próximos metros da estação mais perto, também no ecrã de bloqueio."
                                else "Desligado: fica só uma notificação discreta, que podes esconder.",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = liveMetro,
                            onCheckedChange = { enabled ->
                                liveMetro = enabled
                                com.example.metrotime.prefs.Prefs.setLiveMetroNotification(activity, enabled)
                                // Troca já a notificação (o serviço volta a arrancar com a nova)
                                com.example.metrotime.work.WidgetUpdateService.requestFetch(activity, fromUi = true)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6), checkedTrackColor = Color(0xFF3B82F6).copy(alpha = 0.5f))
                        )
                    }
                    if (!liveMetro) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Esconder a notificação discreta ›",
                            color = Color(0xFF60A5FA),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    runCatching { activity.startActivity(com.example.metrotime.work.WidgetUpdateService.backgroundChannelSettings(activity)) }
                                }
                                .padding(vertical = 4.dp)
                        )
                        Text(
                            "Desliga a categoria \"Em segundo plano\": os widgets, os avisos e a partilha continuam a funcionar.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }

                // ── Alertas de perturbação ──
                item {
                    var metroLines by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.getMetroAlertLines(activity)) }
                    var cpNotices by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.isCpNoticeAlerts(activity)) }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("Alertas", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Linhas do metro com problemas", color = Color.White, fontSize = 15.sp)
                    Text(
                        "Avisamos quando uma linha tem perturbações e quando volta ao normal. Toca numa linha para a ligar ou desligar.",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Azul" to LineAzul, "Amarela" to LineAmarela, "Verde" to LineVerde, "Vermelha" to LineVermelha).forEach { (line, color) ->
                            val on = line in metroLines
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (on) color.copy(alpha = 0.25f) else CardBackground)
                                    .border(1.dp, if (on) color else Color.Transparent, RoundedCornerShape(16.dp))
                                    .clickable {
                                        metroLines = if (on) metroLines - line else metroLines + line
                                        com.example.metrotime.prefs.Prefs.setMetroAlertLines(activity, metroLines)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(line, color = if (on) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Avisos da CP", color = Color.White, fontSize = 15.sp)
                            Text(
                                "Obras, supressões e alterações nas linhas das tuas estações CP favoritas.",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = cpNotices,
                            onCheckedChange = { enabled ->
                                cpNotices = enabled
                                com.example.metrotime.prefs.Prefs.setCpNoticeAlerts(activity, enabled)
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
                        // Acesso decidido pelo Firebase (admins/{uid}); as regras da base de dados
                        // impõem o mesmo, por isso esconder o painel já não é a única proteção.
                        var isAdminUnlocked by remember { mutableStateOf<Boolean?>(null) }

                        var adminGlobalAlertText by remember { mutableStateOf("") }
                        var adminGlobalAlertActive by remember { mutableStateOf(false) }
                        var allStationReports by remember { mutableStateOf<List<Triple<String, String, Long>>>(emptyList()) }

                        LaunchedEffect(Unit) {
                            val uid = FirebaseAuth.getInstance().currentUser?.uid
                            isAdminUnlocked = uid != null && UserDirectory.refreshAdminFlag(activity, uid)
                            if (isAdminUnlocked == true) {
                                allAppUsers = runCatching { UserDirectory.getAllProfiles() }.getOrDefault(emptyList())
                                    .map { it.uid to it.name }
                            }
                        }

                        DisposableEffect(isAdminUnlocked) {
                            if (isAdminUnlocked != true) return@DisposableEffect onDispose { }
                            val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
                            val alertRef = db.getReference("global_alert")
                            val reportsRef = db.getReference("station_reports")
                            val alertListener = object : com.google.firebase.database.ValueEventListener {
                                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                                    adminGlobalAlertActive = snapshot.child("active").boolOrNull() ?: false
                                    adminGlobalAlertText = snapshot.child("text").stringOrNull() ?: ""
                                }
                                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                            }
                            val reportsListener = object : com.google.firebase.database.ValueEventListener {
                                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                                    val list = mutableListOf<Triple<String, String, Long>>()
                                    for (stationSnap in snapshot.children) {
                                        val sid = stationSnap.key ?: continue
                                        for (issueSnap in stationSnap.children) {
                                            val issue = issueSnap.key ?: continue
                                            val ts = issueSnap.longOrNull() ?: continue
                                            list.add(Triple(sid, issue, ts))
                                        }
                                    }
                                    allStationReports = list.sortedByDescending { it.third }
                                }
                                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                            }
                            alertRef.addValueEventListener(alertListener)
                            reportsRef.addValueEventListener(reportsListener)
                            onDispose {
                                alertRef.removeEventListener(alertListener)
                                reportsRef.removeEventListener(reportsListener)
                            }
                        }

                        if (isAdminUnlocked == null) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                        } else if (isAdminUnlocked == false) {
                            Text("Área Reservada (Equipa)", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Esta conta não tem permissões de administrador.", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            var adminMessage by remember { mutableStateOf("") }
                            
                            Text("Área de Administrador", color = Color.Yellow, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(16.dp))

                            // ── Debug ──
                            Button(
                                onClick = { activity.startActivity(DebugActivity.newIntent(activity)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                            ) {
                                Text("Debug da app", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Ao vivo: serviço e localização, em que metro e em que comboio estás, viagens e distintivos.",
                                color = Color.Gray, fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(24.dp))

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
                                    // Os reports são das estações de metro (antes procurava o nome nas estações CP)
                                    val stName = com.example.metrotime.data.StationsRepo.loadStations(activity).find { it.id == sid }?.name
                                        ?: stations[sid] ?: sid
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
                                            db.getReference("user_stats").child(uid).removeValue()
                                            db.getReference("user_badges").child(uid).removeValue()

                                            // Se o admin resetar a si próprio, limpa a memória local
                                            if (uid == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid) {
                                                com.example.metrotime.data.history.TripHistoryManager.clearHistory(activity)
                                                com.example.metrotime.data.caderneta.CadernetaRepository.reset(activity)
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

/**
 * Viagens de metro detetadas com dúvidas (podiam ter sido a pé): confirmas ou não. As que ficarem sem
 * resposta saem ao fim de 24 h e não contam (antes contavam sozinhas).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingDetectionsDialog(
    activity: ComponentActivity,
    pendingDetections: List<PendingTrip>,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    val hm = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    fun clock(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Europe/Lisbon")).format(hm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Foste de metro?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Estas viagens podiam ter sido a pé. Confirma as que fizeste de metro; as outras não contam.",
                    color = TextSecondaryDark,
                    fontSize = 13.sp
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 380.dp)) {
                    items(pendingDetections, key = { it.trip.id }) { p ->
                        val t = p.trip
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${t.fromName} → ${t.toName ?: "?"}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    Text(
                                        listOfNotNull(
                                            clock(t.startMs) + (t.endMs?.let { " – " + clock(it) } ?: ""),
                                            t.lines.joinToString(" e ") { "Linha $it" }.ifEmpty { null }
                                        ).joinToString(" · "),
                                        color = TextSecondaryDark,
                                        fontSize = 12.sp
                                    )
                                }
                                IconButton(
                                    onClick = { TripHistoryManager.confirmPending(activity, t.id); onRefresh() },
                                    modifier = Modifier.size(36.dp).background(GREEN.copy(alpha = 0.2f), CircleShape)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Sim, fui de metro", tint = GREEN, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = { TripHistoryManager.dismissPending(activity, t.id); onRefresh() },
                                    modifier = Modifier.size(36.dp).background(Color(0xFFFF5252).copy(alpha = 0.2f), CircleShape)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Não", tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = MetrotimeBlue)
            }
        },
        containerColor = DarkBackground
    )
}

@Composable
fun CpVehicleCard(vehicle: CpVehicle, onClick: () -> Unit) {
    val serviceColor = getServiceColor(vehicle.service?.code)
    val delayMin = vehicle.delayMinutes // veículos vêm em segundos; arredondado (59 s = 1 min)
    val delayColor = when {
        delayMin <= 0 -> Color(0xFF4CAF50)
        delayMin in 1..5 -> Color(0xFFFFC107)
        else -> Color(0xFFFF5252)
    }
    val isNullStatus = vehicle.status == "NULL"
    val inferredStatus = if ((vehicle.status == VehicleStatus.NOT_STARTED || vehicle.status == null || isNullStatus) && delayMin > 0) {
        VehicleStatus.IN_TRANSIT
    } else if (isNullStatus) {
        VehicleStatus.IN_TRANSIT
    } else {
        vehicle.status
    }

    val statusText = when (inferredStatus) {
        VehicleStatus.IN_TRANSIT -> "Em trânsito"
        VehicleStatus.AT_STATION -> "Na estação"
        VehicleStatus.AT_ORIGIN -> "Na origem"
        VehicleStatus.NEAR_NEXT -> "Próx. estação"
        VehicleStatus.NOT_STARTED -> "Não iniciado"
        VehicleStatus.COMPLETED -> "Concluído"
        VehicleStatus.CANCELLED -> "Cancelado"
        else -> inferredStatus ?: ""
    }

    val originName = remember(vehicle.origin?.designation) { formatStationName(vehicle.origin?.designation ?: "?") }
    val destinationName = remember(vehicle.destination?.designation) { formatStationName(vehicle.destination?.designation ?: "?") }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .cpStrip(serviceColor)
                .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(serviceColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = getServiceShortName(vehicle.service?.code),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "${vehicle.trainNumber}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                val (chipText, chipColor) = when {
                    inferredStatus == VehicleStatus.CANCELLED -> "Cancelado" to Color(0xFFFF5252)
                    inferredStatus == VehicleStatus.NOT_STARTED -> "Agendado" to Color.Gray
                    vehicle.status == "NULL" && delayMin <= 0 -> "Sem dados" to Color.Gray
                    delayMin <= 0 -> "Na hora" to Color(0xFF4CAF50)
                    else -> "+${delayMin} min" to delayColor
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(chipColor.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = chipText,
                        color = chipColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Origin → Destination
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = originName,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = "  →  ",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Text(
                    text = destinationName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // Scheduled/ETA times
            if (vehicle.originTime != null || vehicle.destinationTime != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        TimeDisplay(scheduled = vehicle.originTime, estimated = vehicle.originETA)
                    }
                    Text(text = "    ", fontSize = 14.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        TimeDisplay(scheduled = vehicle.destinationTime, estimated = vehicle.destinationETA)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Status + service name
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = vehicle.service?.designation ?: "",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun TimeDisplay(scheduled: String?, estimated: String?) {
    if (scheduled == null) return
    if (estimated != null && estimated != scheduled) {
        // Vermelho se atrasado, verde se adiantado
        val schedTime = CpTime.parseHm(scheduled)
        val estTime = CpTime.parseHm(estimated)
        val isEarly = schedTime != null && estTime != null && estTime.isBefore(schedTime) &&
            java.time.Duration.between(estTime, schedTime).toHours() < 12
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = scheduled,
                color = Color.Gray,
                fontSize = 13.sp,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = estimated,
                color = if (isEarly) Color(0xFF4CAF50) else Color(0xFFFF5252),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        Text(
            text = scheduled,
            color = Color.LightGray,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun StaggeredItem(
    delayMillis: Int,
    visible: Boolean,
    animate: Boolean,
    content: @Composable () -> Unit
) {
    if (!animate) {
        content()
        return
    }

    var itemVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(delayMillis.toLong())
            itemVisible = true
        } else {
            itemVisible = false
        }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = itemVisible,
        enter = expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = tween(durationMillis = 420, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        ) + fadeIn(
            animationSpec = tween(durationMillis = 260, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
        ),
        exit = fadeOut(animationSpec = tween(durationMillis = 160))
    ) {
        content()
    }
}


@Composable
fun CpSectionHeader(text: String) {
    Text(
        text = text,
        color = Color.Gray,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp)
    )
}
