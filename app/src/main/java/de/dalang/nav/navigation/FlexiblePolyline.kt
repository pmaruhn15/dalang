package de.dalang.nav.navigation

import de.dalang.nav.util.CrashLogger

/**
 * Decoder fuer HERE Flexible Polyline Format
 * Basiert auf der offiziellen HERE Referenzimplementierung
 * https://github.com/heremaps/flexible-polyline
 */
object FlexiblePolyline {

    // Dekodierungstabelle: Index = ord(char) - 45
    private val DECODING_TABLE = intArrayOf(
        62, -1, -1, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21,
        22, 23, 24, 25, -1, -1, -1, -1, 63, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,
        36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51
    )

    fun decode(encoded: String): List<LatLng> {
        if (encoded.isEmpty()) {
            CrashLogger.log("FlexiblePolyline: Empty input")
            return emptyList()
        }

        CrashLogger.log("FlexiblePolyline: Decoding ${encoded.length} chars, first 20: ${encoded.take(20)}")

        try {
            val decoder = Decoder(encoded)

            // Header dekodieren
            val headerValue = decoder.decodeUnsignedValue()
            val precision = headerValue and 0x0F
            val thirdDim = (headerValue shr 4) and 0x07
            val thirdDimPrecision = (headerValue shr 7) and 0x0F

            CrashLogger.log("FlexiblePolyline: headerValue=$headerValue, precision=$precision, thirdDim=$thirdDim")

            val factor = Math.pow(10.0, precision.toDouble())

            val result = mutableListOf<LatLng>()
            var lastLat = 0L
            var lastLng = 0L
            var lastZ = 0L

            while (decoder.hasMore()) {
                // Latitude Delta dekodieren
                val latDelta = decoder.decodeSignedValue()
                lastLat += latDelta

                if (!decoder.hasMore()) break

                // Longitude Delta dekodieren
                val lngDelta = decoder.decodeSignedValue()
                lastLng += lngDelta

                // Third dimension (Altitude) falls vorhanden
                if (thirdDim != 0 && decoder.hasMore()) {
                    val zDelta = decoder.decodeSignedValue()
                    lastZ += zDelta
                }

                val lat = lastLat / factor
                val lng = lastLng / factor

                // Nur die ersten paar Punkte loggen
                if (result.size < 3) {
                    CrashLogger.log("FlexiblePolyline: Point ${result.size}: raw=($lastLat,$lastLng) -> ($lat,$lng)")
                }

                result.add(LatLng(lat, lng))
            }

            CrashLogger.log("FlexiblePolyline: Decoded ${result.size} points")

            // Validierung: Erste Koordinate prüfen
            if (result.isNotEmpty()) {
                val first = result.first()
                if (first.lat < -90 || first.lat > 90 || first.lng < -180 || first.lng > 180) {
                    CrashLogger.log("FlexiblePolyline: WARNING - First point invalid: ${first.lat}, ${first.lng}")
                }
            }

            return result
        } catch (e: Exception) {
            CrashLogger.logError("FlexiblePolyline", "Decode failed: ${e.message}", e)
            return emptyList()
        }
    }

    private class Decoder(private val encoded: String) {
        private var index = 0

        fun hasMore(): Boolean = index < encoded.length

        private fun decodeChar(): Int {
            val char = encoded[index++]
            val charValue = char.code - 45
            if (charValue < 0 || charValue >= DECODING_TABLE.size) {
                throw IllegalArgumentException("Invalid character: $char")
            }
            val value = DECODING_TABLE[charValue]
            if (value < 0) {
                throw IllegalArgumentException("Invalid character: $char")
            }
            return value
        }

        fun decodeUnsignedValue(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val value = decodeChar()
                result = result or ((value and 0x1F) shl shift)
                if ((value and 0x20) == 0) {
                    return result
                }
                shift += 5
            }
        }

        fun decodeSignedValue(): Long {
            val value = decodeUnsignedValue()
            return if ((value and 1) != 0) {
                (value shr 1).inv().toLong()
            } else {
                (value shr 1).toLong()
            }
        }
    }
}
