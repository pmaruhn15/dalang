package de.dalang.nav.search

import de.dalang.nav.util.GeoUtils
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Aufbereitung der Photon-Antwort: Parsen → Textabgleich → Gruppieren → Ranking → Darstellung.
 * Rein (kein Netz, kein Android), damit mit aufgezeichneten Antworten testbar (test/resources/search).
 *
 * Ranking (Begründung siehe SearchRepository-KDoc zum Location-Bias):
 *  1. Photons Reihenfolge ist das Relevanzsignal (Textscore + Wichtigkeit + Standort-Bias) und bleibt
 *     innerhalb eines Blocks erhalten. Die alte reine Distanz-Sortierung zerstörte sie (Ort „Aying“ landete
 *     hinter „Ayinger Weg“, Stadt „Berlin“ hinter jeder Berliner Straße).
 *  2. Nah (≤ [NEAR_KM]) vor fern. Fern ist alles, was außerhalb von Photons Bias-Halbwertsdistanz liegt —
 *     dort ist der Bias auf ≤ die Hälfte abgeklungen und der Textscore dominiert. Ausnahme: Orte/Regionen
 *     (Stadt, Landkreis, Land), die man bewusst weit weg sucht („berlin“).
 *  3. Ferne Treffer nur, wenn jedes Suchwort im Namen/in der Adresse vorkommt (Präfix oder 1–2 Tippfehler);
 *     ferne Treffer, die nur über PLZ o.ä. passen („tower sq“ → Londoner PLZ „E14 5SQ“), fliegen raus.
 *     Nahe Treffer ohne vollen Textabgleich (Photon-Fuzzy) bleiben, aber hinter den passenden nahen.
 *     Höchstens [MAX_FAR_WITH_NEAR] ferne Treffer neben nahen, sonst [MAX_FAR_ONLY]; im fernen Block nach
 *     Entfernung sortiert und als [SearchResult.isFar] markiert (UI: Abschnitt „Weiter entfernt“).
 *
 * Gruppierung: gleicher/ähnlicher Name am selben Ort → ein Eintrag. Repräsentant = höchste
 * [PlaceKind.priority] (Zoo vor Parkplatz/Kasse/Restaurant), Parkplatz/Bahnhof bleiben als [SearchResult.related].
 */
internal object PhotonResults {

    const val NEAR_KM = 100.0
    const val MAX_RESULTS = 10
    private const val MAX_FAR_WITH_NEAR = 3
    private const val MAX_FAR_ONLY = 5

    /** Gleiche Kategorie, gleicher Name, näher als das = dasselbe Objekt doppelt in OSM. */
    private const val DUPLICATE_M = 100.0
    /** Gleicher Name, andere Kategorie (Zoo ↔ Parkplatz 451 m, Ort ↔ Bahnhof 715 m in den Fixtures). */
    private const val GROUP_M = 800.0
    /** Straßenabschnitte ohne Ortsangabe: Mittelpunkte benachbarter Segmente liegen meist < 1,5 km auseinander. */
    private const val STREET_LINK_M = 1500.0

    private val PROMINENT_LAYERS = setOf("city", "county", "state", "country")

    fun process(json: String, query: String, lat: Double?, lon: Double?): List<SearchResult> {
        val queryTokens = tokens(query)
        val places = parse(json, lat, lon)
            .filter { !it.isFar || it.matches(queryTokens) }

        val groups = group(places)
        val (far, near) = groups.partition { it.head.isFar && !it.isProminent }
        val (nearMatched, nearOther) = near.partition { g -> g.members.any { it.matches(queryTokens) } }
        val maxFar = if (near.isEmpty()) MAX_FAR_ONLY else MAX_FAR_WITH_NEAR

        val ordered = nearMatched.sortedBy { it.rank } + nearOther.sortedBy { it.rank }
        val farOrdered = far.sortedBy { it.rank }.take(maxFar).sortedBy { it.head.distanceKm }
        return (ordered.map { it.toResult(isFar = false) } + farOrdered.map { it.toResult(isFar = true) })
            .take(MAX_RESULTS)
    }

    // ---------------------------------------------------------------- Parsen

