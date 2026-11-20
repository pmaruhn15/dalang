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
     * Messung 2026-11-20, korrekt UTF-8-kodiert (München-Moosach, limit 20):
     *  - „dachauer straße 12“ → 20 Treffer, davon 3× München-12; „… 12 münchen“ → 7 Treffer, dieselben 3.
     *  - „dachauer straße 1“ → 1 München-Adresse; „… 1 münchen“ → 10 München-Adressen (Präfix-Treffer).
     *  - „ß“ und „ss“ liefern identische Ergebnisse (eine frühere Messung mit „ß kaputt“ war ein
     *    Kodierungsfehler der Git-Bash, nicht Photon).
     * Daher Ort anhängen: weniger Fremdorte, mehr Hausnummern derselben Straße.
     */
    fun photonQuery(number: String) = "$street $number $city".trim()

    /** Oberster Eintrag: direkt zur Straße navigieren (bisheriges Verhalten beim Straßen-Tap). */
    val streetOnly get() = entry("Ohne Hausnummer navigieren")
    val notFound get() = entry("Hausnummer nicht gefunden – ohne Nummer navigieren")

    /** null = (noch) nicht gesucht/Fehler → nur [streetOnly]; leer → [notFound] statt leerer Liste. */
    fun withAddresses(addresses: List<SearchResult>?): List<SearchResult> = when {
        addresses == null -> listOf(streetOnly)
        addresses.isEmpty() -> listOf(notFound)
        else -> listOf(streetOnly) + addresses
    }

    /** Ziel hinter einem Eintrag: [streetOnly]/[notFound] stehen für die Straße selbst. */
    fun target(clicked: SearchResult) = if (clicked.type == STREET_ONLY) result else clicked

    // Titel = Straßenname, Hinweis als Typzeile (vorher "Straße · ohne Hausnummer n…" abgeschnitten)
    private fun entry(hint: String) = result.copy(
        displayName = "$street, ${result.address}", title = street, typeLabel = hint,
        type = STREET_ONLY, related = emptyList(), isStreet = false, isFar = false
    )

    companion object {
        const val STREET_ONLY = "street_only"

        /** 12, 12a, 12 a, 12-14, 12/1, auch halb getippt „12-“. */
        private val HOUSE_NUMBER = Regex("""\d+\s*[a-zA-Z]?(\s*[-/–]\s*\d*\s*[a-zA-Z]?)?""")

        fun of(result: SearchResult) = if (result.isStreet) StreetContext(result) else null
    }
}
