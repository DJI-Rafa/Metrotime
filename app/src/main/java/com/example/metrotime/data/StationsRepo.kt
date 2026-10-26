package com.example.metrotime.data

import android.content.Context

object StationsRepo {

    @Volatile private var cached: List<Station>? = null

    fun loadStations(context: Context): List<Station> {
        cached?.let { return it }
        val stations = hardcodedStations()
        cached = stations
        return stations
    }

    /**
     * As coordenadas vêm do MapConfiguration (dados oficiais da API do Metro). Antes estavam
     * escritas aqui à mão e metade estava a mais de 250 m da estação real (Bela Vista a 1,9 km;
     * Alfornelos, Pontinha e Carnide com o mesmo ponto), e faltava Laranjeiras.
     */
    private fun station(id: String, name: String, line: String, apiId: String): Station {
        val coord = MapConfiguration.STATION_COORDS.getValue(apiId)
        return Station(id, name, coord.lat, coord.lon, line, apiId)
    }

    private fun hardcodedStations(): List<Station> = listOf(
        station("reboleira", "Reboleira", "Azul", "RB"),
        station("amadora_este", "Amadora Este", "Azul", "AS"),
        station("alfornelos", "Alfornelos", "Azul", "AF"),
        station("pontinha", "Pontinha", "Azul", "PO"),
        station("carnide", "Carnide", "Azul", "CA"),
        station("colegio_militar", "Colégio Militar/Luz", "Azul", "CM"),
        station("alto_dos_moinhos", "Alto dos Moinhos", "Azul", "AH"),
        station("laranjeiras", "Laranjeiras", "Azul", "LA"),
        station("jardim_zoologico", "Jardim Zoológico", "Azul", "JZ"),
        station("praca_de_espanha", "Praça de Espanha", "Azul", "PE"),
        station("sao_sebastiao", "São Sebastião", "Azul Vermelha", "SS"),
        station("parque", "Parque", "Azul", "PA"),
        station("marques_de_pombal", "Marquês de Pombal", "Azul Amarela", "MP"),
        station("avenida", "Avenida", "Azul", "AV"),
        station("restauradores", "Restauradores", "Azul", "RE"),
        station("baixa_chiado", "Baixa-Chiado", "Azul Verde", "BC"),
        station("terreiro_do_paco", "Terreiro do Paço", "Azul", "TP"),
        station("santa_apolonia", "Santa Apolónia", "Azul", "SP"),
        station("odivelas", "Odivelas", "Amarela", "OD"),
        station("senhor_roubado", "Senhor Roubado", "Amarela", "SR"),
        station("ameixoeira", "Ameixoeira", "Amarela", "AX"),
        station("lumiar", "Lumiar", "Amarela", "LU"),
        station("quinta_das_conchas", "Quinta das Conchas", "Amarela", "QC"),
        station("campo_grande", "Campo Grande", "Amarela Verde", "CG"),
        station("cidade_universitaria", "Cidade Universitária", "Amarela", "CU"),
        station("entre_campos", "Entre Campos", "Amarela", "EC"),
        station("campo_pequeno", "Campo Pequeno", "Amarela", "CP"),
        station("saldanha", "Saldanha", "Amarela Vermelha", "SA"),
        station("picoas", "Picoas", "Amarela", "PI"),
        station("rato", "Rato", "Amarela", "RA"),
        station("telheiras", "Telheiras", "Verde", "TE"),
        station("alvalade", "Alvalade", "Verde", "AL"),
        station("roma", "Roma", "Verde", "RM"),
        station("areeiro", "Areeiro", "Verde", "AE"),
        station("alameda", "Alameda", "Verde Vermelha", "AM"),
        station("arroios", "Arroios", "Verde", "AR"),
        station("anjos", "Anjos", "Verde", "AN"),
        station("intendente", "Intendente", "Verde", "IN"),
        station("martim_moniz", "Martim Moniz", "Verde", "MM"),
        station("rossio", "Rossio", "Verde", "RO"),
        station("cais_do_sodre", "Cais do Sodré", "Verde", "CS"),
        station("aeroporto", "Aeroporto", "Vermelha", "AP"),
        station("encarnacao", "Encarnação", "Vermelha", "EN"),
        station("moscavide", "Moscavide", "Vermelha", "MO"),
        station("oriente", "Oriente", "Vermelha", "OR"),
        station("cabo_ruivo", "Cabo Ruivo", "Vermelha", "CR"),
        station("olivais", "Olivais", "Vermelha", "OS"),
        station("chelas", "Chelas", "Vermelha", "CH"),
        station("bela_vista", "Bela Vista", "Vermelha", "BV"),
        station("olaias", "Olaias", "Vermelha", "OL")
    )
}
