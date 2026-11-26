package com.example.metrotime.data.plan

import android.content.Context
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.cp.CpNetwork
import com.example.metrotime.data.cp.CpTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDateTime

/**
 * O planeador de todos os transportes: junta o que a app calcula sozinha (metro de Lisboa com tempo real, Carris,
 * Carris Metropolitana e CP), o planeador online (Fertagus, barcos, MobiCascais, TCB, MTS, Porto, comboios em todo o
 * país…) e as viagens diretas nas redes da Eleven (Covilhã, Viseu…). A partir de uma hora mostra a mais rápida
 * primeiro; com "chegar às" mostra a que deixa sair mais tarde e ainda chegar a tempo.
 */
object JourneyPlanner {

    /** As empresas que a app calcula sozinha (com tempo real): as viagens online só com elas ficam de fora. */
    private val LOCAL_AGENCIES = listOf("metropolitano de lisboa", "carris", "comboios de portugal")

    private const val MAX_ALTERNATIVES = 10

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    /** Um bocado que a app já sabe fazer sozinha (a pé, ou numa empresa que ela calcula). */
    internal fun coveredLocally(leg: PlanLeg): Boolean =
        leg.mode == LegMode.WALK || leg.agency == null || LOCAL_AGENCIES.any { leg.agency.lowercase().contains(it) }

    /**
     * Alternativas de [from] para [to]. [at] null = agora; com [arriveBy], [at] é a hora a que se quer chegar e as
     * partidas de cada alternativa vão da mais tarde (a que deixa sair mais tarde) para a mais cedo.
     */
    suspend fun plan(
        context: Context,
        from: Place,
        to: Place,
        at: LocalDateTime?,
        arriveBy: Boolean,
        modes: Set<LegMode>
    ): List<PlanAlternative> = coroutineScope {
        val now = CpTime.now()
        val target = at ?: now
        // O planeador da app vai sempre "a partir de": para chegar a uma hora, procura desde 2 h antes
        val localAt: LocalDateTime? = if (arriveBy) target.minusHours(2).takeIf { it.isAfter(now) } else at
        val localModes = modes - LegMode.BOAT
        val local = async { if (localModes.isEmpty()) emptyList() else safe { TripPlanner.plan(context, from, to, localAt, localModes) } }
        val online = async { safe { OnlinePlanner.plan(from, to, target, arriveBy, modes) } }
        val eleven = async { if (LegMode.BUS in modes) safe { ElevenPlanner.plan(context, from, to, at, arriveBy) } else emptyList() }

        val localAlts = local.await()
        val localHasTransit = localAlts.any { it.first.transit.isNotEmpty() }
        // Online: só as que usam outras empresas (as da app têm tempo real), ou tudo se a app não encontrou nada
        val onlineOptions = online.await().filter { o -> !localHasTransit || o.transit.any { !coveredLocally(it) } }
        val extra = (onlineOptions + eleven.await())
            .groupBy { it.signature }
            .map { (_, opts) -> PlanAlternative(opts.distinctBy { it.key }.sortedBy { it.depart }) }

        val merged = merge(localAlts, extra, arriveBy, target)
        merged.map { fillCoordinates(context, it, from, to) }
    }

    /** Junta as alternativas (as da app ganham quando são a mesma maneira) e ordena-as. */
    internal fun merge(local: List<PlanAlternative>, extra: List<PlanAlternative>, arriveBy: Boolean, target: LocalDateTime): List<PlanAlternative> {
        val all = (local + extra).groupBy { it.signature }.map { (_, g) -> g.first() }
        return if (arriveBy) {
            all.mapNotNull { alt ->
                val ok = alt.options.filter { !it.arrive.isAfter(target) }.sortedByDescending { it.depart }
                if (ok.isEmpty()) null else alt.copy(options = ok)
            }
                // A pé só se não houver outra maneira, ou se for a que deixa sair mais tarde
                .sortedWith(compareByDescending<PlanAlternative> { it.first.depart }.thenBy { it.first.transit.size })
                .take(MAX_ALTERNATIVES)
        } else {
            all.sortedWith(compareBy<PlanAlternative>({ it.first.arrive }, { it.first.transit.size }, { it.first.walkMinutes }))
                .take(MAX_ALTERNATIVES)
        }
    }

    // ── Coordenadas de cada bocado (para o caminho a pé e os avisos) ────────────

    private fun fillCoordinates(context: Context, alt: PlanAlternative, from: Place, to: Place): PlanAlternative {
        val cp by lazy { runCatching { CpNetwork.stations(context) }.getOrDefault(emptyList()).associateBy { it.code } }
        fun metro(id: String?) = id?.let { MapConfiguration.STATION_COORDS[it] }?.let { it.lat to it.lon }
        fun cpAt(code: String?) = code?.let { cp[it] }?.let { it.lat to it.lon }
        return alt.copy(options = alt.options.map { option ->
            // Primeiro os veículos (de onde partem e onde chegam)…
            val legs = option.legs.map { leg ->
                if (leg.fromLat != null && leg.toLat != null) return@map leg
                val (start, end) = when (leg.mode) {
                    LegMode.METRO -> metro(leg.metroStations.firstOrNull()) to metro(leg.metroStations.lastOrNull())
                    LegMode.TRAIN -> cpAt(leg.fromCode) to cpAt(leg.toCode)
                    LegMode.BUS, LegMode.BOAT -> leg.busBoardLat?.let { lat -> leg.busBoardLon?.let { lat to it } } to
                        leg.busAlightLat?.let { lat -> leg.busAlightLon?.let { lat to it } }
                    LegMode.WALK -> null to null
                }
                leg.copy(
                    fromLat = leg.fromLat ?: start?.first, fromLon = leg.fromLon ?: start?.second,
                    toLat = leg.toLat ?: end?.first, toLon = leg.toLon ?: end?.second
                )
            }
            // …depois os bocados a pé, entre o anterior e o seguinte
            val walked = legs.mapIndexed { i, leg ->
                if (leg.mode != LegMode.WALK || (leg.fromLat != null && leg.toLat != null)) return@mapIndexed leg
                val prev = legs.getOrNull(i - 1)
                val next = legs.getOrNull(i + 1)
                val start = if (prev == null) from.lat to from.lon else prev.toLat?.let { lat -> prev.toLon?.let { lat to it } }
                val end = if (next == null) to.lat to to.lon else next.fromLat?.let { lat -> next.fromLon?.let { lat to it } }
                leg.copy(
                    fromLat = leg.fromLat ?: start?.first, fromLon = leg.fromLon ?: start?.second,
                    toLat = leg.toLat ?: end?.first, toLon = leg.toLon ?: end?.second
                )
            }
            option.copy(legs = walked)
        })
    }
}
