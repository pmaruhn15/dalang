package de.dalang.nav.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationProvider(private val context: Context) {

    private val locationManager: LocationManager? = try {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    } catch (e: Exception) {
        CrashLogger.logError("LocationProvider", "Failed to get LocationManager", e)
        null
    }

    init {
        CrashLogger.log("LocationProvider: initialized, manager=${locationManager != null}")
    }

    val isLocationEnabled: Boolean
        get() = try {
            val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            val networkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            CrashLogger.log("LocationProvider: GPS=$gpsEnabled, Network=$networkEnabled")
            gpsEnabled || networkEnabled
        } catch (e: Exception) {
            CrashLogger.logError("LocationProvider", "isLocationEnabled check failed", e)
            false
        }

    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): Location? {
        return try {
            val manager = locationManager
            if (manager == null) {
                CrashLogger.logError("LocationProvider", "LocationManager is null")
                return null
            }

            val gpsLocation = try {
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {
                CrashLogger.logError("LocationProvider", "GPS location failed", e)
                null
            }

            val networkLocation = try {
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                CrashLogger.logError("LocationProvider", "Network location failed", e)
                null
            }

            val location = gpsLocation ?: networkLocation
            if (location != null) {
                CrashLogger.log("LocationProvider: Got last known location: ${location.latitude}, ${location.longitude}")
            } else {
                CrashLogger.log("LocationProvider: No last known location available")
            }
            location
        } catch (e: Exception) {
            CrashLogger.logError("LocationProvider", "getLastKnownLocation failed", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long = 1000L): Flow<Location> = callbackFlow {
        CrashLogger.log("LocationProvider: Starting location updates")

        val manager = locationManager
        if (manager == null) {
            CrashLogger.logError("LocationProvider", "LocationManager is null, closing flow")
            close()
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try {
                    trySend(location)
                } catch (e: Exception) {
                    CrashLogger.logError("LocationProvider", "trySend failed", e)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                CrashLogger.log("LocationProvider: Status changed: $provider -> $status")
            }

            override fun onProviderEnabled(provider: String) {
                CrashLogger.log("LocationProvider: Provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                CrashLogger.log("LocationProvider: Provider disabled: $provider")
            }
        }

        try {
            // GPS Provider für höchste Genauigkeit
            val gpsEnabled = try {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {
                CrashLogger.logError("LocationProvider", "GPS check failed", e)
                false
            }

            if (gpsEnabled) {
                try {
                    CrashLogger.log("LocationProvider: Requesting GPS updates")
                    manager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        intervalMs,
                        1f,
                        listener,
                        Looper.getMainLooper()
                    )
                } catch (e: Exception) {
                    CrashLogger.logError("LocationProvider", "GPS requestLocationUpdates failed", e)
                }
            }

            // Network Provider als Fallback
            val networkEnabled = try {
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                CrashLogger.logError("LocationProvider", "Network check failed", e)
                false
            }

            if (networkEnabled) {
                try {
                    CrashLogger.log("LocationProvider: Requesting Network updates")
                    manager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        intervalMs,
                        5f,
                        listener,
                        Looper.getMainLooper()
                    )
                } catch (e: Exception) {
                    CrashLogger.logError("LocationProvider", "Network requestLocationUpdates failed", e)
                }
            }

            if (!gpsEnabled && !networkEnabled) {
                CrashLogger.logError("LocationProvider", "No location providers available")
            }
        } catch (e: Exception) {
            CrashLogger.logError("LocationProvider", "Location updates setup failed", e)
            close(e)
            return@callbackFlow
        }

        awaitClose {
            CrashLogger.log("LocationProvider: Stopping location updates")
            try {
                manager.removeUpdates(listener)
            } catch (e: Exception) {
                CrashLogger.logError("LocationProvider", "removeUpdates failed", e)
            }
        }
    }

    companion object {
        fun hasLocationPermission(context: Context): Boolean {
            return try {
                val result = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                CrashLogger.log("LocationProvider: hasLocationPermission=$result")
                result
            } catch (e: Exception) {
                CrashLogger.logError("LocationProvider", "hasLocationPermission check failed", e)
                false
            }
        }
    }
}
