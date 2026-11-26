package com.example.metrotime.data.plan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * O caminho a pé pelas ruas, com as viragens: o encaminhamento a pé do OpenStreetMap (OSRM da FOSSGIS). Sem rede,
 * uma linha reta com a distância e o tempo a 80 m por minuto, como no resto da app.
 */
object WalkRouter {

    private const val URL = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/"
    private const val USER_AGENT = "Metrotime/3.9 (app de transportes)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Route(
        val path: List<Pair<Double, Double>>,
        val metres: Double,
        val seconds: Int,
        val steps: List<WalkStep>,
        /** false: linha reta (sem o caminho pelas ruas). */
        val streets: Boolean
    )

    /** De [from] a [to] (lat, lon); [toName] para a última indicação ("Chegaste a Rossio"). */
    suspend fun route(from: Pair<Double, Double>, to: Pair<Double, Double>, toName: String): Route = withContext(Dispatchers.IO) {
        val url = URL + "${from.second},${from.first};${to.second},${to.first}?overview=full&geometries=geojson&steps=true"
        val json = runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
        json?.let { runCatching { parse(it, toName) }.getOrNull() } ?: straight(from, to, toName)
    }

    /** Sem o caminho pelas ruas: em linha reta. */
    fun straight(from: Pair<Double, Double>, to: Pair<Double, Double>, toName: String): Route {
        val m = TripPlanner.metres(from.first, from.second, to.first, to.second)
        return Route(
            listOf(from, to), m, Walk.minutes(m) * 60,
            listOf(
                WalkStep("Segue em direção a $toName", m, WalkStep.Turn.STRAIGHT),
                WalkStep("Chegaste a $toName", 0.0, WalkStep.Turn.ARRIVE)
            ),
            streets = false
        )
    }

    /** A resposta do OSRM em caminho e indicações em português (as viragens seguidas na mesma rua juntam-se). */
    internal fun parse(json: String, toName: String): Route? {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") return null
        val r = root.optJSONArray("routes")?.optJSONObject(0) ?: return null
        val coords = r.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val path = (0 until coords.length()).mapNotNull { i ->
            coords.optJSONArray(i)?.let { c -> c.optDouble(1) to c.optDouble(0) }
        }
        val steps = ArrayList<WalkStep>()
        val legs = r.optJSONArray("legs")
        for (li in 0 until (legs?.length() ?: 0)) {
            val s = legs!!.optJSONObject(li)?.optJSONArray("steps") ?: continue
            for (k in 0 until s.length()) {
                val step = s.optJSONObject(k) ?: continue
                val m = step.optJSONObject("maneuver") ?: JSONObject()
                val street = step.optString("name").trim()
                val metres = step.optDouble("distance", 0.0)
                val (turn, text) = instruction(m.optString("type"), m.optString("modifier"), street, toName)
                val last = steps.lastOrNull()
                // "Continua" na mesma rua: soma ao anterior
                if (last != null && turn == WalkStep.Turn.STRAIGHT && text == last.text) {
                    steps[steps.lastIndex] = last.copy(metres = last.metres + metres)
                } else steps += WalkStep(text, metres, turn)
            }
        }
        // Os bocados minúsculos (menos de 8 m, sem nome) só confundem
        val cleaned = steps.filterIndexed { i, s -> s.turn == WalkStep.Turn.ARRIVE || i == 0 || s.metres >= 8 }
        return Route(path, r.optDouble("distance", 0.0), r.optDouble("duration", 0.0).toInt(), cleaned, streets = true)
    }

    private fun onto(street: String) = if (street.isEmpty()) "" else " para ${artigo(street)}$street"

    private fun along(street: String): String = when {
        street.isEmpty() -> "Segue em frente"
        artigo(street) == "a " -> "Segue pela $street"
        artigo(street) == "o " -> "Segue pelo $street"
        else -> "Segue por $street"
    }

    /** "a Rua Augusta", "o Largo do Rato", "a Avenida…" (o artigo pelo tipo de rua). */
    private fun artigo(street: String): String {
        val first = street.substringBefore(" ").lowercase()
        val fem = setOf("rua", "avenida", "av.", "travessa", "praça", "estrada", "calçada", "escadinhas", "alameda", "azinhaga", "rampa", "via", "ponte", "vila", "quinta", "praceta", "costa", "ladeira", "rotunda")
        val masc = setOf("largo", "beco", "caminho", "passeio", "pátio", "terreiro", "parque", "jardim", "cais", "túnel", "viaduto", "bairro", "adro", "boqueirão")
        return when (first) {
            in fem -> "a "
            in masc -> "o "
            else -> ""
        }
    }

    /** A viragem e o texto de uma manobra do OSRM. */
    internal fun instruction(type: String, modifier: String, street: String, toName: String): Pair<WalkStep.Turn, String> {
        val turn = when (modifier) {
            "left" -> WalkStep.Turn.LEFT
            "right" -> WalkStep.Turn.RIGHT
            "slight left" -> WalkStep.Turn.SLIGHT_LEFT
            "slight right" -> WalkStep.Turn.SLIGHT_RIGHT
            "sharp left" -> WalkStep.Turn.SHARP_LEFT
            "sharp right" -> WalkStep.Turn.SHARP_RIGHT
            "uturn" -> WalkStep.Turn.UTURN
            else -> WalkStep.Turn.STRAIGHT
        }
        return when (type) {
            "arrive" -> WalkStep.Turn.ARRIVE to "Chegaste a $toName"
            "depart" -> WalkStep.Turn.STRAIGHT to along(street)
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" ->
                WalkStep.Turn.ROUNDABOUT to "Na rotunda, sai${onto(street)}".trimEnd()
            "continue", "new name", "notification" ->
                if (turn == WalkStep.Turn.STRAIGHT) WalkStep.Turn.STRAIGHT to along(street) else turn to turnText(turn, street)
            else -> if (turn == WalkStep.Turn.STRAIGHT) WalkStep.Turn.STRAIGHT to along(street) else turn to turnText(turn, street)
        }
    }

    private fun turnText(turn: WalkStep.Turn, street: String): String = when (turn) {
        WalkStep.Turn.LEFT -> "Vira à esquerda"
        WalkStep.Turn.RIGHT -> "Vira à direita"
        WalkStep.Turn.SLIGHT_LEFT -> "Ligeiramente à esquerda"
        WalkStep.Turn.SLIGHT_RIGHT -> "Ligeiramente à direita"
        WalkStep.Turn.SHARP_LEFT -> "Vira bem à esquerda"
        WalkStep.Turn.SHARP_RIGHT -> "Vira bem à direita"
        WalkStep.Turn.UTURN -> "Volta para trás"
        else -> "Segue em frente"
    } + onto(street)
}
