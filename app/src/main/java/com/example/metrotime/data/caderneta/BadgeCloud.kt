package com.example.metrotime.data.caderneta

import android.content.Context
import com.example.metrotime.data.FIREBASE_DB_URL
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

/**
 * Os distintivos no Firebase, em user_badges/<uid>: os ganhos com o dia em que foram ganhos e o texto da fita. Assim
 * não se perdem (outro telemóvel, app apagada), os amigos podem vê-los e dá para saber quantas pessoas têm cada um
 * (badge_counts, que uma Cloud Function vai contando).
 */
object BadgeCloud {

    private val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")
    private const val TIMEOUT_MS = 8_000L
    private const val COUNTS_TTL_MS = 10 * 60 * 1000L

    /** Os distintivos de alguém: id → dia em que foi ganho (dia desde 1970), e o texto da fita. */
    class Earned(val days: Map<String, Long>, val labels: Map<String, String>)

    /** Quantas pessoas têm cada distintivo, e quantas têm distintivos guardados. */
    class Counts(val users: Int, private val byBadge: Map<String, Int>) {
        fun of(id: String): Int = byBadge[id] ?: 0
        fun text(id: String, mine: Boolean): String? = ShareText.holders(of(id), users, mine)
    }

    @Volatile private var cachedCounts: Pair<Long, Counts>? = null

    private fun db() = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
    private fun myUid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    private fun parse(snap: DataSnapshot): Earned = Earned(
        snap.child("earned").children.mapNotNull { c ->
            val id = c.key ?: return@mapNotNull null
            (c.value as? Number)?.toLong()?.let { id to it }
        }.toMap(),
        snap.child("labels").children.mapNotNull { c ->
            val id = c.key ?: return@mapNotNull null
            (c.value as? String)?.let { id to it }
        }.toMap()
    )

    /** Os distintivos de outra pessoa (um amigo); null se não der para ler. */
    suspend fun of(uid: String): Earned? = runCatching {
        withTimeoutOrNull(TIMEOUT_MS) {
            val e = parse(db().getReference("user_badges").child(uid).get().await())
            Earned(e.days.filterKeys { Badges.trusted(it, e.labels[it]) }, e.labels)
        }
    }.getOrNull()

    /**
     * Junta os distintivos ganhos neste telemóvel com os do Firebase: os novos sobem, e os que só lá estão ficam ganhos
     * aqui também (sem se festejarem outra vez). Devolve true se vieram distintivos do Firebase (a caderneta mudou).
     */
    suspend fun sync(context: Context, caderneta: Caderneta): Boolean {
        val uid = myUid() ?: return false
        val ref = db().getReference("user_badges").child(uid)
        val snap = runCatching { withTimeoutOrNull(TIMEOUT_MS) { ref.get().await() } }.getOrNull() ?: return false
        val cloud = parse(snap)
        // Os ganhos com uma regra que estava errada saem (sobem outra vez se forem ganhos a sério)
        val trustedDays = cloud.days.filterKeys { Badges.trusted(it, cloud.labels[it]) }
        val trustedLabels = cloud.labels.filterKeys { it in trustedDays }
        val local = caderneta.badges.filter { it.earned }
        val today = LocalDate.now(ZONE).toEpochDay()
        val days = HashMap(trustedDays)
        val labels = HashMap(trustedLabels)
        for (b in local) {
            val day = CadernetaRepository.earnedDate(context, b.def.id)?.toEpochDay() ?: today
            days[b.def.id] = minOf(days[b.def.id] ?: day, day)
            if (b.label.isNotBlank()) labels[b.def.id] = b.label
        }
        val restored = CadernetaRepository.restore(context, trustedDays, trustedLabels, local.map { it.def.id }.toSet())
        if (!snap.exists() || days != cloud.days || labels != cloud.labels) {
            // Sem rede fica em fila e sobe quando houver
            runCatching { ref.setValue(mapOf("earned" to days, "labels" to labels, "updatedAt" to ServerValue.TIMESTAMP)) }
        }
        return restored
    }

    /** Quantas pessoas têm cada distintivo (guardado 10 min); null se não der para ler. */
    suspend fun counts(): Counts? {
        cachedCounts?.let { (at, c) -> if (System.currentTimeMillis() - at < COUNTS_TTL_MS) return c }
        val snap = runCatching { withTimeoutOrNull(TIMEOUT_MS) { db().getReference("badge_counts").get().await() } }.getOrNull()
            ?: return cachedCounts?.second
        val users = (snap.child("_users").value as? Number)?.toInt() ?: 0
        val byBadge = snap.children.mapNotNull { c ->
            val id = c.key ?: return@mapNotNull null
            if (id.startsWith("_")) null else (c.value as? Number)?.toInt()?.let { id to it }
        }.toMap()
        return Counts(users, byBadge).also { cachedCounts = System.currentTimeMillis() to it }
    }
}
