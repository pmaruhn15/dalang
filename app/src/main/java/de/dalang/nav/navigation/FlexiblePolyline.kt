package de.dalang.nav.navigation

import de.dalang.nav.util.CrashLogger

/**
 * Decoder fuer HERE Flexible Polyline Format
 * https://github.com/heremaps/flexible-polyline
 */
object FlexiblePolyline {

    private const val DECODING_TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun decode(encoded: String): List<LatLng> {
        if (encoded.isEmpty()) {
            CrashLogger.log("FlexiblePolyline: Empty input")
            return emptyList()
        }

        CrashLogger.log("FlexiblePolyline: Decoding ${encoded.length} chars")

        try {
            val result = mutableListOf<LatLng>()
            var index = 0

            // Header dekodieren
            val (headerValue, newIndex) = decodeUnsignedVarint(encoded, index)
            index = newIndex

            val precision = headerValue and 0x0F
            val thirdDim = (headerValue shr 4) and 0x07
            val thirdDimPrecision = (headerValue shr 7) and 0x0F

            CrashLogger.log("FlexiblePolyline: precision=$precision, thirdDim=$thirdDim")

            val multiplier = Math.pow(10.0, precision.toDouble())
            val thirdDimMultiplier = Math.pow(10.0, thirdDimPrecision.toDouble())

            var lat = 0L
            var lng = 0L
            var z = 0L

            while (index < encoded.length) {
                // Latitude
                val (latDelta, idx1) = decodeSignedVarint(encoded, index)
                index = idx1
                lat += latDelta

                if (index >= encoded.length) break

                // Longitude
                val (lngDelta, idx2) = decodeSignedVarint(encoded, index)
                index = idx2
                lng += lngDelta

                // Third dimension (altitude) - ueberspringen wenn vorhanden
                if (thirdDim != 0 && index < encoded.length) {
                    val (zDelta, idx3) = decodeSignedVarint(encoded, index)
                    index = idx3
                    z += zDelta
                }

                val decodedLat = lat / multiplier
                val decodedLng = lng / multiplier
                // Validierung und Logging bei ungültigen Koordinaten
                if (decodedLat < -90 || decodedLat > 90 || decodedLng < -180 || decodedLng > 180) {
                    CrashLogger.log("FlexiblePolyline: Invalid coord at index ${result.size}: lat=$decodedLat, lng=$decodedLng")
                }
                result.add(LatLng(decodedLat, decodedLng))
            }

            CrashLogger.log("FlexiblePolyline: Decoded ${result.size} points")
            return result
        } catch (e: Exception) {
            CrashLogger.logError("FlexiblePolyline", "Decode failed: ${e.message}", e)
            return emptyList()
        }
    }

    private fun decodeUnsignedVarint(encoded: String, startIndex: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var index = startIndex

        while (index < encoded.length) {
            val char = encoded[index]
            val value = DECODING_TABLE.indexOf(char)
            if (value < 0) throw IllegalArgumentException("Invalid character: $char")

            result = result or ((value and 0x1F) shl shift)
            index++

            if ((value and 0x20) == 0) {
                break
            }
            shift += 5
        }

        return Pair(result, index)
    }

    private fun decodeSignedVarint(encoded: String, startIndex: Int): Pair<Long, Int> {
        val (unsignedValue, newIndex) = decodeUnsignedVarint(encoded, startIndex)
        val signedValue = if ((unsignedValue and 1) != 0) {
            -(unsignedValue shr 1) - 1L
        } else {
            (unsignedValue shr 1).toLong()
        }
        return Pair(signedValue, newIndex)
    }
}
