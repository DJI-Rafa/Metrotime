package com.example.metrotime.data

import android.content.Context
import android.content.Intent
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.work.LocationKeepAliveWorker
import com.example.metrotime.work.WidgetUpdateService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

const val FIREBASE_DB_URL = "https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app"

// Leitura tolerante: qualquer utilizador autenticado já conseguiu escrever nestes nós, e
// getValue(X::class.java) lança exceção (e fecha a app) quando o tipo guardado não é o esperado.
fun DataSnapshot.boolOrNull(): Boolean? = value as? Boolean
fun DataSnapshot.stringOrNull(): String? = value as? String
fun DataSnapshot.longOrNull(): Long? = (value as? Number)?.toLong()
fun DataSnapshot.intOrNull(): Int? = (value as? Number)?.toInt()
fun DataSnapshot.doubleOrNull(): Double? = (value as? Number)?.toDouble()

/** Caracteres que o Firebase não aceita em chaves. */
private val INVALID_KEY_CHARS = Regex("[.#$\\[\\]/]")

/** Converte texto livre numa chave válida do Firebase (ex.: o texto de um report de avaria). */
fun firebaseSafeKey(text: String, maxLength: Int = 60): String =
    text.replace(INVALID_KEY_CHARS, " ").replace(Regex("\\s+"), " ").trim().take(maxLength).trim()

/**
 * Dados de outros utilizadores. `users/{uid}` é privado (tem email e data de nascimento);
 * os outros só veem `profiles/{uid}` (nome e foto) e podem procurar alguém pelo email exato
 * através de `email_index`.
 */
object UserDirectory {

    data class PublicProfile(val uid: String, val name: String, val photoUrl: String?, val photoBase64: String?)

    private fun db() = FirebaseDatabase.getInstance(FIREBASE_DB_URL)

    fun emailKey(email: String): String? {
        val key = email.trim().lowercase().replace(".", ",")
        return if (key.isEmpty() || INVALID_KEY_CHARS.containsMatchIn(key)) null else key
    }

    /** Publica o nome e a foto (visíveis aos outros) e o índice do email da conta. */
    fun publishProfile(uid: String, name: String?, email: String?, photoUrl: String?) {
        val profile = mutableMapOf<String, Any>("name" to (name?.trim()?.take(80)?.ifBlank { null } ?: "Utilizador"))
        photoUrl?.takeIf { it.length <= 1000 }?.let { profile["photoUrl"] = it }
        db().getReference("profiles").child(uid).setValue(profile)
        email?.let(::emailKey)?.let { db().getReference("email_index").child(it).setValue(uid) }
    }

    fun publishPhoto(uid: String, base64: String?) {
        val ref = db().getReference("profile_photos").child(uid)
        if (base64 == null) ref.removeValue() else ref.setValue(base64)
    }

    /** Recria o perfil público a partir de `users/{uid}` (migra contas antigas quando abrem a app). */
    suspend fun syncOwnProfile(uid: String, authEmail: String?) {
        val snap = db().getReference("users").child(uid).get().await()
        publishProfile(uid, snap.child("name").stringOrNull(), authEmail, snap.child("photoUrl").stringOrNull())
        snap.child("photoBase64").stringOrNull()?.let { publishPhoto(uid, it) }
    }

    suspend fun getProfile(uid: String, withPhoto: Boolean = false): PublicProfile? {
        val snap = db().getReference("profiles").child(uid).get().await()
        if (!snap.exists()) return null
        val photo = if (withPhoto) {
            runCatching { db().getReference("profile_photos").child(uid).get().await().stringOrNull() }.getOrNull()
        } else null
        return PublicProfile(
            uid = uid,
            name = snap.child("name").stringOrNull() ?: "Utilizador",
            photoUrl = snap.child("photoUrl").stringOrNull(),
            photoBase64 = photo
        )
    }

    suspend fun getAllProfiles(): List<PublicProfile> =
        db().getReference("profiles").get().await().children.mapNotNull { child ->
            val uid = child.key ?: return@mapNotNull null
            PublicProfile(uid, child.child("name").stringOrNull() ?: "Utilizador", child.child("photoUrl").stringOrNull(), null)
        }

    suspend fun findUidByEmail(email: String): String? {
        val key = emailKey(email) ?: return null
        return db().getReference("email_index").child(key).get().await().stringOrNull()
    }

    /** O estatuto de admin vem de `admins/{uid}` (só editável na consola do Firebase). */
    suspend fun refreshAdminFlag(context: Context, uid: String): Boolean {
        val isAdmin = runCatching {
            db().getReference("admins").child(uid).get().await().boolOrNull() == true
        }.getOrDefault(false)
        Prefs.setAdmin(context, isAdmin)
        return isAdmin
    }

    /**
     * Termina a sessão: apaga a localização partilhada, pára o serviço e o worker de background,
     * e só depois sai da conta (antes o serviço continuava a correr e a última posição ficava visível).
     */
    suspend fun signOut(context: Context) {
        val app = context.applicationContext
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            withTimeoutOrNull(3_000) { runCatching { db().getReference("locations").child(uid).removeValue().await() } }
        }
        runCatching { app.stopService(Intent(app, WidgetUpdateService::class.java)) }
        LocationKeepAliveWorker.cancel(app)
        Prefs.setAdmin(app, false)
        FirebaseAuth.getInstance().signOut()
    }
}