    internal class Place(
        val rank: Int,
        val osmKey: String,
        val osmValue: String,
        val layer: String,
        val name: String,
        val street: String,
        val housenumber: String,
        val postcode: String,
        val city: String,
        val district: String,
        val locality: String,
        val county: String,
        val state: String,
        val country: String,
        val countrycode: String,
        val lat: Double,
        val lon: Double,
        val distanceKm: Double?
    ) {
        val kind = PlaceKind.of(osmKey, osmValue, layer, isAddress = name.isBlank() && housenumber.isNotBlank())
        val streetLine = "$street $housenumber".trim()
        val title = name.ifBlank { streetLine.ifBlank { kind.label } }
        val titleTokens = tokens(title)
        val isFar get() = distanceKm != null && distanceKm > NEAR_KM

        private val searchTokens = tokens("$name $street $housenumber $city $district $locality")
        private val postcodeTokens = tokens(postcode)

        /** Jedes Suchwort steckt in Name/Adresse; PLZ zählt nur exakt (sonst passt „sq“ auf „3SQ“). */
        fun matches(queryTokens: List<String>) = queryTokens.all { q ->
            q in postcodeTokens || searchTokens.any { tokenMatches(q, it) }
        }
    }

    private fun parse(json: String, lat: Double?, lon: Double?): List<Place> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).map { i ->
            val f = features.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val pLon = c.getDouble(0)
            val pLat = c.getDouble(1)
            fun s(key: String) = p.optString(key, "").trim()
            Place(
                rank = i,
                osmKey = s("osm_key"), osmValue = s("osm_value"), layer = s("type"),
                name = s("name"), street = s("street"), housenumber = s("housenumber"),
                postcode = s("postcode"), city = s("city"), district = s("district"),
                locality = s("locality"), county = s("county"), state = s("state"),
                country = s("country"), countrycode = s("countrycode"),
                lat = pLat, lon = pLon,
                distanceKm = if (lat != null && lon != null) {
                    GeoUtils.calculateDistanceKilometers(lat, lon, pLat, pLon)
                } else null
            )
        }
    }

    // ---------------------------------------------------------------- Gruppieren

    internal class Group(val head: Place) {
        val members = mutableListOf(head)
        val rank get() = members.minOf { it.rank }
        val isProminent get() = head.osmKey in setOf("place", "boundary") && head.layer in PROMINENT_LAYERS

        fun toResult(isFar: Boolean): SearchResult {
            val related = members
                .filter { it !== head && it.kind.related && it.kind.label != head.kind.label }
                .distinctBy { it.kind.label }
                .map { sub ->
                    val title = if (sub.name.isBlank() || normalize(sub.name) == normalize(head.title)) {
                        "${sub.kind.label} ${head.title}"
                    } else sub.title
                    sub.toResult(title, isFar)
                }
            return head.toResult(head.title, isFar, related)
        }
    }

    /**
     * Greedy um Repräsentanten: Kandidaten nach (Priorität ↓, Photon-Rang ↑); jeder schließt sich dem nächsten
     * passenden Kopf an oder wird selbst Kopf. Kopf-zentriert statt Single-Linkage, damit zwei gleichnamige
     * Supermärkte 500 m auseinander nicht über einen dazwischenliegenden Parkplatz verschmelzen.
     * ponytail: O(n²), n ≤ Photon-limit (20).
     */
    private fun group(places: List<Place>): List<Group> {
        val groups = mutableListOf<Group>()
        val byPriority = places.sortedWith(compareByDescending<Place> { it.kind.priority }.thenBy { it.rank })
        for (p in byPriority) {
            val target = groups.filter { belongsTo(p, it.head) }.minByOrNull { meters(p, it.head) }
            if (target != null) target.members += p else groups += Group(p)
        }
        return groups
    }

    private fun belongsTo(p: Place, head: Place): Boolean {
        if (p.titleTokens.isEmpty() || head.titleTokens.isEmpty()) return false
        val sameName = p.titleTokens == head.titleTokens
        // „Bergtierpark“ (Restaurant) ⊂ „BergTierPark Blindham“ (Zoo) — nur echte Namen, nicht
        // „Bunzlauer Straße“ ⊂ „Bunzlauer Straße 8“ (Adresse ≠ Straße).
        val similarName = sameName || (p.name.isNotBlank() && head.name.isNotBlank() &&
            (head.titleTokens.containsAll(p.titleTokens) || p.titleTokens.containsAll(head.titleTokens)))
        if (!similarName) return false
        val d = meters(p, head)
        return when {
            p.kind.linear && head.kind.linear ->
                sameName && ((p.city.isNotBlank() && p.city == head.city) || d <= STREET_LINK_M)
            // Hauptobjekte sind groß: zwei Knoten „Hauptbahnhof“ (railway=station) liegen 126 m auseinander
            p.kind.label == head.kind.label -> d <= if (head.kind.priority >= PlaceKind.MAIN) GROUP_M else DUPLICATE_M
            sameName -> d <= GROUP_M
            // ähnlicher Name: gleiche Adresse (Restaurant „Bergtierpark“, Blindham 3) oder Teilobjekt in der Nähe
            // (Haltestelle „Aying (S)“ 670 m vom Ort „Aying“)
            else -> d <= DUPLICATE_M || (p.housenumber.isNotBlank() && p.streetLine == head.streetLine) ||
                (p.kind.priority <= PlaceKind.PART && d <= GROUP_M)
        }
    }

    private fun meters(a: Place, b: Place) = GeoUtils.calculateDistanceMeters(a.lat, a.lon, b.lat, b.lon)

    // ---------------------------------------------------------------- Darstellung

    /** „Straße Nr, PLZ Ort[, Land]“ ohne Wiederholung des Titels; bei Orten Landkreis/Bundesland. */
    internal fun Place.addressLine(): String {
        val parts = mutableListOf<String>()
        if (streetLine.isNotBlank() && streetLine != title) parts += streetLine
        val town = city.ifBlank { district.ifBlank { locality } }
        when {
            town.isNotBlank() && !town.equals(title, ignoreCase = true) -> parts += "$postcode $town".trim()
            kind.priority >= PlaceKind.MAIN && osmKey in setOf("place", "boundary") ->
                parts += listOf(county, state).filter { it.isNotBlank() && it != title }.distinct()
            postcode.isNotBlank() -> parts += postcode
        }
        if (country.isNotBlank() && (countrycode != "DE" || parts.isEmpty())) parts += country
        return parts.joinToString(", ")
    }

    private fun Place.toResult(title: String, isFar: Boolean, related: List<SearchResult> = emptyList()): SearchResult {
        val address = addressLine()
        return SearchResult(
            displayName = listOf(title, address).filter { it.isNotBlank() }.joinToString(", "),
            lat = lat,
            lon = lon,
            type = osmValue,
            distance = distanceKm ?: 0.0,
            title = title,
            typeLabel = kind.label,
            address = address,
            isFar = isFar,
            related = related
        )
    }

    // ---------------------------------------------------------------- Textabgleich

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /** Klein, ß→ss, Akzente weg, ae/oe/ue→a/o/u: „München“ = „Muenchen“ = „munchen“, „BergTierPark“ = „Bergtierpark“. */
    internal fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.GERMAN).replace("ß", "ss"), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace("ae", "a").replace("oe", "o").replace("ue", "u")

    internal fun tokens(s: String): List<String> = normalize(s).split(NON_ALNUM).filter { it.isNotEmpty() }

    /** Präfix („str“ → „strasse“), Teilwort ab 4 Zeichen („strasse“ in „bahnhofstrasse“), Tippfehler 1 bzw. 2 (ab 8). */
    internal fun tokenMatches(q: String, word: String): Boolean {
        if (word.startsWith(q) || (q.length >= 4 && word.contains(q))) return true
        if (q.length < 5 || word.first() != q.first()) return false
        val maxEdits = if (q.length >= 8) 2 else 1
        return (q.length - 1..q.length + 1).any { n -> editDistance(q, word.take(n)) <= maxEdits }
    }

    /** Levenshtein + Vertauschung benachbarter Zeichen (optimal string alignment). */
    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}

