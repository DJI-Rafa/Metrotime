package com.example.metrotime.data.bus

/**
 * Cores das linhas de autocarro. A GTFS da Carris não traz route_color (vem vazia nas 176 variantes),
 * por isso as cores da Carris são as do site (carris.pt/viaje/carreiras, outubro de 2026), uma por zona
 * ou rede. As da Carris Metropolitana vêm da própria API (campo "color"). Sem dependências Android.
 */
object BusColors {

    /** Cinzento das circulares: também serve para linhas que o site não mostra. */
    const val FALLBACK = "#8C8C99"

    private val CARRIS_BY_COLOR = mapOf(
        // Carreiras de bairro
        "#FFDC00" to "10B 13B 17B 19B 22B 26B 29B 31B 32B 34B 37B 40B 41B 45B 46B 49B 52B 55B 58B 60B 61B 64B 65B 67B 71B 72B 73B 76B 79B",
        // Laranja (Centro)
        "#F7941E" to "12E 24E 25E 28E 51E 55E 702 706 709 712 713 730 734 737 773 774 797",
        // Vermelha (Marvila / Olivais)
        "#ED1C24" to "705 708 718 722 725 731 744 749 755 759 781 782 783 793 794",
        // Azul (Benfica / Carnide)
        "#00AEEF" to "711 716 724 726 729 746 754 758 763 764 765 768 771 799",
        // Cinzenta (Circulares)
        "#8C8C99" to "701 703 720 727 728 738 742 750 753 756 767",
        // Rosa (Ajuda / Belém)
        "#EC008C" to "15E 18E 714 723 732 748 751 760 776",
        // Rede da Madrugada
        "#091B7D" to "201 202 203 206 207 208 210",
        // Verde (Alvalade / Lumiar)
        "#2FB61E" to "717 735 736 747 778 796 798"
    )

    private val CARRIS: Map<String, String> = buildMap {
        CARRIS_BY_COLOR.forEach { (color, lines) -> lines.split(" ").forEach { put(it, color) } }
    }

    /** Cor oficial de uma linha da Carris pelo número ("735", "28E", "22b"). */
    fun carris(shortName: String): String {
        val key = shortName.trim().uppercase()
        CARRIS[key]?.let { return it }
        return when {
            key.endsWith("B") -> "#FFDC00"                          // carreiras de bairro
            key.length == 3 && key.startsWith("2") -> "#091B7D"     // rede da madrugada
            else -> FALLBACK
        }
    }

    /** Cor de uma linha: a da Carris pelo número, ou a que a Carris Metropolitana envia. */
    fun forRoute(shortName: String, operator: String, apiColor: String? = null): String =
        if (operator == "carris_metropolitana") normalize(apiColor) ?: FALLBACK else carris(shortName)

    /**
     * Cor do número em cima da cor da linha: branco, como no site da Carris, exceto em cores muito
     * claras (o amarelo dos bairros), onde o branco não se lê.
     */
    fun textOn(hex: String): String = if (luminance(hex) > 0.5) "#171717" else "#FFFFFF"

    /** "#2fb61e", "2FB61E" → "#2FB61E"; null se não for uma cor válida. */
    fun normalize(hex: String?): String? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        return if (h.length == 6 && h.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) "#" + h.uppercase() else null
    }

    /** Luminância relativa (WCAG) de uma cor "#RRGGBB". */
    fun luminance(hex: String): Double {
        val h = normalize(hex) ?: return 0.0
        fun channel(i: Int): Double {
            val c = h.substring(i, i + 2).toInt(16) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5)
    }
}
