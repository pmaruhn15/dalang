package de.dalang.nav.navigation

import de.dalang.nav.config.HereConfig
import de.dalang.nav.util.CrashLogger
import de.dalang.nav.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/**
 * Kraftstoffpreise für eine Tankstelle
 */
data class FuelPrices(
    val diesel: Double?,  // Preis in Euro
    val e5: Double?,      // Super E5
    val e10: Double?      // Super E10
) {
    /**
     * Gibt den Preis für den angegebenen Kraftstofftyp zurück
     */
    fun getPriceForType(fuelType: de.dalang.nav.settings.FuelType): Double? {
        return when (fuelType) {
            de.dalang.nav.settings.FuelType.DIESEL -> diesel
            de.dalang.nav.settings.FuelType.SUPER -> e5 ?: e10
        }
    }
}

/**
 * POI (Point of Interest) Daten
 */
data class Poi(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val distanceKm: Double,  // Entfernung vom aktuellen Standort
    val estimatedArrivalMinutes: Int,  // Geschätzte Ankunftszeit in Minuten
    val detourMinutes: Int = 0,  // Zusätzliche Zeit für Umweg (nur bei Route-Suche)
    val fuelPrices: FuelPrices? = null,  // Nur für Tankstellen
    val openingHours: String? = null  // Öffnungszeiten (z.B. "24/7" oder "Mo-Fr 06:00-22:00")
)

enum class PoiType(val searchQuery: String, val displayName: String) {
    MCDONALDS("McDonald's", "McDonald's"),
    GAS_STATION("Tankstelle", "Tankstelle")
}

/**
 * Repository für POI-Suche (McDonald's, Tankstellen, etc.)
 */
class PoiRepository {