/**
 * Deutsche Kategorie aus osm_key/osm_value. Erweitern = Zeile in [BY_TAG] (genau) oder [BY_KEY] (Fallback).
 * [priority] entscheidet, wer eine Gruppe repräsentiert; [related] = als Unterpunkt anbieten (für Navigation
 * nützlich); [linear] = Straßen/Wege, deren Abschnitte zu einer Straße zusammengefasst werden.
 */
internal data class PlaceKind(val label: String, val priority: Int, val related: Boolean = false, val linear: Boolean = false) {
    companion object {
        const val INACTIVE = 0
        const val PART = 1
        const val NORMAL = 2
        const val MAIN = 3

        private fun k(priority: Int, label: String, vararg tags: String, related: Boolean = false, linear: Boolean = false) =
            PlaceKind(label, priority, related, linear).let { kind -> tags.map { it to kind } }

        private val BY_TAG: Map<String, PlaceKind> = listOf(
            // Hauptobjekte
            k(MAIN, "Zoo", "tourism=zoo"),
            k(MAIN, "Freizeitpark", "tourism=theme_park"),
            k(MAIN, "Museum", "tourism=museum"),
            k(MAIN, "Aquarium", "tourism=aquarium"),
            k(MAIN, "Sehenswürdigkeit", "tourism=attraction"),
            k(MAIN, "Burg/Schloss", "historic=castle"),
            k(MAIN, "Bahnhof", "railway=station", "railway=halt", "public_transport=station", related = true),
            k(MAIN, "Busbahnhof", "amenity=bus_station"),
            k(MAIN, "Flughafen", "aeroway=aerodrome"),
            k(MAIN, "Krankenhaus", "amenity=hospital"),
            k(MAIN, "Einkaufszentrum", "shop=mall"),
            k(MAIN, "Raststätte", "highway=services"),
            k(MAIN, "Park", "leisure=park"),
            k(MAIN, "Stadion", "leisure=stadium"),
            k(MAIN, "Freizeitbad", "leisure=water_park"),
            k(MAIN, "Gipfel", "natural=peak"),
            k(MAIN, "Platz", "place=square"),
            k(MAIN, "Stadt", "place=city", "place=town"),
            k(MAIN, "Ort", "place=village", "place=hamlet"),
            k(MAIN, "Stadtteil", "place=suburb", "place=borough"),
            k(MAIN, "Landkreis", "place=county"),
            k(MAIN, "Bundesland", "place=state"),
            k(MAIN, "Land", "place=country"),
            k(MAIN, "Insel", "place=island"),
            // normale Ziele
            k(NORMAL, "Viertel", "place=quarter", "place=neighbourhood"),
            k(NORMAL, "Gegend", "place=locality"),
            k(NORMAL, "Hof", "place=isolated_dwelling", "place=farm"),
            k(NORMAL, "Adresse", "place=house"),
            k(NORMAL, "PLZ-Gebiet", "place=postcode"),
            k(NORMAL, "Fußgängerzone", "highway=pedestrian", linear = true),
            k(NORMAL, "Ausfahrt", "highway=motorway_junction"),
            k(NORMAL, "Rastplatz", "highway=rest_area"),
            k(NORMAL, "Restaurant", "amenity=restaurant"),
            k(NORMAL, "Imbiss", "amenity=fast_food"),
            k(NORMAL, "Café", "amenity=cafe"),
            k(NORMAL, "Bar", "amenity=bar"),
            k(NORMAL, "Kneipe", "amenity=pub"),
            k(NORMAL, "Biergarten", "amenity=biergarten"),
            k(NORMAL, "Eisdiele", "amenity=ice_cream"),
            k(NORMAL, "Tankstelle", "amenity=fuel"),
            k(NORMAL, "Ladestation", "amenity=charging_station"),
            k(NORMAL, "Apotheke", "amenity=pharmacy"),
            k(NORMAL, "Arztpraxis", "amenity=doctors"),
            k(NORMAL, "Zahnarzt", "amenity=dentist"),
            k(NORMAL, "Schule", "amenity=school"),
            k(NORMAL, "Kindergarten", "amenity=kindergarten"),
            k(NORMAL, "Hochschule", "amenity=university", "amenity=college"),
            k(NORMAL, "Rathaus", "amenity=townhall"),
            k(NORMAL, "Polizei", "amenity=police"),
            k(NORMAL, "Post", "amenity=post_office"),
            k(NORMAL, "Bank", "amenity=bank"),
            k(NORMAL, "Kino", "amenity=cinema"),
            k(NORMAL, "Theater", "amenity=theatre"),
            k(NORMAL, "Bibliothek", "amenity=library"),
            k(NORMAL, "Gotteshaus", "amenity=place_of_worship"),
            k(NORMAL, "Fahrschule", "amenity=driving_school"),
            k(NORMAL, "Autovermietung", "amenity=car_rental"),
            k(NORMAL, "Waschanlage", "amenity=car_wash"),
            k(NORMAL, "Supermarkt", "shop=supermarket"),
            k(NORMAL, "Bäckerei", "shop=bakery"),
            k(NORMAL, "Metzgerei", "shop=butcher"),
            k(NORMAL, "Kiosk", "shop=kiosk"),
            k(NORMAL, "Autohaus", "shop=car"),
            k(NORMAL, "Werkstatt", "shop=car_repair"),
            k(NORMAL, "Baumarkt", "shop=doityourself"),
            k(NORMAL, "Kaufhaus", "shop=department_store"),
            k(NORMAL, "Fitnessstudio", "leisure=fitness_centre"),
            k(NORMAL, "Sportzentrum", "leisure=sports_centre"),
            k(NORMAL, "Schwimmbad", "leisure=swimming_pool"),
            k(NORMAL, "Sportplatz", "leisure=pitch"),
            k(NORMAL, "Spielplatz", "leisure=playground"),
            k(NORMAL, "Golfplatz", "leisure=golf_course"),
            k(NORMAL, "Hotel", "tourism=hotel"),
            k(NORMAL, "Pension", "tourism=guest_house"),
            k(NORMAL, "Campingplatz", "tourism=camp_site"),
            k(NORMAL, "Wohnmobilstellplatz", "tourism=caravan_site"),
            k(NORMAL, "Aussichtspunkt", "tourism=viewpoint"),
            k(NORMAL, "Kunstwerk", "tourism=artwork"),
            k(NORMAL, "Denkmal", "historic=monument"),
            k(NORMAL, "Gedenkstätte", "historic=memorial"),
            k(NORMAL, "Ruine", "historic=ruins"),
            k(NORMAL, "Turm", "man_made=tower"),
            k(NORMAL, "Terminal", "aeroway=terminal"),
            // Teilobjekte
            k(PART, "Parkplatz", "amenity=parking", "amenity=parking_entrance", related = true),
            k(PART, "Weg", "highway=footway", "highway=path", "highway=cycleway", "highway=track",
                "highway=steps", "highway=bridleway", linear = true),
            k(PART, "Bushaltestelle", "highway=bus_stop"),
            k(PART, "Haltestelle", "highway=platform", "railway=stop", "public_transport=platform",
                "public_transport=stop_position"),
            k(PART, "Tramhaltestelle", "railway=tram_stop"),
            k(PART, "Bahnsteig", "railway=platform"),
            k(PART, "U-Bahn-Eingang", "railway=subway_entrance"),
            k(PART, "Ticketverkauf", "shop=ticket"),
            k(PART, "Information", "tourism=information"),
            k(PART, "Taxistand", "amenity=taxi"),
            k(PART, "Carsharing", "amenity=car_sharing"),
            k(PART, "Fahrradstellplatz", "amenity=bicycle_parking"),
            k(PART, "Toilette", "amenity=toilets"),
            k(PART, "Geldautomat", "amenity=atm"),
        ).flatten().toMap()

        private val BY_KEY: Map<String, PlaceKind> = mapOf(
            "highway" to PlaceKind("Straße", NORMAL, linear = true),
            "amenity" to PlaceKind("Einrichtung", NORMAL),
            "shop" to PlaceKind("Geschäft", NORMAL),
            "tourism" to PlaceKind("Sehenswürdigkeit", NORMAL),
            "leisure" to PlaceKind("Freizeit", NORMAL),
            "historic" to PlaceKind("Historische Stätte", NORMAL),
            "natural" to PlaceKind("Natur", NORMAL),
            "waterway" to PlaceKind("Gewässer", NORMAL),
            "landuse" to PlaceKind("Gebiet", NORMAL),
            "building" to PlaceKind("Gebäude", NORMAL),
            "office" to PlaceKind("Büro", NORMAL),
            "craft" to PlaceKind("Handwerk", NORMAL),
            "man_made" to PlaceKind("Bauwerk", NORMAL),
            "healthcare" to PlaceKind("Gesundheit", NORMAL),
            "railway" to PlaceKind("Bahnanlage", PART),
            "public_transport" to PlaceKind("Haltestelle", PART),
        )

        /** Photon-Layer („type“) als letzter Fallback, z.B. boundary=administrative. */
        private val BY_LAYER: Map<String, PlaceKind> = mapOf(
            "street" to PlaceKind("Straße", NORMAL, linear = true),
            "city" to PlaceKind("Ort", MAIN),
            "district" to PlaceKind("Stadtteil", MAIN),
            "county" to PlaceKind("Landkreis", MAIN),
            "state" to PlaceKind("Bundesland", MAIN),
            "country" to PlaceKind("Land", MAIN),
        )

        private val ADDRESS = PlaceKind("Adresse", NORMAL)
        private val FALLBACK = PlaceKind("Ort", NORMAL)
        private val PLANNED = setOf("proposed", "construction")
        private val GONE = setOf("disused", "abandoned", "razed", "demolished")

        fun of(osmKey: String, osmValue: String, layer: String, isAddress: Boolean): PlaceKind = when {
            osmValue in PLANNED -> PlaceKind("geplant", INACTIVE)
            osmValue in GONE -> PlaceKind("stillgelegt", INACTIVE)
            isAddress -> ADDRESS
            else -> BY_TAG["$osmKey=$osmValue"] ?: BY_KEY[osmKey] ?: BY_LAYER[layer] ?: FALLBACK
        }
    }
}
