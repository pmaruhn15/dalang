package de.dalang.nav.navigation

import de.dalang.nav.config.HereConfig
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    val fuelPrices: FuelPrices? = null  // Nur für Tankstellen
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

            // Sample-Punkte entlang der Route (alle ~5km)
            val samplePoints = sampleRoutePoints(routeGeometry, 5.0)
            CrashLogger.log("PoiRepository: Using ${samplePoints.size} sample points along route")

            val allResults = mutableListOf<Poi>()
            val seenLocations = mutableSetOf<String>()

            for (samplePoint in samplePoints) {
                val results = when (type) {
                    PoiType.GAS_STATION -> searchGasStationsWithHere(samplePoint, maxDistanceFromRouteKm + 3.0)
                    else -> searchWithNominatim(type, samplePoint, maxDistanceFromRouteKm + 3.0)
                }

                // Nur POIs hinzufügen, die nah an der Route sind und nicht schon vorhanden
                for (poi in results) {
                    val locationKey = "${poi.lat.format(4)}_${poi.lng.format(4)}"
                    if (locationKey !in seenLocations) {
                        val distanceToRoute = minDistanceToRoute(poi.lat, poi.lng, routeGeometry)
                        if (distanceToRoute <= maxDistanceFromRouteKm) {
                            // Entfernung vom aktuellen Standort berechnen
                            val distanceFromCurrent = calculateDistance(
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
            val segmentDistance = calculateDistance(prev.lat, prev.lng, curr.lat, curr.lng)
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
            val dist = calculateDistance(lat, lng, point.lat, point.lng)
            if (dist < minDist) {
                minDist = dist
            }
        }
        return minDist
    }

    /**
     * Sucht Tankstellen mit HERE Fuel Prices API inkl. Spritpreise
     * API: https://fuel-v2.cc.api.here.com/fuel/stations.json
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
            // HERE Fuel Prices API v2 - radius in Metern
            val radiusMeters = (radiusKm * 1000).toInt().coerceAtMost(100000)
            val url = "https://fuel-v2.cc.api.here.com/fuel/stations.json?" +
                "prox=${center.lat},${center.lng},$radiusMeters" +
                "&apiKey=$apiKey"

            CrashLogger.log("PoiRepository: HERE Fuel Prices request at ${center.lat},${center.lng} radius ${radiusKm}km")

            val connection = URL(url).openConnection()
            connection.setRequestProperty("User-Agent", "DaLang Navigation App")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.getInputStream().bufferedReader().readText()

            // Fuel Prices Usage zählen (separates Limit von 100/Monat)
            HereConfig.incrementFuelPricesUsage()
            CrashLogger.log("PoiRepository: Fuel Prices usage: ${HereConfig.getFuelPricesMonthlyUsage()}/${HereConfig.getFuelPricesMonthlyLimit()}")

            val json = JSONObject(response)

            // HERE Fuel Prices API v2 Response Format:
            // { "fuelStations": { "fuelStation": [...] } }
            val fuelStationsObj = json.optJSONObject("fuelStations")
            if (fuelStationsObj == null) {
                CrashLogger.log("PoiRepository: HERE Fuel Prices - no fuelStations object in response")
                CrashLogger.log("PoiRepository: Response keys: ${json.keys().asSequence().toList()}")
                return@withContext searchWithNominatim(PoiType.GAS_STATION, center, radiusKm)
            }

            val stationsArray = fuelStationsObj.optJSONArray("fuelStation") ?: return@withContext emptyList()
            val results = mutableListOf<Poi>()

            CrashLogger.log("PoiRepository: Found ${stationsArray.length()} stations in HERE response")

            for (i in 0 until stationsArray.length()) {
                val station = stationsArray.getJSONObject(i)

                // Position - HERE verwendet latitude/longitude
                val positionObj = station.optJSONObject("position")
                val lat = positionObj?.optDouble("latitude", 0.0) ?: 0.0
                val lng = positionObj?.optDouble("longitude", 0.0) ?: 0.0

                val brand = station.optString("brand", "Tankstelle")
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

                val distance = calculateDistance(center.lat, center.lng, lat, lng)
                val arrivalMinutes = estimateArrivalTime(distance)

                // Kraftstoffpreise - HERE verwendet fuelPrice Array
                var diesel: Double? = null
                var e5: Double? = null

                val fuelPriceArray = station.optJSONArray("fuelPrice")
                if (fuelPriceArray != null) {
                    for (j in 0 until fuelPriceArray.length()) {
                        val fuelPrice = fuelPriceArray.getJSONObject(j)
                        // fuelType: 1=Diesel, 2=Super, 3=Super Plus, etc.
                        val fuelTypeCode = fuelPrice.optString("fuelType", "")
                        val price = fuelPrice.optDouble("price", Double.NaN).takeIf { !it.isNaN() }

                        when (fuelTypeCode) {
                            "1" -> diesel = price  // Diesel
                            "2", "3" -> if (e5 == null) e5 = price  // Super/Super Plus
                        }
                    }
                }

                val fuelPrices = if (diesel != null || e5 != null) {
                    FuelPrices(diesel = diesel, e5 = e5, e10 = null)
                } else null

                results.add(
                    Poi(
                        name = brand.ifEmpty { name },
                        lat = lat,
                        lng = lng,
                        address = address,
                        distanceKm = distance,
                        estimatedArrivalMinutes = arrivalMinutes,
                        fuelPrices = fuelPrices
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
                "&bounded=1"

            CrashLogger.log("PoiRepository: Nominatim URL: $url")

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

                val distance = calculateDistance(center.lat, center.lng, lat, lng)
                val arrivalMinutes = estimateArrivalTime(distance)

                results.add(
                    Poi(
                        name = name,
                        lat = lat,
                        lng = lng,
                        address = address.ifEmpty { null },
                        distanceKm = distance,
                        estimatedArrivalMinutes = arrivalMinutes
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
     * Berechnet die Entfernung zwischen zwei Punkten in km (Haversine)
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0 // Erdradius in km

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLng = Math.toRadians(lng2 - lng1)

        val a = sin(deltaLat / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
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
