package com.example.metrotime.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.example.metrotime.R
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.cp.CpTripClock
import com.example.metrotime.data.cp.VehicleStatus
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.ui.CpTripDetailActivity
import org.json.JSONObject
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * "Seguir este comboio": enquanto um comboio é seguido, o WidgetUpdateService chama [check] a cada minuto.
 * Mostra uma notificação permanente com o estado e avisa quando o atraso muda, quando a linha de embarque
 * aparece ou muda, quando o comboio é suprimido e uns minutos antes de chegar à estação onde vais sair.
 */
object CpFollow {

    data class Followed(
        val trainNumber: Int,
        val runDate: String,
        val service: String,
        val destination: String?,
        /** Estação onde vais entrar (para avisar da linha de embarque), se se souber. */
        val boardCode: String?,
        /** Estação onde vais sair (aviso de chegada); null = fim do percurso. */
        val alightCode: String?,
        val alightName: String?,
        val startedAt: Long = System.currentTimeMillis()
    )

    private const val PREFS = "cp_follow"
    private const val CHANNEL_STATUS = "cp_follow"
    private const val CHANNEL_ALERTS = "cp_follow_alerts"
    private const val NOTIF_STATUS = 4201
    private const val NOTIF_ALERT = 4202
    private const val ARRIVAL_WARNING_MIN = 3L
    private const val FIRST_DELAY_ALERT_MIN = 5
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    const val ACTION_STOP = "com.example.metrotime.CP_FOLLOW_STOP"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): Followed? {
        val raw = prefs(context).getString("followed", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Followed(
                trainNumber = o.getInt("trainNumber"),
                runDate = o.getString("runDate"),
                service = o.optString("service", "?"),
                destination = o.optString("destination").ifBlank { null },
                boardCode = o.optString("boardCode").ifBlank { null },
                alightCode = o.optString("alightCode").ifBlank { null },
                alightName = o.optString("alightName").ifBlank { null },
                startedAt = o.optLong("startedAt", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    fun isFollowing(context: Context, trainNumber: Int): Boolean = current(context)?.trainNumber == trainNumber

    fun start(context: Context, followed: Followed) {
        val o = JSONObject()
            .put("trainNumber", followed.trainNumber)
            .put("runDate", followed.runDate)
            .put("service", followed.service)
            .put("destination", followed.destination ?: "")
            .put("boardCode", followed.boardCode ?: "")
            .put("alightCode", followed.alightCode ?: "")
            .put("alightName", followed.alightName ?: "")
            .put("startedAt", followed.startedAt)
        // Estado novo: os avisos começam do zero para este comboio
        prefs(context).edit { clear(); putString("followed", o.toString()) }
        // O ciclo do serviço é que vai verificando o comboio
        WidgetUpdateService.requestFetch(context, fromUi = true)
    }

    fun stop(context: Context) {
        prefs(context).edit { clear() }
        manager(context).cancel(NOTIF_STATUS)
    }

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels(context: Context) {
        val m = manager(context)
        m.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Comboio seguido", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Estado do comboio que estás a seguir"
            setShowBadge(false)
        })
        m.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Avisos do comboio seguido", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Atrasos, mudanças de linha, supressões e aviso antes de chegares"
            enableVibration(true)
        })
    }

    /** Verifica o comboio seguido (chamado pelo serviço a cada minuto e pelo worker de 15 em 15 min). */
    suspend fun check(context: Context) {
        val f = current(context) ?: return
        // Ninguém segue um comboio mais de 14 h: esquecer pedidos antigos
        if (System.currentTimeMillis() - f.startedAt > 14 * 3600_000L) { stop(context); return }

        val (trip, date) = try {
            CpRepository.getTripDetail(f.trainNumber, f.runDate)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return // sem rede: tenta no próximo minuto
        }
        val stops = trip.trainStops.orEmpty()
        if (stops.isEmpty()) return
        ensureChannels(context)

        val moments = CpTripClock.moments(date, stops)
        val now = CpTime.now()
        val alightIdx = f.alightCode?.let { code -> stops.indexOfLast { it.station?.code == code } }?.takeIf { it >= 0 } ?: stops.lastIndex
        val alight = stops[alightIdx]
        val alightName = f.alightName ?: formatStationName(alight.station?.designation ?: "")
        val alightAt = moments[alightIdx].arrivalOrDeparture
        val boardIdx = f.boardCode?.let { code -> stops.indexOfFirst { it.station?.code == code } }?.takeIf { it in 0 until alightIdx }
        val boardDeparture = boardIdx?.let { moments[it].departureOrArrival }
        val boarded = boardIdx == null || (boardDeparture != null && now.isAfter(boardDeparture))
        val delay = (alight.delay ?: trip.delay)?.takeIf { it >= 0 }
        val suppressed = trip.status == VehicleStatus.CANCELLED || alight.supression != null ||
            (boardIdx != null && stops[boardIdx].supression != null)
        // Linha que interessa: a de embarque enquanto não entraste, depois a de chegada
        val platformStop = if (!boarded && boardIdx != null) stops[boardIdx] else alight
        val platform = platformStop.platform?.takeIf { it.isNotBlank() && it != "0" }
        val platformWhere = formatStationName(platformStop.station?.designation ?: "")
        val label = "${CpService.longName(f.service)} ${f.trainNumber}"

        // Chegou (ou passou da hora há mais de 10 min): deixa de seguir
        if (alightAt != null && now.isAfter(alightAt.plusMinutes(10)) || trip.status == VehicleStatus.COMPLETED) {
            stop(context)
            return
        }

        val p = prefs(context)
        val alerts = mutableListOf<Pair<String, String>>()
        if (suppressed && !p.getBoolean("suppressedAlerted", false)) {
            alerts += "$label suprimido" to "A CP indica que este comboio não vai fazer o percurso até $alightName."
            p.edit { putBoolean("suppressedAlerted", true) }
        }
        val lastDelay = if (p.contains("lastDelay")) p.getInt("lastDelay", 0) else null
        if (delay != null) {
            // Começou a seguir um comboio que já vai atrasado: avisa logo ("o teu IC vai 12 min atrasado")
            if (lastDelay == null && delay >= FIRST_DELAY_ALERT_MIN) {
                alerts += "$label vai atrasado" to "Vai com +$delay min de atraso. Chega a $alightName às ${alightAt?.format(HM) ?: "?"}."
            }
            if (lastDelay != null && kotlin.math.abs(delay - lastDelay) >= 3) {
                val text = if (delay <= 0) "Já vai a horas." else "Vai com +$delay min de atraso."
                alerts += "$label: atraso mudou" to "$text Chega a $alightName às ${alightAt?.format(HM) ?: "?"}."
            }
            if (lastDelay == null || kotlin.math.abs(delay - lastDelay) >= 3) p.edit { putInt("lastDelay", delay) }
        }
        val lastPlatform = p.getString("lastPlatform", null)
        if (platform != null && platform != lastPlatform) {
            val title = if (lastPlatform == null) "$label: linha $platform" else "$label mudou para a linha $platform"
            alerts += title to "Em $platformWhere${if (lastPlatform != null) " (antes era a linha $lastPlatform)" else ""}."
            p.edit { putString("lastPlatform", platform) }
        }
        if (alightAt != null && !p.getBoolean("arrivalAlerted", false)) {
            val minutes = Duration.between(now, alightAt).toMinutes()
            if (minutes in 0..ARRIVAL_WARNING_MIN && boarded) {
                alerts += "Prepara-te para sair" to "Chegas a $alightName dentro de ${if (minutes <= 0) "instantes" else "$minutes min"}."
                p.edit { putBoolean("arrivalAlerted", true) }
            }
        }

        // Notificação permanente com o estado
        val status = buildString {
            append(if (suppressed) "Suprimido" else delay?.let { if (it <= 0) "A horas" else "+$it min" } ?: "Sem dados em tempo real")
            platform?.let { append(" · Linha $it") }
            alightAt?.let {
                val left = Duration.between(now, it).toMinutes()
                append(" · chega a $alightName às ${it.format(HM)}")
                if (left in 1..180) append(" (em $left min)")
            }
        }
        val open = PendingIntent.getActivity(
            context, NOTIF_STATUS,
            CpTripDetailActivity.newIntent(context, f.trainNumber, date.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            context, NOTIF_STATUS,
            Intent(context, CpFollowReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager(context).notify(NOTIF_STATUS, NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle("$label → ${f.destination ?: formatStationName(stops.last().station?.designation ?: "")}")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Deixar de seguir", stopIntent)
            .build())

        alerts.forEachIndexed { i, (title, text) ->
            manager(context).notify(NOTIF_ALERT + i, NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_metro)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build())
        }
    }
}

/** Botão "Deixar de seguir" da notificação. */
class CpFollowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CpFollow.ACTION_STOP) CpFollow.stop(context)
    }
}