    /**
     * Sucht POIs entlang einer Route
     */
    suspend fun searchAlongRoute(
        type: PoiType,
        routeGeometry: List<LatLng>,
        currentLocation: LatLng,
        maxDistanceFromRouteKm: Double = 2.0,
        limit: Int = 15
    ): List<Poi> = withContext(Dispatchers.IO) {
        try {
            CrashLogger.log("PoiRepository: Searching for ${type.displayName} along route with ${routeGeometry.size} points")

            if (routeGeometry.isEmpty()) {
                return@withContext emptyList()
            }

            // Für Tankstellen: HERE Fuel Prices API mit Corridor-Suche (EINE einzige Anfrage!)
            // KEIN Sample-Points Fallback - das würde 10-20 API-Aufrufe pro Route verbrauchen
            // Bei 100/Monat Free Tier wäre das zu schnell aufgebraucht
            if (type == PoiType.GAS_STATION) {
                if (HereConfig.isConfigured() && HereConfig.canMakeFuelPricesRequest()) {
                    val results = searchGasStationsAlongRoute(routeGeometry, currentLocation, maxDistanceFromRouteKm, limit)
                    if (results.isNotEmpty()) {
                        return@withContext results
                    }
                    CrashLogger.log("PoiRepository: Corridor search returned no results, using Nominatim fallback")
                }
                // Fallback: Nominatim (kostenlos, unbegrenzt, aber ohne Preise)
                return@withContext searchGasStationsWithNominatim(routeGeometry, currentLocation, maxDistanceFromRouteKm, limit)
            }

            // Für andere POIs (z.B. McDonald's): Sample-Punkte entlang der Route (alle ~5km)
            val samplePoints = sampleRoutePoints(routeGeometry, 5.0)

            val allResults = mutableListOf<Poi>()
            val seenLocations = mutableSetOf<String>()

            for ((index, samplePoint) in samplePoints.withIndex()) {
                try {
                    val results = searchWithNominatim(type, samplePoint, maxDistanceFromRouteKm + 3.0)

                    // Nur POIs hinzufügen, die nah an der Route sind und nicht schon vorhanden
                    for (poi in results) {
                        val locationKey = "${poi.lat.format(4)}_${poi.lng.format(4)}"
                        if (locationKey !in seenLocations) {
                            val distanceToRoute = minDistanceToRoute(poi.lat, poi.lng, routeGeometry)
                            if (distanceToRoute <= maxDistanceFromRouteKm) {
                                // Entfernung vom aktuellen Standort berechnen
                                val distanceFromCurrent = GeoUtils.calculateDistanceKilometers(
                                    currentLocation.lat, currentLocation.lng,
                                    poi.lat, poi.lng
                                )
                                // Umweg berechnen: Hin + Zurück zur Route (~2x Abstand von Route)
                                val detourMinutes = estimateDetourTime(distanceToRoute)
                                allResults.add(poi.copy(
                                    distanceKm = distanceFromCurrent,
                                    estimatedArrivalMinutes = estimateArrivalTime(distanceFromCurrent),
                                    detourMinutes = detourMinutes
                                ))
                                seenLocations.add(locationKey)
                            }
                        }
                    }
                } catch (e: Exception) {
                    CrashLogger.logError("PoiRepository", "Sample point ${index + 1} query failed", e)
                    // Continue with next sample point
                }
            }

            // Nach Entfernung vom aktuellen Standort sortieren
            val sortedResults = allResults
                .sortedBy { it.distanceKm }
                .take(limit)

            CrashLogger.log("PoiRepository: Found ${sortedResults.size} ${type.displayName} along route")
            sortedResults

        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "Search along route failed for ${type.displayName}", e)
            emptyList()
        }
    }

    /**
     * Sucht POIs in der Nähe einer Position (Fallback wenn keine Route)
     */
    suspend fun searchNearby(
        type: PoiType,
        currentLocation: LatLng,
        radiusKm: Double = 10.0,
        limit: Int = 10
    ): List<Poi> = withContext(Dispatchers.IO) {
        try {
            CrashLogger.log("PoiRepository: Searching for ${type.displayName} near ${currentLocation.lat},${currentLocation.lng}")

            val results = when (type) {
                PoiType.GAS_STATION -> searchGasStationsWithHere(currentLocation, radiusKm)
                else -> searchWithNominatim(type, currentLocation, radiusKm)
            }

            // Nach Entfernung sortieren und limitieren
            val sortedResults = results
                .sortedBy { it.distanceKm }
                .take(limit)

            CrashLogger.log("PoiRepository: Found ${sortedResults.size} ${type.displayName} locations")
            sortedResults

        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "Search failed for ${type.displayName}", e)
            emptyList()
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    /**
     * Sample-Punkte entlang der Route (ca. alle sampleDistanceKm Kilometer)
     */
    private fun sampleRoutePoints(geometry: List<LatLng>, sampleDistanceKm: Double): List<LatLng> {
        if (geometry.isEmpty()) return emptyList()
        if (geometry.size == 1) return geometry

        val samples = mutableListOf(geometry.first())
        var accumulatedDistance = 0.0

        for (i in 1 until geometry.size) {
            val prev = geometry[i - 1]
            val curr = geometry[i]
            val segmentDistance = GeoUtils.calculateDistanceKilometers(prev.lat, prev.lng, curr.lat, curr.lng)
            accumulatedDistance += segmentDistance

            if (accumulatedDistance >= sampleDistanceKm) {
                samples.add(curr)
                accumulatedDistance = 0.0
            }
        }

        // Letzten Punkt immer hinzufügen
        if (samples.last() != geometry.last()) {
            samples.add(geometry.last())
        }

        return samples
    }

    /**
     * Minimale Entfernung eines Punktes zur Route
     */
    private fun minDistanceToRoute(lat: Double, lng: Double, routeGeometry: List<LatLng>): Double {
        if (routeGeometry.isEmpty()) return Double.MAX_VALUE

        var minDist = Double.MAX_VALUE
        for (point in routeGeometry) {
            val dist = GeoUtils.calculateDistanceKilometers(lat, lng, point.lat, point.lng)
            if (dist < minDist) {
                minDist = dist
            }
        }
        return minDist
    }

    /**
     * Sucht Tankstellen entlang einer Route mit HERE Fuel Prices API v3 Corridor-Suche
     * POST https://fuel.hereapi.com/v3/stations mit corridor Body
     * Effizient: Eine einzige Anfrage für die gesamte Route
     */
    private suspend fun searchGasStationsAlongRoute(
        routeGeometry: List<LatLng>,
        currentLocation: LatLng,
        maxDistanceFromRouteKm: Double,
        limit: Int
    ): List<Poi> = withContext(Dispatchers.IO) {
        try {
            val apiKey = HereConfig.getApiKey()
            val widthMeters = (maxDistanceFromRouteKm * 1000).toInt().coerceIn(50, 20000)

            // Sample-Punkte für Corridor (max ~50 Punkte, alle ~2km)
            val corridorPoints = sampleRoutePoints(routeGeometry, 2.0).take(50)
            if (corridorPoints.size < 2) {
                CrashLogger.log("PoiRepository: Not enough points for corridor search")
                return@withContext emptyList()
            }

            // JSON Body für POST Request
            val corridorArray = JSONArray()
            for (point in corridorPoints) {
                val pointObj = JSONObject()
                pointObj.put("lat", point.lat)
                pointObj.put("lng", point.lng)
                corridorArray.put(pointObj)
            }

            val requestBody = JSONObject()
            requestBody.put("corridor", corridorArray)
            requestBody.put("width", widthMeters)

            CrashLogger.log("PoiRepository: HERE Fuel Prices v3 corridor search with ${corridorPoints.size} points, width=${widthMeters}m")

            val url = URL("https://fuel.hereapi.com/v3/stations?apiKey=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "DaLang Navigation App")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true

            // Request Body schreiben
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                CrashLogger.logError("PoiRepository", "Corridor search failed with code $responseCode")
                return@withContext emptyList()
            }

            val response = connection.inputStream.bufferedReader().readText()

            // Fuel Prices Usage zählen
            HereConfig.incrementFuelPricesUsage()
            CrashLogger.log("PoiRepository: Fuel Prices usage: ${HereConfig.getFuelPricesMonthlyUsage()}/${HereConfig.getFuelPricesMonthlyLimit()}")

            val json = JSONObject(response)
            val stationsArray = json.optJSONArray("stations")

            if (stationsArray == null) {
                CrashLogger.log("PoiRepository: Corridor search - no stations in response")
                return@withContext emptyList()
            }

            CrashLogger.log("PoiRepository: Corridor search found ${stationsArray.length()} stations")

            val results = mutableListOf<Poi>()
            for (i in 0 until stationsArray.length()) {
                val station = stationsArray.getJSONObject(i)
                val poi = parseStationToPoi(station, currentLocation, routeGeometry)
                if (poi != null) {
                    results.add(poi)
                }
            }

            // Nach Entfernung sortieren und limitieren
            val sortedResults = results.sortedBy { it.distanceKm }.take(limit)
            CrashLogger.log("PoiRepository: Corridor search returned ${sortedResults.size} stations")
            sortedResults

        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "Corridor search failed", e)
            emptyList()
        }
    }

    /**
     * Parst ein Station-JSON-Objekt zu einem Poi
     */
    private fun parseStationToPoi(
        station: JSONObject,
        currentLocation: LatLng,
        routeGeometry: List<LatLng>? = null
    ): Poi? {
        try {
            val positionObj = station.optJSONObject("position") ?: return null
            val lat = positionObj.optDouble("lat", Double.NaN)
            val lng = positionObj.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) return null

            val brand = station.optString("brand", "").ifEmpty { "Tankstelle" }
            val name = station.optString("name", brand)

            // Adresse
            val addressObj = station.optJSONObject("address")
            val address = if (addressObj != null) {
                val street = addressObj.optString("street", "")
                val houseNumber = addressObj.optString("houseNumber", "")
                val city = addressObj.optString("city", "")
                buildString {
                    if (street.isNotEmpty()) {
                        append(street)
                        if (houseNumber.isNotEmpty()) append(" $houseNumber")
                    }
                    if (city.isNotEmpty()) {
                        if (isNotEmpty()) append(", ")
                        append(city)
                    }
                }.ifEmpty { null }
            } else null

            val distanceFromCurrent = GeoUtils.calculateDistanceKilometers(currentLocation.lat, currentLocation.lng, lat, lng)
            val arrivalMinutes = estimateArrivalTime(distanceFromCurrent)

            // Umweg berechnen wenn Route vorhanden
            val detourMinutes = if (routeGeometry != null && routeGeometry.isNotEmpty()) {
                val distanceToRoute = minDistanceToRoute(lat, lng, routeGeometry)
                estimateDetourTime(distanceToRoute)
            } else 0

            // Kraftstoffpreise
            var diesel: Double? = null
            var e5: Double? = null

            val pricesArray = station.optJSONArray("prices")
            if (pricesArray != null) {
                for (j in 0 until pricesArray.length()) {
                    val priceObj = pricesArray.getJSONObject(j)
                    val fuelTypeId = priceObj.optInt("fuelType", -1)
                    val price = priceObj.optDouble("price", Double.NaN).takeIf { !it.isNaN() }

                    when (fuelTypeId) {
                        1 -> diesel = price  // Diesel
                        53 -> e5 = price     // Super E5
                        54 -> if (e5 == null) e5 = price  // Super E10 als Fallback
                        2 -> if (e5 == null) e5 = price   // Regular als Fallback
                    }
                }
            }

            val fuelPrices = if (diesel != null || e5 != null) {
                FuelPrices(diesel = diesel, e5 = e5, e10 = null)
            } else null

            // Öffnungszeiten aus HERE API parsen
            val openingHours = parseHereOpeningHours(station)

            return Poi(
                name = brand.ifEmpty { name },
                lat = lat,
                lng = lng,
                address = address,
                distanceKm = distanceFromCurrent,
                estimatedArrivalMinutes = arrivalMinutes,
                detourMinutes = detourMinutes,
                fuelPrices = fuelPrices,
                openingHours = openingHours
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Parst Öffnungszeiten aus HERE Fuel Prices API Response
     * Format: { "openingHours": { "regularOpeningHours": [{ "daymask": 127, "period": [{ "from": "06:00:00", "to": "22:00:00" }] }] } }
     * Oder: "open24x7": true
     */
    private fun parseHereOpeningHours(station: JSONObject): String? {
        try {
            // Prüfe ob 24/7 geöffnet
            if (station.optBoolean("open24x7", false)) {
                return "24/7"
            }

            val openingHoursObj = station.optJSONObject("openingHours") ?: return null
            val regularHours = openingHoursObj.optJSONArray("regularOpeningHours") ?: return null

            if (regularHours.length() == 0) return null

            // Sammle alle Öffnungszeiten und konvertiere zu OSM-Format
            val dayNames = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
            val result = StringBuilder()

            for (i in 0 until regularHours.length()) {
                val entry = regularHours.getJSONObject(i)
                val daymask = entry.optInt("daymask", 0)
                val periodsArray = entry.optJSONArray("period") ?: continue

                if (periodsArray.length() == 0) continue

                // Erste Periode nehmen
                val period = periodsArray.getJSONObject(0)
                val from = period.optString("from", "").take(5)  // "06:00:00" -> "06:00"
                val to = period.optString("to", "").take(5)

                if (from.isEmpty() || to.isEmpty()) continue

                // Daymask zu Tagen konvertieren (Bitmask: 1=Mo, 2=Tu, 4=We, 8=Th, 16=Fr, 32=Sa, 64=Su)
                val days = mutableListOf<String>()
                for (d in 0..6) {
                    if ((daymask and (1 shl d)) != 0) {
                        days.add(dayNames[d])
                    }
                }

                if (days.isEmpty()) continue

                // Zusammenhängende Tage als Bereich formatieren
                val dayStr = if (days.size == 7) {
                    "Mo-Su"
                } else if (days == listOf("Mo", "Tu", "We", "Th", "Fr")) {
                    "Mo-Fr"
                } else if (days == listOf("Sa", "Su")) {
                    "Sa-Su"
                } else {
                    days.joinToString(",")
                }

                if (result.isNotEmpty()) result.append("; ")
                result.append("$dayStr $from-$to")
            }

            return result.toString().ifEmpty { null }
        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "Failed to parse HERE opening hours", e)
            return null
        }
    }

    /**
     * Sucht Tankstellen mit HERE Fuel Prices API v3 inkl. Spritpreise (Circle Search)
     * API: https://fuel.hereapi.com/v3/stations
     * Docs: https://developer.here.com/documentation/fuel-prices
     */
    private suspend fun searchGasStationsWithHere(
        center: LatLng,
        radiusKm: Double
    ): List<Poi> = withContext(Dispatchers.IO) {
        try {
            // Prüfen ob HERE API konfiguriert ist
            if (!HereConfig.isConfigured()) {
                CrashLogger.log("PoiRepository: HERE not configured, falling back to Nominatim")
                return@withContext searchWithNominatim(PoiType.GAS_STATION, center, radiusKm)
            }

            // Prüfen ob Fuel Prices Limit erreicht ist (100/Monat)
            if (!HereConfig.canMakeFuelPricesRequest()) {
                CrashLogger.log("PoiRepository: Fuel Prices limit reached (${HereConfig.getFuelPricesMonthlyUsage()}/${HereConfig.getFuelPricesMonthlyLimit()}), falling back to Nominatim")
                return@withContext searchWithNominatim(PoiType.GAS_STATION, center, radiusKm)
            }

            val apiKey = HereConfig.getApiKey()
            // HERE Fuel Prices API v3 - in=circle:{lat},{lng};r={radius}
            val radiusMeters = (radiusKm * 1000).toInt().coerceAtMost(100000)
            val url = "https://fuel.hereapi.com/v3/stations?" +
                "in=circle:${center.lat},${center.lng};r=$radiusMeters" +
                "&apiKey=$apiKey"

            CrashLogger.log("PoiRepository: HERE Fuel Prices v3 request at ${center.lat},${center.lng} radius ${radiusKm}km")

            val connection = URL(url).openConnection()
            connection.setRequestProperty("User-Agent", "DaLang Navigation App")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.getInputStream().bufferedReader().readText()

            // Fuel Prices Usage zählen (separates Limit von 100/Monat)
            HereConfig.incrementFuelPricesUsage()
            CrashLogger.log("PoiRepository: Fuel Prices usage: ${HereConfig.getFuelPricesMonthlyUsage()}/${HereConfig.getFuelPricesMonthlyLimit()}")

            val json = JSONObject(response)

            // HERE Fuel Prices API v3 Response Format:
            // { "stations": [...] }
            val stationsArray = json.optJSONArray("stations")

            if (stationsArray == null) {
                CrashLogger.log("PoiRepository: HERE Fuel Prices - no stations in response, fallback to Nominatim")
                return@withContext searchWithNominatim(PoiType.GAS_STATION, center, radiusKm)
            }
            val results = mutableListOf<Poi>()

            for (i in 0 until stationsArray.length()) {
                val station = stationsArray.getJSONObject(i)

                // Position - v3 nutzt "position" Object oder direkt lat/lng
                val positionObj = station.optJSONObject("position")
                val lat = positionObj?.optDouble("lat", 0.0)
                    ?: positionObj?.optDouble("latitude", 0.0)
                    ?: station.optDouble("lat", 0.0)
                val lng = positionObj?.optDouble("lng", 0.0)
                    ?: positionObj?.optDouble("longitude", 0.0)
                    ?: station.optDouble("lng", 0.0)

                val brand = station.optString("brand", "")
                    .ifEmpty { station.optString("brandName", "Tankstelle") }
                val name = station.optString("name", brand)

                // Adresse
                val addressObj = station.optJSONObject("address")
                val address = if (addressObj != null) {
                    val street = addressObj.optString("street", "")
                    val streetNumber = addressObj.optString("streetNumber", "")
                    val city = addressObj.optString("city", "")
                    buildString {
                        if (street.isNotEmpty()) {
                            append(street)
                            if (streetNumber.isNotEmpty()) append(" $streetNumber")
                        }
                        if (city.isNotEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append(city)
                        }
                    }.ifEmpty { null }
                } else null

                val distance = GeoUtils.calculateDistanceKilometers(center.lat, center.lng, lat, lng)
                val arrivalMinutes = estimateArrivalTime(distance)

                // Kraftstoffpreise - v3 nutzt "prices" Array mit fuelType ID
                // FuelType IDs: 1=Diesel, 53=Super E5, 54=Super E10, 2=Regular
                var diesel: Double? = null
                var e5: Double? = null

                val pricesArray = station.optJSONArray("prices")
                if (pricesArray != null) {
                    for (j in 0 until pricesArray.length()) {
                        val priceObj = pricesArray.getJSONObject(j)
                        val fuelTypeId = priceObj.optInt("fuelType", -1)
                        val price = priceObj.optDouble("price", Double.NaN).takeIf { !it.isNaN() }

                        when (fuelTypeId) {
                            1 -> diesel = price  // Diesel
                            53 -> e5 = price     // Super E5
                            54 -> if (e5 == null) e5 = price  // Super E10 als Fallback
                            2 -> if (e5 == null) e5 = price   // Regular als Fallback
                        }
                    }
                }

                val fuelPrices = if (diesel != null || e5 != null) {
                    FuelPrices(diesel = diesel, e5 = e5, e10 = null)
                } else null

                // Öffnungszeiten aus HERE API
                val openingHours = parseHereOpeningHours(station)

                results.add(
                    Poi(
                        name = brand.ifEmpty { name },
                        lat = lat,
                        lng = lng,
                        address = address,
                        distanceKm = distance,
                        estimatedArrivalMinutes = arrivalMinutes,
                        fuelPrices = fuelPrices,
                        openingHours = openingHours
                    )
                )
            }

            CrashLogger.log("PoiRepository: HERE Fuel Prices returned ${results.size} stations with prices")
            results

        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "HERE Fuel Prices failed, falling back to Nominatim", e)
            searchWithNominatim(PoiType.GAS_STATION, center, radiusKm)
        }
    }

    private suspend fun searchWithNominatim(
        type: PoiType,
        center: LatLng,
        radiusKm: Double
    ): List<Poi> = withContext(Dispatchers.IO) {
        try {
            // Bounding Box berechnen
            val latDelta = radiusKm / 111.0  // ~111 km pro Grad Latitude
            val lngDelta = radiusKm / (111.0 * cos(Math.toRadians(center.lat)))

            val minLat = center.lat - latDelta
            val maxLat = center.lat + latDelta
            val minLng = center.lng - lngDelta
            val maxLng = center.lng + lngDelta

            val query = URLEncoder.encode(type.searchQuery, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?" +
                "q=$query" +
                "&format=json" +
                "&limit=20" +
                "&viewbox=$minLng,$maxLat,$maxLng,$minLat" +
                "&bounded=1" +
                "&extratags=1"  // Für Öffnungszeiten

            val connection = URL(url).openConnection()
            connection.setRequestProperty("User-Agent", "DaLang Navigation App")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.getInputStream().bufferedReader().readText()
            val jsonArray = JSONArray(response)

            val results = mutableListOf<Poi>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val lat = item.optDouble("lat", 0.0)
                val lng = item.optDouble("lon", 0.0)
                val displayName = item.optString("display_name", type.displayName)

                // Name extrahieren (erster Teil vor dem Komma)
                val name = displayName.split(",").firstOrNull()?.trim() ?: type.displayName

                // Adresse (Rest nach dem Namen)
                val address = displayName.split(",").drop(1).take(3).joinToString(", ").trim()

                // Öffnungszeiten aus extratags extrahieren
                val extratags = item.optJSONObject("extratags")
                val openingHours = extratags?.optString("opening_hours", null)?.takeIf { it.isNotEmpty() }

                val distance = GeoUtils.calculateDistanceKilometers(center.lat, center.lng, lat, lng)
                val arrivalMinutes = estimateArrivalTime(distance)

                results.add(
                    Poi(
                        name = name,
                        lat = lat,
                        lng = lng,
                        address = address.ifEmpty { null },
                        distanceKm = distance,
                        estimatedArrivalMinutes = arrivalMinutes,
                        openingHours = openingHours
                    )
                )
            }

            results

        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "Nominatim search failed", e)
            emptyList()
        }
    }

    /**
     * Sucht Tankstellen entlang einer Route mit Nominatim (kostenlos, unbegrenzt)
     * Verwendet nur 3 Sample-Punkte um Rate-Limiting zu vermeiden
     * Keine Spritpreise verfügbar
     */
    private suspend fun searchGasStationsWithNominatim(
        routeGeometry: List<LatLng>,
        currentLocation: LatLng,
        maxDistanceFromRouteKm: Double,
        limit: Int
    ): List<Poi> = withContext(Dispatchers.IO) {
        try {
            CrashLogger.log("PoiRepository: Nominatim fallback for gas stations (no prices)")

            // Nur 3 Sample-Punkte: Start, Mitte, Ende der Route
            val samplePoints = if (routeGeometry.size >= 3) {
                listOf(
                    routeGeometry.first(),
                    routeGeometry[routeGeometry.size / 2],
                    routeGeometry.last()
                )
            } else {
                routeGeometry.take(3)
            }

            val allResults = mutableListOf<Poi>()
            val seenLocations = mutableSetOf<String>()

            for (samplePoint in samplePoints) {
                val results = searchWithNominatim(PoiType.GAS_STATION, samplePoint, maxDistanceFromRouteKm + 5.0)

                for (poi in results) {
                    val locationKey = "${poi.lat.format(4)}_${poi.lng.format(4)}"
                    if (locationKey !in seenLocations) {
                        val distanceToRoute = minDistanceToRoute(poi.lat, poi.lng, routeGeometry)
                        if (distanceToRoute <= maxDistanceFromRouteKm) {
                            val distanceFromCurrent = GeoUtils.calculateDistanceKilometers(
                                currentLocation.lat, currentLocation.lng,
                                poi.lat, poi.lng
                            )
                            val detourMinutes = estimateDetourTime(distanceToRoute)
                            allResults.add(poi.copy(
                                distanceKm = distanceFromCurrent,
                                estimatedArrivalMinutes = estimateArrivalTime(distanceFromCurrent),
                                detourMinutes = detourMinutes
                            ))
                            seenLocations.add(locationKey)
                        }
                    }
                }
            }

            val sortedResults = allResults.sortedBy { it.distanceKm }.take(limit)
            CrashLogger.log("PoiRepository: Nominatim found ${sortedResults.size} gas stations (no prices)")
            sortedResults

        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "Nominatim gas station search failed", e)
            emptyList()
        }
    }

    /**
     * Schätzt die Ankunftszeit basierend auf Entfernung
     * Annahme: ~40 km/h Durchschnittsgeschwindigkeit in der Stadt
     */
    private fun estimateArrivalTime(distanceKm: Double): Int {
        val avgSpeedKmh = 40.0
        val timeHours = distanceKm / avgSpeedKmh
        return (timeHours * 60).toInt().coerceAtLeast(1)
    }

    /**
     * Schätzt die Umwegzeit für einen POI abseits der Route
     * Berechnet: 2x Entfernung zur Route (hin + zurück) bei ~30 km/h
     */
    private fun estimateDetourTime(distanceToRouteKm: Double): Int {
        val avgSpeedKmh = 30.0  // Etwas langsamer wegen Abfahrt/Auffahrt
        val detourDistanceKm = distanceToRouteKm * 2  // Hin + Zurück
        val timeHours = detourDistanceKm / avgSpeedKmh
        return (timeHours * 60).toInt().coerceAtLeast(1)
    }
}
