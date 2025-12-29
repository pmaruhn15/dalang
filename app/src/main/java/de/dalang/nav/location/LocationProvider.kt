package de.dalang.nav.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationProvider(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    val isLocationEnabled: Boolean
        get() = try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        } catch (e: Exception) {
            false
        }

    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): Location? {
        return try {
            locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long = 1000L): Flow<Location> = callbackFlow {
        val manager = locationManager
        if (manager == null) {
            close()
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            // GPS Provider für höchste Genauigkeit
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    1f,
                    listener,
                    Looper.getMainLooper()
                )
            }

            // Network Provider als Fallback
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    intervalMs,
                    5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try {
                manager.removeUpdates(listener)
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    companion object {
        fun hasLocationPermission(context: Context): Boolean {
            return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
