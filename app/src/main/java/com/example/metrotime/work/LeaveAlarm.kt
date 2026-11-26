package com.example.metrotime.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import com.example.metrotime.data.plan.LegMode
import com.example.metrotime.data.plan.PlanLeg
import com.example.metrotime.data.plan.PlanOption
import com.example.metrotime.ui.TripPlannerActivity
import com.example.metrotime.ui.WalkRouteActivity
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Avisar-me quando tiver de sair": para uma viagem escolhida (normalmente a de "chegar às…"), uma notificação
 * 5 minutos antes da hora de sair (o que apanhar, onde e a que horas) e outra na hora. Tocar abre o caminho a pé até
 * onde se apanha o primeiro transporte. Só um aviso de cada vez (um novo substitui o anterior).
 */
object LeaveAlarm {

    const val ACTION = "com.example.metrotime.LEAVE_ALARM"
    const val ACTION_CANCEL = "com.example.metrotime.LEAVE_ALARM_CANCEL"
    private const val EXTRA_STAGE = "stage"
    private const val PREFS = "leave_alarm"
    private const val NOTIF = 4410
    private const val BEFORE_MS = 5 * 60_000L
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val ZONE = ZoneId.of("Europe/Lisbon")

    /** O que fica guardado até tocar. */
    data class Saved(
        val leaveAt: Long,
        val soonText: String,
        val nowText: String,
        val walkName: String?,
        val walkLat: Double?,
        val walkLon: Double?,
        val note: String?
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun what(leg: PlanLeg): String = when (leg.mode) {
        LegMode.METRO -> if (leg.label.startsWith("Linha")) "a ${leg.label}" else "o ${leg.label}"
        LegMode.TRAIN -> if (leg.label == "FT") "o Fertagus" else "o comboio ${leg.label}" + (leg.trainNumber?.let { " $it" } ?: "")
        LegMode.BUS -> "o ${leg.label}"
        LegMode.BOAT -> "o barco"
        LegMode.WALK -> "o caminho a pé"
    }

    /** Os textos dos dois avisos (5 min antes e na hora) e para onde ir a pé. */
    fun describe(option: PlanOption, destination: String): Saved {
        val legs = option.legs
        val first = option.transit.firstOrNull()
        val walk = legs.firstOrNull()?.takeIf { it.mode == LegMode.WALK }
        val leaveAt = legs.first().depart.atZone(ZONE).toInstant().toEpochMilli()
        val arrive = option.arrive.format(HM)
        val soon = when {
            first == null -> "Às ${legs.first().depart.format(HM)} vai a pé até $destination (${option.minutes} min). Chegas às $arrive."
            walk != null -> "Às ${walk.depart.format(HM)} vai a pé até ${first.fromName} (${walk.minutes} min) e apanha ${what(first)} das " +
                "${first.depart.format(HM)}. Chegas a $destination às $arrive."
            else -> "Às ${first.depart.format(HM)} apanha ${what(first)} em ${first.fromName}. Chegas a $destination às $arrive."
        }
        val now = when {
            first == null -> "A pé até $destination: chegas às $arrive."
            walk != null -> "A pé até ${first.fromName}" + (walk.metres?.let { " (${com.example.metrotime.data.plan.TripPlanner.distanceText(it)})" } ?: "") +
                ". ${what(first).replaceFirstChar { it.uppercase() }} parte às ${first.depart.format(HM)}."
            else -> "${what(first).replaceFirstChar { it.uppercase() }} parte às ${first.depart.format(HM)} em ${first.fromName}."
        }
        val target = walk ?: legs.firstOrNull()
        return Saved(
            leaveAt = leaveAt,
            soonText = soon,
            nowText = now,
            walkName = if (first != null) first.fromName else destination,
            walkLat = target?.toLat,
            walkLon = target?.toLon,
            note = first?.let { "${what(it).replaceFirstChar { c -> c.uppercase() }} parte às ${it.depart.format(HM)}" }
        )
    }

    /** Liga o aviso para esta viagem; false se a hora de sair já passou. */
    fun schedule(context: Context, option: PlanOption, destination: String): Boolean {
        val s = describe(option, destination)
        val now = System.currentTimeMillis()
        if (s.leaveAt < now - 60_000L) return false
        prefs(context).edit {
            clear()
            val o = JSONObject()
                .put("leaveAt", s.leaveAt).put("soon", s.soonText).put("now", s.nowText)
                .put("walkName", s.walkName ?: "").put("note", s.note ?: "")
            // (o JSON não aceita NaN: sem coordenadas, as chaves ficam de fora)
            if (s.walkLat != null && s.walkLon != null) o.put("walkLat", s.walkLat).put("walkLon", s.walkLon)
            putString("saved", o.toString())
        }
        if (s.leaveAt - BEFORE_MS > now) set(context, 1, s.leaveAt - BEFORE_MS)
        set(context, 2, maxOf(s.leaveAt, now + 1_000L))
        return true
    }

    fun current(context: Context): Saved? {
        val raw = prefs(context).getString("saved", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Saved(
                o.getLong("leaveAt"), o.getString("soon"), o.getString("now"),
                o.optString("walkName").ifEmpty { null },
                o.optDouble("walkLat").takeIf { !it.isNaN() }, o.optDouble("walkLon").takeIf { !it.isNaN() },
                o.optString("note").ifEmpty { null }
            )
        }.getOrNull()
    }

    fun cancel(context: Context) {
        prefs(context).edit { clear() }
        val am = context.getSystemService(AlarmManager::class.java)
        for (stage in 1..2) am?.cancel(pending(context, stage))
        TravelNotify.manager(context).cancel(NOTIF)
    }

    private fun pending(context: Context, stage: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, NOTIF + stage,
            Intent(context, TravelAlertReceiver::class.java).setAction(ACTION).putExtra(EXTRA_STAGE, stage),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** À hora certa se o telemóvel deixar (alarmes exatos); senão o Android pode atrasar uns minutos. */
    private fun set(context: Context, stage: Int, atMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, stage)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    /** O alarme tocou: o aviso de 5 min antes ([stage] 1) ou o de sair agora (2). */
    fun fire(context: Context, intent: Intent) {
        val s = current(context) ?: return
        val stage = intent.getIntExtra(EXTRA_STAGE, 2)
        val open = if (s.walkLat != null && s.walkLon != null) {
            WalkRouteActivity.newIntent(context, s.walkLat, s.walkLon, s.walkName ?: "o destino", note = s.note)
        } else Intent(context, TripPlannerActivity::class.java)
        val pi = TravelNotify.open(context, NOTIF, open)
        if (stage == 1) {
            TravelNotify.alert(context, NOTIF, "Sai daqui a 5 min", s.soonText, pi)
        } else {
            TravelNotify.alert(context, NOTIF, "Está na hora de sair", s.nowText, pi)
            prefs(context).edit { clear() }
        }
    }
}
