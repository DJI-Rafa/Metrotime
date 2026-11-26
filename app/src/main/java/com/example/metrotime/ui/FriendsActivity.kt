package com.example.metrotime.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.ui.theme.CardBackground
import com.example.metrotime.ui.theme.DarkBackground
import com.example.metrotime.ui.theme.MetrotimeTheme
import com.example.metrotime.ui.theme.Red40
import com.example.metrotime.data.FIREBASE_DB_URL
import com.example.metrotime.data.UserDirectory
import com.example.metrotime.data.stringOrNull
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.delay

class FriendsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    FriendsScreen(
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

data class UserProfile(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val status: String = "" // "accepted", "pending_received", "pending_sent", "none"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(onBack: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var friendsList by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var selectedFriendForMessage by remember { mutableStateOf<UserProfile?>(null) }
    var customMessageText by remember { mutableStateOf("") }
    
    val currentUser = FirebaseAuth.getInstance().currentUser
    val currentUid = currentUser?.uid ?: return

    // Firebase refs
    val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
    val friendsRef = db.getReference("friends").child(currentUid)
    // Estado das amizades (uid -> estado), atualizado pelo listener
    var friendStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Listen to friends list (listener removido ao sair do ecrã)
    DisposableEffect(currentUid) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                friendStatuses = snapshot.children.mapNotNull { child ->
                    val status = child.stringOrNull() ?: return@mapNotNull null
                    child.key?.let { it to status }
                }.toMap()
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        friendsRef.addValueEventListener(listener)
        onDispose { friendsRef.removeEventListener(listener) }
    }

    // Perfis carregados de uma vez por cada mudança: um pedido antigo já não sobrescreve o mais recente
    LaunchedEffect(friendStatuses) {
        friendsList = friendStatuses.map { (uid, status) ->
            val profile = runCatching { UserDirectory.getProfile(uid) }.getOrNull()
            UserProfile(uid, profile?.name ?: "Desconhecido", "", status)
        }
    }

    // Search logic: por nome nos perfis públicos, ou por email exato (os emails já não são públicos)
    LaunchedEffect(searchQuery, friendStatuses) {
        val query = searchQuery.trim()
        if (query.length < 3) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300) // debounce
        val found = runCatching {
            if (query.contains("@")) {
                val uid = UserDirectory.findUidByEmail(query)
                listOfNotNull(uid?.let { UserDirectory.getProfile(it) ?: UserDirectory.PublicProfile(it, "Utilizador", null, null) })
            } else {
                UserDirectory.getAllProfiles().filter { it.name.contains(query, ignoreCase = true) }
            }
        }.getOrDefault(emptyList())
        searchResults = found
            .filter { it.uid != currentUid }
            .map { UserProfile(it.uid, it.name, "", friendStatuses[it.uid] ?: "none") }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Amigos", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Nome ou email exato...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF3B82F6),
                unfocusedBorderColor = Color(0xFF3A3A3A),
                focusedContainerColor = CardBackground,
                unfocusedContainerColor = CardBackground,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // SHOW SEARCH RESULTS
            if (searchQuery.length >= 3) {
                item {
                    Text("Resultados da pesquisa", color = Color.Gray, fontSize = 14.sp)
                }
                if (searchResults.isEmpty()) {
                    item {
                        Text("Nenhum utilizador encontrado.", color = Color.White, modifier = Modifier.padding(vertical = 8.dp))
                    }
                } else {
                    items(searchResults) { user ->
                        UserCard(
                            user = user, 
                            currentUid = currentUid,
                            onActionComplete = { searchQuery = "" }
                        )
                    }
                }
            } else {
                // SHOW FRIEND REQUESTS
                val pendingRequests = friendsList.filter { it.status == "pending_received" }
                if (pendingRequests.isNotEmpty()) {
                    item {
                        Text("Pedidos de Amizade", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    items(pendingRequests) { user ->
                        UserCard(user = user, currentUid = currentUid, onActionComplete = {})
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }

                // SHOW FRIENDS
                val friends = friendsList.filter { it.status == "accepted" }
                item {
                    Text("Meus Amigos (${friends.size})", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (friends.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Ainda não tens amigos adicionados.", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                } else {
                    items(friends) { user ->
                        UserCard(user = user, currentUid = currentUid, onActionComplete = {}, onClick = { 
                            selectedFriendForMessage = user
                            // Fetch existing message
                            db.getReference("users").child(currentUid).child("friendSettings").child(user.uid).child("customMessage")
                                .get().addOnSuccessListener { snap ->
                                    customMessageText = snap.stringOrNull() ?: ""
                                }
                        })
                    }
                }
            }
        }

        // Custom Message Dialog
        if (selectedFriendForMessage != null) {
            AlertDialog(
                onDismissRequest = { selectedFriendForMessage = null },
                title = { Text("Personalizar Notificação", color = Color.White) },
                text = {
                    Column {
                        Text("Escreve a mensagem que queres receber quando o ${selectedFriendForMessage!!.name} andar de comboio.", color = Color.Gray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Usa os códigos:\n{name} = Nome\n{train} = Comboio\n{dest} = Destino", color = Color(0xFF3B82F6), fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = customMessageText,
                            onValueChange = { customMessageText = it },
                            placeholder = { Text("Ex: Cuidado, o {name} vai no {train}!") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        val context = androidx.compose.ui.platform.LocalContext.current
                        Button(
                            onClick = {
                                val intent = android.content.Intent(context, StatsActivity::class.java).apply {
                                    putExtra(StatsActivity.EXTRA_FRIEND_UID, selectedFriendForMessage!!.uid)
                                    putExtra(StatsActivity.EXTRA_FRIEND_NAME, selectedFriendForMessage!!.name)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("📊 Ver Estatísticas de ${selectedFriendForMessage!!.name}", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val uid = selectedFriendForMessage!!.uid
                        if (customMessageText.isBlank()) {
                            db.getReference("users").child(currentUid).child("friendSettings").child(uid).child("customMessage").removeValue()
                        } else {
                            db.getReference("users").child(currentUid).child("friendSettings").child(uid).child("customMessage").setValue(customMessageText)
                        }
                        selectedFriendForMessage = null
                    }) {
                        Text("Guardar", color = Color(0xFF3B82F6))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedFriendForMessage = null }) {
                        Text("Cancelar", color = Color.Gray)
                    }
                },
                containerColor = DarkBackground
            )
        }
    }
}

@Composable
fun UserCard(user: UserProfile, currentUid: String, onActionComplete: () -> Unit, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3B82F6)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.name.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(text = user.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                // Removed email display for privacy
            }

            // Actions based on status
            when (user.status) {
                "none" -> {
                    IconButton(onClick = { sendFriendRequest(currentUid, user.uid); onActionComplete() }) {
                        Icon(Icons.Default.Person, contentDescription = "Add Friend", tint = Color(0xFF3B82F6))
                    }
                }
                "pending_sent" -> {
                    Text("Pendente", color = Color.Gray, fontSize = 12.sp)
                }
                "pending_received" -> {
                    Row {
                        IconButton(onClick = { acceptFriendRequest(currentUid, user.uid) }) {
                            Icon(Icons.Default.Check, contentDescription = "Accept", tint = Color(0xFF10B981))
                        }
                        IconButton(onClick = { removeFriend(currentUid, user.uid) }) {
                            Icon(Icons.Default.Close, contentDescription = "Reject", tint = Red40)
                        }
                    }
                }
                "accepted" -> {
                    IconButton(onClick = { removeFriend(currentUid, user.uid) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Gray)
                    }
                }
            }
        }
    }
}

// --- FIREBASE HELPER FUNCTIONS ---

fun sendFriendRequest(currentUid: String, targetUid: String) {
    val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app").reference
    db.child("friends").child(currentUid).child(targetUid).setValue("pending_sent")
    db.child("friends").child(targetUid).child(currentUid).setValue("pending_received")
}

fun acceptFriendRequest(currentUid: String, targetUid: String) {
    val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app").reference
    db.child("friends").child(currentUid).child(targetUid).setValue("accepted")
    db.child("friends").child(targetUid).child(currentUid).setValue("accepted")
}

fun removeFriend(currentUid: String, targetUid: String) {
    val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app").reference
    db.child("friends").child(currentUid).child(targetUid).removeValue()
    db.child("friends").child(targetUid).child(currentUid).removeValue()
}
