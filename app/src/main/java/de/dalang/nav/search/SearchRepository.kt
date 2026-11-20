package de.dalang.nav.search

import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SearchResult(
    /** „Titel, Adresse“ — erster Teil vor dem Komma ist der Name (FavoriteAddressDialog/Speichern nutzen das). */
    val displayName: String,
    val lat: Double,
    val lon: Double,
    /** OSM-Wert (residential, zoo, …) bzw. "saved"/"adb" für Nicht-Suchtreffer. */
    val type: String,
    /** Entfernung zum Nutzer in km, 0 = unbekannt. */
    val distance: Double = 0.0,
    val title: String = displayName.substringBefore(",").trim(),
    /** Deutsche Kategorie („Zoo“, „Parkplatz“, „Straße“ …), leer bei Nicht-Suchtreffern. */
    val typeLabel: String = "",
    /** „Straße Nr, PLZ Ort“ (ohne Titel). */
    val address: String = "",
    /** Liegt außerhalb der Region (> PhotonResults.NEAR_KM) — UI zeigt Abschnitt „Weiter entfernt“. */
    val isFar: Boolean = false,
    /** Zugehörige, separat anfahrbare Teilobjekte (Parkplatz, Bahnhof). */
    val related: List<SearchResult> = emptyList(),
    /** Straße ohne Hausnummer — Tippen startet die Hausnummer-Eingabe ([StreetContext]). */
    val isStreet: Boolean = false,
    /** Ort (city, sonst district/locality) — Straßen-Kontext filtert darauf, nicht auf die PLZ. */
    val city: String = ""
)

class SearchRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Zielsuche mit Photon (Komoot, OSM-Daten). Eine Anfrage pro Suche (Fair-Use photon.komoot.io),
     * Aufbereitung/Ranking/Gruppierung in [PhotonResults].
     */
    suspend fun search(
        query: String,
        currentLat: Double? = null,
        currentLon: Double? = null
    ): List<SearchResult> =
        if (query.isBlank()) emptyList()
        else photon(query, currentLat, currentLon) { PhotonResults.process(it, query, currentLat, currentLon) } ?: emptyList()

    /** Adressen zu [number] in der Straße von [street] (gefiltert, siehe [PhotonResults.houseNumbers]); null = Fehler. */
    suspend fun searchHouseNumber(
        street: StreetContext,
        number: String,
        currentLat: Double? = null,
        currentLon: Double? = null
    ): List<SearchResult>? =
        photon(street.photonQuery(number), currentLat, currentLon) {
            PhotonResults.houseNumbers(it, street, number, currentLat, currentLon)
        }

    private suspend fun <T : List<*>> photon(query: String, lat: Double?, lon: Double?, parse: (String) -> T): T? =
        withContext(Dispatchers.IO) {
            try {
                CrashLogger.log("SearchRepository: Searching for '$query'")

                // Eigener User-Agent ist Pflicht: Photon blockt den OkHttp-Default seit Nov 2026 mit 403
                // (HTML-Antwort -> JSON-Parse-Fehler -> 0 Treffer, "kein Ziel eingebbar").
                val request = Request.Builder()
                    .url(photonUrl(query, lat, lon))
                    .header("User-Agent", USER_AGENT)
                    .build()

                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        CrashLogger.logError("SearchRepository", "Photon HTTP ${response.code}")
                        return@withContext null
                    }
                    response.body?.string()
                } ?: return@withContext null

                parse(body).also { CrashLogger.log("SearchRepository: Found ${it.size} results") }
            } catch (e: Exception) {
                CrashLogger.logError("SearchRepository", "Search failed", e)
                null
            }
        }

    suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$NOMINATIM_BASE_URL/reverse?lat=$lat&lon=$lon&format=json"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            val json = JSONObject(body)
            json.optString("display_name", null)
        } catch (e: Exception) {
            CrashLogger.logError("SearchRepository", "Reverse geocode failed", e)
            null
        }
    }

    companion object {
        private const val PHOTON_BASE_URL = "https://photon.komoot.io/api"
        private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
        private const val USER_AGENT = "DaLang Navigation App"

        /** Mehr Kandidaten als angezeigt, weil die Gruppierung stark verdichtet („hauptbahnhof“: 20 → 1). */
        private const val PHOTON_LIMIT = 20

        /**
         * Photon-Parameter. Quellen: github.com/komoot/photon docs/api-v1.md („Location Bias“) und Code von
         * v1.3.0 (= photon.komoot.io laut /status, git 4c28c7c1): query/SearchRequestBase.java,
         * opensearch/OpenSearchSearchHandler.java, opensearch/OpenSearchResult.java.
         *
         * So wirkt der Location-Bias tatsächlich:
         *  - Er filtert nicht, er addiert nur Punkte (function_score, boostMode=Sum) zum Text-Score.
         *  - Gewicht 30·(1−location_bias_scale), Wichtigkeit (prominence) 30·scale; Default scale=0.4.
         *  - Voll innerhalb r = 0.1·2.2^(18−zoom) km (zoom 12 → 11 km), danach exponentieller Abfall mit
         *    Halbwertsdistanz max(8, r·(zoom−3)) km (zoom 12 → 102 km). Daher [PhotonResults.NEAR_KM] = 100.
         *  - Kandidaten müssen den Text matchen (must-Query, ~1,5·limit Stück); der Bias sortiert nur unter
         *    ihnen. Passt in der Nähe nichts zum Text („tower fitness“), gewinnt der beste Textmatch weltweit —
         *    der Bias kann nichts Nahes herbeizaubern. Das war das gemeldete „Bias wirkungslos“.
         *
         * Messung 2026-11-20, Standort München-Moosach (48.1799, 11.5077), limit 15/20:
         *  - bahnhofstraße / dachauer straße / berlin / aying / bunzlauer straße 8: Default-Bias liefert lokale
         *    Treffer bereits zuerst (alle ≤ 36 km, Berlin-Stadt korrekt auf 1).
         *  - zoom=10 + location_bias_scale=0.2 (aying, bunzlauer, dachauer) bzw. zoom=9 / scale=0.2
         *    (marienplatz): nahe Listen praktisch identisch. zoom=10 + scale=0.1: „tower sq“/„tower fitness“
         *    bleiben weltweit (Nürnberg 146 km rückt nur von 3 auf 2), „tower sq“ holt sogar Prag dazu.
         *  - bbox ±~100 km: „tower sq“/„tower fitness“ → 0 Treffer, marienplatz/bergtierpark unverändert; also kein Gewinn
         *    gegenüber Bias, aber globale Ziele („berlin“) wären weg → zweite Anfrage nötig. Nicht genommen.
         *  Fazit: Default zoom/scale behalten, ferne Treffer clientseitig einordnen (PhotonResults).
         * lang=de: deutsche Namen/Länder („Vereinigtes Königreich“ statt lokaler Schrift); Suche matcht trotzdem
         * alle Namensvarianten. dedupe bleibt Default (an): fasst nur gleiche name+PLZ+osm_value zusammen.
         */
        internal fun photonUrl(query: String, lat: Double?, lon: Double?): String =
            PHOTON_BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("q", query.trim())
                .addQueryParameter("limit", PHOTON_LIMIT.toString())
                .addQueryParameter("lang", "de")
                .apply {
                    if (lat != null && lon != null) {
                        addQueryParameter("lat", lat.toString())
                        addQueryParameter("lon", lon.toString())
                    }
                }
                .build().toString()
    }
}
