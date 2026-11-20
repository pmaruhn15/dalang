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

    private fun photonJson(query: String): String {
        val f = File(dir, query.replace("ß", "ss").replace(Regex("[^a-z0-9]+"), "_") + ".json")
        if (System.getenv("SEARCH_RECORD") == "1") {
            Thread.sleep(1100) // Fair-Use
            val req = Request.Builder().url(SearchRepository.photonUrl(query, lat, lon))
                .header("User-Agent", "DaLang Navigation App").build()
            f.writeText(OkHttpClient().newCall(req).execute().use { it.body!!.string() })
        }
        return f.readText()
    }

    private fun search(query: String) = PhotonResults.process(photonJson(query), query, lat, lon)

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

    // ---------------------------------------------------------------- Straße → Hausnummer

    /** Die Münchner Dachauer Straße (1. Treffer, 0,9 km), wie nach dem Tipp in der Liste. */
    private val dachauerMuenchen by lazy { StreetContext.of(search("dachauer straße").first())!! }

    private fun houseNumbers(query: String, number: String) =
        PhotonResults.houseNumbers(photonJson(query), dachauerMuenchen, number, lat, lon)

    @Test
    fun strassenKontext_startNurBeiStrasse_endetWennNameVeraendert() {
        val street = search("dachauer straße").first()
        assertTrue(street.isStreet)
        assertEquals("München", street.city)
        assertEquals(null, StreetContext.of(search("bergtierpark blindham").first()))
        assertEquals(null, StreetContext.of(search("bunzlauer straße 8").first { it.typeLabel == "Adresse" }))

        val ctx = dachauerMuenchen
        assertEquals("Dachauer Straße", ctx.street)
        assertTrue(ctx.covers("Dachauer Straße "))
        assertTrue(ctx.covers("Dachauer Straße 12a"))
        assertTrue(ctx.covers("dachauer straße 1"))
        assertFalse(ctx.covers("Dachauer Straß"))
        assertFalse(ctx.covers("Dachauer Weg 12"))
        assertFalse(ctx.covers(""))
    }

    @Test
    fun hausnummerImQuery() {
        val ctx = dachauerMuenchen
        assertEquals("", ctx.houseNumberIn("Dachauer Straße "))
        assertEquals("", ctx.houseNumberIn("Dachauer Straße"))
        listOf("12", "12a", "12 a", "12-14", "12 - 14", "12-", "12/1", "1").forEach {
            assertEquals(it, ctx.houseNumberIn("Dachauer Straße $it"))
        }
        assertEquals(null, ctx.houseNumberIn("Dachauer Straße München"))
        assertEquals(null, ctx.houseNumberIn("Dachauer Straße 12 München"))
        assertEquals("Dachauer Strasse 12 München", ctx.photonQuery("12"))
    }

    @Test
    fun hausnummer_nurGleicheStrasseImGleichenOrt_poisZusammengefasst() {
        // Rohdaten: Nr. 12 auch in Olching, Emmering, Erdweg, Petershausen, Haimhausen …
        val towns = Regex("\"city\":\"([^\"]+)\"").findAll(photonJson("dachauer strasse 12")).map { it.groupValues[1] }.toSet()
        assertTrue(towns.toString(), towns.size >= 5 && "München" in towns && "Olching" in towns)

        for (query in listOf("dachauer strasse 12", "dachauer strasse 12 münchen")) {
            val r = houseNumbers(query, "12")
            assertEquals(query + " " + r.dump(), 1, r.size)
            with(r[0]) {
                assertEquals("Dachauer Straße 12", title)
                assertEquals("80335 München", address) // andere PLZ als der Straßen-Treffer (80637)
                assertEquals("Adresse · Advastay by KING's, California Bean", typeLabel)
                assertEquals("Dachauer Straße 12, 80335 München", displayName)
                assertEquals("house", type) // Koordinate der reinen Adresse, nicht des Hotels
            }
        }
    }

    @Test
    fun hausnummer_praefix_sortiertNachNummer() {
        // „… 1 münchen“: München liefert Photon nur 98 und 118, Oberschleißheim/Indersdorf haben die echte 1
        val r = houseNumbers("dachauer strasse 1 münchen", "1")
        assertEquals(r.dump(), listOf("Dachauer Straße 118"), r.map { it.title })
        assertEquals("Adresse · Oberlandesgericht München - Strafsenate", r[0].typeLabel)
        // 12, 12a, 12b … sortiert, 12 vor 12a
        val ctx = StreetContext(dachauerMuenchen.result.copy(city = "Oberschleißheim"))
        val o = PhotonResults.houseNumbers(photonJson("dachauer strasse 12 münchen"), ctx, "12", lat, lon)
        assertEquals(listOf("12a", "12b", "12c"), o.map { it.title.substringAfterLast(" ") })
    }

    @Test
    fun hausnummerNichtGefunden_hinweisStattLeererListe() {
        val ctx = dachauerMuenchen
        val none = houseNumbers("dachauer strasse 12", "99")
        assertTrue(none.isEmpty())
        val list = ctx.withAddresses(none)
        assertEquals(1, list.size)
        assertEquals("Hausnummer nicht gefunden – Dachauer Straße ohne Nummer navigieren", list[0].title)
        assertEquals(ctx.result, ctx.target(list[0]))

        assertEquals(listOf("Dachauer Straße · ohne Hausnummer navigieren"), ctx.withAddresses(null).map { it.title })
        val found = ctx.withAddresses(houseNumbers("dachauer strasse 12", "12"))
        assertEquals(listOf("Dachauer Straße · ohne Hausnummer navigieren", "Dachauer Straße 12"), found.map { it.title })
        assertEquals(ctx.result, ctx.target(found[0]))
        assertEquals(found[1], ctx.target(found[1]))
        assertTrue(found.none { it.isStreet }) // erneuter Tipp startet keinen neuen Kontext
    }
}
