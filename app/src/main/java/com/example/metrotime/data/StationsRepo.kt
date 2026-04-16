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

    private fun hardcodedStations(): List<Station> = listOf(
        Station("reboleira", "Reboleira", 38.7496, -9.2255, "Azul", "RB"),
        Station("amadora_este", "Amadora Este", 38.7562, -9.2173, "Azul", "AS"),
        Station("alfornelos", "Alfornelos", 38.7612, -9.2057, "Azul", "AF"),
        Station("pontinha", "Pontinha", 38.7612, -9.2057, "Azul", "PO"),
        Station("carnide", "Carnide", 38.7612, -9.2057, "Azul", "CA"),
        Station("colegio_militar", "Colégio Militar/Luz", 38.7518, -9.186, "Azul", "CM"),
        Station("alto_dos_moinhos", "Alto dos Moinhos", 38.749, -9.1793, "Azul", "AH"),
        Station("jardim_zoologico", "Jardim Zoológico", 38.741, -9.1729, "Azul", "JZ"),
        Station("praca_de_espanha", "Praça de Espanha", 38.7363, -9.1601, "Azul", "PE"),
        Station("sao_sebastiao", "São Sebastião", 38.7303, -9.1557, "Azul Vermelha", "SS"),
        Station("parque", "Parque", 38.7259, -9.155, "Azul", "PA"),
        Station("marques_de_pombal", "Marquês de Pombal", 38.7253, -9.1502, "Azul Amarela", "MP"),
        Station("avenida", "Avenida", 38.7196, -9.145, "Azul", "AV"),
        Station("restauradores", "Restauradores", 38.7142, -9.1424, "Azul", "RE"),
        Station("baixa_chiado", "Baixa-Chiado", 38.7107, -9.1404, "Azul Verde", "BC"),
        Station("terreiro_do_paco", "Terreiro do Paço", 38.7067, -9.1354, "Azul", "TP"),
        Station("santa_apolonia", "Santa Apolónia", 38.7142, -9.124, "Azul", "SP"),
        Station("odivelas", "Odivelas", 38.7937, -9.1856, "Amarela", "OD"),
        Station("senhor_roubado", "Senhor Roubado", 38.784, -9.1798, "Amarela", "SR"),
        Station("ameixoeira", "Ameixoeira", 38.7774, -9.1673, "Amarela", "AX"),
        Station("lumiar", "Lumiar", 38.7719, -9.1638, "Amarela", "LU"),
        Station("quinta_das_conchas", "Quinta das Conchas", 38.7656, -9.1584, "Amarela", "QC"),
        Station("campo_grande", "Campo Grande", 38.7555, -9.1583, "Amarela Verde", "CG"),
        Station("cidade_universitaria", "Cidade Universitária", 38.749, -9.1596, "Amarela", "CU"),
        Station("entre_campos", "Entre Campos", 38.7466, -9.1506, "Amarela", "EC"),
        Station("campo_pequeno", "Campo Pequeno", 38.7416, -9.1466, "Amarela", "CP"),
        Station("saldanha", "Saldanha", 38.735, -9.145, "Amarela Vermelha", "SA"),
        Station("picoas", "Picoas", 38.7299, -9.1464, "Amarela", "PI"),
        Station("rato", "Rato", 38.7202, -9.1543, "Amarela", "RA"),
        Station("telheiras", "Telheiras", 38.7601, -9.1706, "Verde", "TE"),
        Station("alvalade", "Alvalade", 38.7523, -9.143, "Verde", "AL"),
        Station("roma", "Roma", 38.747, -9.1418, "Verde", "RM"),
        Station("areeiro", "Areeiro", 38.7423, -9.1332, "Verde", "AE"),
        Station("alameda", "Alameda", 38.737, -9.1358, "Verde Vermelha", "AM"),
        Station("arroios", "Arroios", 38.7303, -9.1368, "Verde", "AR"),
        Station("anjos", "Anjos", 38.7258, -9.136, "Verde", "AN"),
        Station("intendente", "Intendente", 38.7214, -9.1365, "Verde", "IN"),
        Station("martim_moniz", "Martim Moniz", 38.7169, -9.137, "Verde", "MM"),
        Station("rossio", "Rossio", 38.7139, -9.1396, "Verde", "RO"),
        Station("cais_do_sodre", "Cais do Sodré", 38.7058, -9.1454, "Verde", "CS"),
        Station("aeroporto", "Aeroporto", 38.7756, -9.129, "Vermelha", "AP"),
        Station("encarnacao", "Encarnação", 38.7733, -9.1171, "Vermelha", "EN"),
        Station("moscavide", "Moscavide", 38.7735, -9.105, "Vermelha", "MO"),
        Station("oriente", "Oriente", 38.7679, -9.0999, "Vermelha", "OR"),
        Station("cabo_ruivo", "Cabo Ruivo", 38.76, -9.1056, "Vermelha", "CR"),
        Station("olivais", "Olivais", 38.7599, -9.1205, "Vermelha", "OS"),
        Station("chelas", "Chelas", 38.7562, -9.128, "Vermelha", "CH"),
        Station("bela_vista", "Bela Vista", 38.7628, -9.129, "Vermelha", "BV"),
        Station("olaias", "Olaias", 38.744, -9.131, "Vermelha", "OL")
    )
}
