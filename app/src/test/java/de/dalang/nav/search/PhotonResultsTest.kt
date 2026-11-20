package de.dalang.nav.search

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Zielsuche mit echten Photon-Antworten (photon.komoot.io v1.3.0, 2026-11-20, Standort München-Moosach,
 * URL aus [SearchRepository.photonUrl]). Neu aufzeichnen: SEARCH_RECORD=1 ./gradlew testDebugUnitTest
 */
class PhotonResultsTest {

    private val lat = 48.1799
    private val lon = 11.5077
    private val dir = File("src/test/resources/search")

    private fun search(query: String): List<SearchResult> {
        val f = File(dir, query.replace("ß", "ss").replace(Regex("[^a-z0-9]+"), "_") + ".json")
        if (System.getenv("SEARCH_RECORD") == "1") {
            Thread.sleep(1100) // Fair-Use
            val req = Request.Builder().url(SearchRepository.photonUrl(query, lat, lon))
                .header("User-Agent", "DaLang Navigation App").build()
            f.writeText(OkHttpClient().newCall(req).execute().use { it.body!!.string() })
        }
        return PhotonResults.process(f.readText(), query, lat, lon)
    }

    private fun List<SearchResult>.dump() = joinToString("\n") {
        "${it.title} | ${it.typeLabel} | ${it.address} | ${"%.1f".format(it.distance)} km" +
            (if (it.isFar) " [fern]" else "") + it.related.joinToString("") { r -> " +[${r.title}]" }
    }

    @Test
    fun bergtierpark_vierTreffer_werdenEinZooMitParkplatz() {
        val r = search("bergtierpark blindham")
        assertEquals(r.dump(), 1, r.size)
        with(r[0]) {
            assertEquals("BergTierPark Blindham", title)
            assertEquals("Zoo", typeLabel)
            assertEquals("Blindham 3, 85653 Aying", address)
            assertEquals("BergTierPark Blindham, Blindham 3, 85653 Aying", displayName)
            assertEquals(listOf("Parkplatz BergTierPark Blindham"), related.map { it.title })
            assertEquals("Parkplatz", related[0].typeLabel)
        }
    }

    @Test
    fun marienplatz_platzStrasseBahnhofZusammen_fernerMarienplatzSeparat() {
        val r = search("marienplatz")
        assertEquals(r.dump(), 2, r.size)
        assertEquals("Platz", r[0].typeLabel)
        assertTrue(r[0].address, r[0].address.endsWith("München"))
        assertEquals(listOf("Bahnhof Marienplatz"), r[0].related.map { it.title })
        assertTrue(r.dump(), r[1].distance > 30)
        assertFalse(r[1].isFar)
    }

    @Test
    fun towerSq_nurTextTreffer_fernUndNachEntfernung() {
        val r = search("tower sq")
        // Nichts davon in der Nähe; PLZ-Treffer („E14 5SQ“) und „Tower, … RH12 3SQ“ fliegen raus
        assertTrue(r.dump(), r.isNotEmpty() && r.all { it.isFar })
        assertTrue(r.dump(), r.size <= 5)
        assertTrue(r.dump(), r.none { it.typeLabel == "PLZ-Gebiet" || it.title == "Tower" })
        assertEquals(r.map { it.distance }.sorted(), r.map { it.distance })
        assertTrue(r.dump(), r.all { it.address.contains("Vereinigte Staaten") || it.address.contains("Iran") })
    }

    @Test
    fun towerFitness_hoechstensFuenfFerne_naechsterZuerst() {
        val r = search("tower fitness")
        assertTrue(r.dump(), r.size in 1..5 && r.all { it.isFar })
        assertEquals(r.map { it.distance }.sorted(), r.map { it.distance })
    }

    @Test
    fun bunzlauerStrasse8_adresseVorStrasse_fernes_hoechstensDrei() {
        val r = search("bunzlauer straße 8")
        val near = r.filterNot { it.isFar }
        assertTrue(r.dump(), near.any { it.title == "Bunzlauer Straße 8" && it.typeLabel == "Adresse" && it.address == "80992 München" })
        assertTrue(r.dump(), near.any { it.title == "Fahrschule 1Plus" && it.address == "Bunzlauer Straße 8, 80992 München" })
        // Straßenabschnitte + Taxistand gleichen Namens in München → ein Eintrag
        assertEquals(r.dump(), 1, near.count { it.title == "Bunzlauer Straße" && it.address.endsWith("München") })
        assertTrue(r.dump(), r.count { it.isFar } in 1..3)
        assertTrue(r.dump(), r.dropWhile { !it.isFar }.all { it.isFar })
    }

