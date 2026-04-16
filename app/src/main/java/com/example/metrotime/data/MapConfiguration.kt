package com.example.metrotime.data

/**
 * Ground truth for station coordinates (from official Lisbon Metro API).
 * Also defines the ordered sequence of station IDs for each line,
 * which is used to draw GeoJSON routes and estimate train positions.
 */
object MapConfiguration {

    data class StationCoord(val id: String, val name: String, val lat: Double, val lon: Double)

    val STATION_COORDS: Map<String, StationCoord> = listOf(
        StationCoord("AM", "Alameda",             38.7373,  -9.13409),
        StationCoord("AF", "Alfornelos",           38.7606,  -9.20471),
        StationCoord("AH", "Alto dos Moinhos",     38.7496,  -9.17995),
        StationCoord("AL", "Alvalade",             38.7535,  -9.14388),
        StationCoord("AS", "Amadora Este",         38.7584,  -9.21917),
        StationCoord("AX", "Ameixoeira",           38.7799,  -9.15999),
        StationCoord("AN", "Anjos",                38.7266,  -9.13503),
        StationCoord("AE", "Areeiro",              38.7426,  -9.13381),
        StationCoord("AR", "Arroios",              38.7335,  -9.13445),
        StationCoord("AV", "Avenida",              38.7201,  -9.14582),
        StationCoord("BC", "Baixa-Chiado",         38.7107,  -9.13909),
        StationCoord("BV", "Bela Vista",           38.7477,  -9.11855),
        StationCoord("CR", "Cabo Ruivo",           38.7632,  -9.10409),
        StationCoord("CS", "Cais do Sodré",        38.7062,  -9.14503),
        StationCoord("CG", "Campo Grande",         38.7599,  -9.15794),
        StationCoord("CP", "Campo Pequeno",        38.7414,  -9.14703),
        StationCoord("CA", "Carnide",              38.7593,  -9.19281),
        StationCoord("CH", "Chelas",               38.7553,  -9.11414),
        StationCoord("CU", "Cidade Universitária", 38.7519,  -9.15863),
        StationCoord("CM", "Colégio Militar/Luz",  38.7533,  -9.18866),
        StationCoord("EC", "Entre Campos",         38.7479,  -9.14856),
        StationCoord("IN", "Intendente",           38.7222,  -9.13531),
        StationCoord("JZ", "Jardim Zoológico",     38.7422,  -9.16872),
        StationCoord("LA", "Laranjeiras",          38.7485,  -9.17243),
        StationCoord("LU", "Lumiar",               38.7728,  -9.15970),
        StationCoord("MP", "Marquês de Pombal",    38.7249,  -9.15081),
        StationCoord("MM", "Martim Moniz",         38.7168,  -9.13575),
        StationCoord("OD", "Odivelas",             38.7932,  -9.17322),
        StationCoord("OL", "Olaias",               38.7392,  -9.12366),
        StationCoord("OS", "Olivais",              38.7613,  -9.11204),
        StationCoord("OR", "Oriente",              38.7678,  -9.09977),
        StationCoord("PA", "Parque",               38.7297,  -9.15028),
        StationCoord("PI", "Picoas",               38.7306,  -9.14650),
        StationCoord("PO", "Pontinha",             38.7624,  -9.19693),
        StationCoord("PE", "Praça de Espanha",     38.7377,  -9.15845),
        StationCoord("QC", "Quinta das Conchas",   38.7671,  -9.15546),
        StationCoord("RA", "Rato",                 38.7201,  -9.15411),
        StationCoord("RE", "Restauradores",        38.7151,  -9.14162),
        StationCoord("RM", "Roma",                 38.7485,  -9.14135),
        StationCoord("RO", "Rossio",               38.7138,  -9.13896),
        StationCoord("SA", "Saldanha",             38.7353,  -9.14558),
        StationCoord("SP", "Santa Apolónia",       38.7138,  -9.12256),
        StationCoord("SS", "São Sebastião",        38.7348,  -9.15423),
        StationCoord("SR", "Senhor Roubado",       38.7858,  -9.17215),
        StationCoord("TE", "Telheiras",            38.7604,  -9.16606),
        StationCoord("TP", "Terreiro do Paço",     38.7072,  -9.13335),
        StationCoord("MO", "Moscavide",            38.7748,  -9.10266),
        StationCoord("EN", "Encarnação",           38.7750,  -9.11498),
        StationCoord("AP", "Aeroporto",            38.7686,  -9.12833),
        StationCoord("RB", "Reboleira",            38.7522,  -9.22414),
    ).associateBy { it.id }

    /** Ordered station IDs for each line (Reboleira → Santa Apolónia etc.) */
    val LINE_STATION_ORDER: Map<String, List<String>> = mapOf(
        "Azul" to listOf("RB", "AS", "AF", "PO", "CA", "CM", "AH", "LA", "JZ", "PE", "SS", "PA", "MP", "AV", "RE", "BC", "TP", "SP"),
        "Amarela" to listOf("OD", "SR", "AX", "LU", "QC", "CG", "CU", "EC", "CP", "SA", "PI", "MP", "RA"),
        "Verde" to listOf("TE", "CG", "AL", "RM", "AE", "AM", "AR", "AN", "IN", "MM", "RO", "BC", "CS"),
        "Vermelha" to listOf("SS", "SA", "AM", "OL", "BV", "CH", "OS", "CR", "OR", "MO", "EN", "AP")
    )

    /** Average travel time per segment in seconds (~2 min) */
    const val SEGMENT_TRAVEL_SECONDS = 120

    /** Map from destination name to list of stations visited going in that direction */
    val DESTINATION_TO_ORDERED_STOPS: Map<String, Pair<String, List<String>>> by lazy {
        buildDestinationMap()
    }

    private fun buildDestinationMap(): Map<String, Pair<String, List<String>>> {
        val result = mutableMapOf<String, Pair<String, List<String>>>()
        for ((lineName, stations) in LINE_STATION_ORDER) {
            // Forward direction: destination = last station
            val forwardDest = STATION_COORDS[stations.last()]?.name ?: continue
            result[forwardDest] = Pair(lineName, stations)
            // Reverse direction: destination = first station  
            val reverseDest = STATION_COORDS[stations.first()]?.name ?: continue
            result[reverseDest] = Pair(lineName, stations.reversed())
        }
        return result
    }
}
