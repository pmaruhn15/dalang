package de.dalang.nav.search

/**
 * „Straße wählen, dann Hausnummer tippen“ (wie Google Maps): Tippen auf einen Straßen-Treffer setzt das Suchfeld
 * auf „<Straße> “ und merkt sich die Straße. Solange der Text mit dem Straßennamen beginnt, ist der Rest die
 * Hausnummer; Vorschläge kommen gefiltert aus [PhotonResults.houseNumbers].
 */
data class StreetContext(val result: SearchResult) {
    val street get() = result.title
    val city get() = result.city

    /** Endet, sobald der Nutzer den Straßennamen verändert oder löscht. */
    fun covers(query: String) = query.startsWith(street, ignoreCase = true)

    /** "" = noch keine Nummer, null = Rest ist keine Hausnummer (dann normale Suche). */
    fun houseNumberIn(query: String): String? {
        val rest = query.substring(street.length).trim()
        return if (rest.isEmpty()) "" else rest.takeIf { HOUSE_NUMBER.matches(it) }
    }

    /**
     * Messung 2026-11-20 (München-Moosach, Ziel Dachauer Straße 12 München 80335):
     *  - „dachauer straße 12“: 9 Treffer, kein einziger aus München (Olching, Emmering, Erdweg, Wien).
     *  - „dachauer straße 12 münchen“: 2 Treffer, beide Unsinn (Dachdecker in Berlin). Gleiches bei
     *    „bunzlauer straße 8 münchen“. Photon verträgt „ß“ in dieser Kombination nicht.
     *  - „dachauer strasse 12“: 20 Treffer, München-12 (Adresse, Hotel, Café) + 14 andere Orte.
     *  - „dachauer strasse 12 münchen“: 20 Treffer, dieselben München-12 + nur noch 5 andere Orte.
     *  - „dachauer strasse 1“ → keine München-Adresse; „… 1 münchen“ → immerhin 118 (Präfix-Treffer).
     * Daher ß→ss und Ort anhängen.
     */
    fun photonQuery(number: String) = "$street $number $city".trim().replace("ß", "ss")

    /** Oberster Eintrag: direkt zur Straße navigieren (bisheriges Verhalten beim Straßen-Tap). */
    val streetOnly get() = entry("$street · ohne Hausnummer navigieren")
    val notFound get() = entry("Hausnummer nicht gefunden – $street ohne Nummer navigieren")

    /** null = (noch) nicht gesucht/Fehler → nur [streetOnly]; leer → [notFound] statt leerer Liste. */
    fun withAddresses(addresses: List<SearchResult>?): List<SearchResult> = when {
        addresses == null -> listOf(streetOnly)
        addresses.isEmpty() -> listOf(notFound)
        else -> listOf(streetOnly) + addresses
    }

    /** Ziel hinter einem Eintrag: [streetOnly]/[notFound] stehen für die Straße selbst. */
    fun target(clicked: SearchResult) = if (clicked.type == STREET_ONLY) result else clicked

    private fun entry(title: String) = result.copy(
        displayName = "$title, ${result.address}", title = title, typeLabel = "",
        type = STREET_ONLY, related = emptyList(), isStreet = false, isFar = false
    )

    companion object {
        const val STREET_ONLY = "street_only"

        /** 12, 12a, 12 a, 12-14, 12/1, auch halb getippt „12-“. */
        private val HOUSE_NUMBER = Regex("""\d+\s*[a-zA-Z]?(\s*[-/–]\s*\d*\s*[a-zA-Z]?)?""")

        fun of(result: SearchResult) = if (result.isStreet) StreetContext(result) else null
    }
}