    @Test
    fun dachauerStrasse_abschnitteEinerStadt_werdenEineStrasse() {
        val r = search("dachauer straße")
        assertEquals(r.dump(), 1, r.count { it.title == "Dachauer Straße" && it.address.endsWith("München") })
        val first = r.first()
        assertEquals("Straße", first.typeLabel)
        assertTrue(first.distance < 1.5)
    }

    @Test
    fun aying_ortZuerst_bahnhofAlsUnterpunkt() {
        val r = search("aying")
        with(r[0]) {
            assertEquals("Aying", title)
            assertEquals("Ort", typeLabel)
            assertEquals("Landkreis München, Bayern", address)
            assertEquals(listOf("Bahnhof Aying"), related.map { it.title })
        }
        // Haltestellen/Bahnsteige „Aying“, „Aying (S)“, „Aying, Peißer Straße 8“ sind im Ort aufgegangen
        assertTrue(r.dump(), r.drop(1).none { PhotonResults.tokens(it.title).first() == "aying" })
    }

    @Test
    fun berlin_ferneStadtBleibtVorne() {
        val r = search("berlin")
        assertEquals("Berlin", r[0].title)
        assertEquals("Stadt", r[0].typeLabel)
        assertEquals("Deutschland", r[0].address)
        assertFalse(r[0].isFar)
        assertTrue(r[0].distance > 400)
    }

    @Test
    fun hauptbahnhof_zwanzigObjekte_werdenEinBahnhof() {
        val r = search("hauptbahnhof")
        assertEquals(r.dump(), 1, r.count { it.title == "Hauptbahnhof" && it.address.endsWith("München") })
        assertEquals("Bahnhof", r[0].typeLabel)
    }

    @Test
    fun ohneStandort_photonReihenfolge_nichtsFern() {
        val json = File(dir, "berlin.json").readText()
        val r = PhotonResults.process(json, "berlin", null, null)
        assertEquals("Berlin", r[0].title)
        assertTrue(r.none { it.isFar || it.distance != 0.0 })
    }

    @Test
    fun typLabels() {
        fun label(k: String, v: String, layer: String = "house", address: Boolean = false) =
            PlaceKind.of(k, v, layer, address).label
        assertEquals("Zoo", label("tourism", "zoo"))
        assertEquals("Parkplatz", label("amenity", "parking"))
        assertEquals("Restaurant", label("amenity", "restaurant"))
        assertEquals("Bushaltestelle", label("highway", "bus_stop"))
        assertEquals("Straße", label("highway", "secondary", "street"))
        assertEquals("Straße", label("highway", "living_street", "street"))
        assertEquals("Weg", label("highway", "footway", "street"))
        assertEquals("Platz", label("place", "square", "locality"))
        assertEquals("Ort", label("place", "village", "city"))
        assertEquals("Ort", label("boundary", "administrative", "city"))
        assertEquals("Adresse", label("building", "apartments", address = true))
        assertEquals("Gebäude", label("building", "yes"))
        assertEquals("geplant", label("railway", "proposed"))
        assertEquals("Geschäft", label("shop", "unknown_value"))
        assertEquals("Ort", label("unknown_key", "x"))
        assertTrue(PlaceKind.of("tourism", "zoo", "house", false).priority >
            PlaceKind.of("amenity", "parking", "house", false).priority)
    }

    @Test
    fun textabgleich() {
        val t = PhotonResults::tokens
        assertEquals(listOf("munchen"), t("München"))
        assertEquals(t("München"), t("Muenchen"))
        assertEquals(listOf("bergtierpark", "blindham"), t("BergTierPark Blindham"))
        assertEquals(t("bunzlauer strasse 8"), t("Bunzlauer Straße 8"))
        assertEquals(3, t("Bunzlauer Straße 8").size)
        assertTrue(PhotonResults.tokenMatches("str", "strasse"))          // Präfix
        assertTrue(PhotonResults.tokenMatches("strasse", "bahnhofstrasse")) // Teilwort
        assertTrue(PhotonResults.tokenMatches("hambrug", "hamburg"))      // Dreher
        assertTrue(PhotonResults.tokenMatches("marienplaz", "marienplatz"))
        assertFalse(PhotonResults.tokenMatches("tower", "power"))         // erster Buchstabe falsch
        assertFalse(PhotonResults.tokenMatches("sq", "3sq"))
    }

    @Test
    fun photonUrl_parameter() {
        val url = SearchRepository.photonUrl(" bunzlauer straße 8 ", lat, lon)
        assertTrue(url, url.startsWith("https://photon.komoot.io/api?q=bunzlauer%20stra%C3%9Fe%208&"))
        assertTrue(url, url.contains("limit=20") && url.contains("lang=de"))
        assertTrue(url, url.contains("lat=48.1799") && url.contains("lon=11.5077"))
        assertFalse(SearchRepository.photonUrl("x", null, null).contains("lat="))
    }
}
