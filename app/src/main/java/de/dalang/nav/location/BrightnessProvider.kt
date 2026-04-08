package de.dalang.nav.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Liefert Helligkeitswerte vom Ambient Light Sensor.
 * Wird für Auto-Dark-Mode verwendet: Dark Mode nur bei sehr wenig Licht.
 */
class BrightnessProvider(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    val isAvailable: Boolean = lightSensor != null

    /**
     * Gibt einen Flow von Helligkeitswerten (Lux) zurück.
     * Typische Werte:
     * - 0-10 Lux: Sehr dunkel (Nacht, Tunnel)
     * - 10-50 Lux: Dämmerung
     * - 50-400 Lux: Bewölkt, Innenraum
     * - 400-1000 Lux: Schatten draußen
     * - 1000-10000 Lux: Sonnig bewölkt
     * - 10000+ Lux: Direkte Sonne
     */
    fun brightnessUpdates(): Flow<Float> = callbackFlow {
        if (sensorManager == null || lightSensor == null) {
            CrashLogger.log("BrightnessProvider: No light sensor available")
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            private var lastReportedValue = -1f

            override fun onSensorChanged(event: SensorEvent?) {
                val lux = event?.values?.firstOrNull() ?: return

                // Nur melden wenn sich Wert signifikant geändert hat (Hysterese)
                // Vermeidet zu häufige Theme-Wechsel
                if (lastReportedValue < 0 || kotlin.math.abs(lux - lastReportedValue) > 3f) {
                    lastReportedValue = lux
                    trySend(lux)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(
            listener,
            lightSensor,
            SensorManager.SENSOR_DELAY_NORMAL  // ~200ms Update-Rate
        )

        CrashLogger.log("BrightnessProvider: Started listening")

        awaitClose {
            sensorManager.unregisterListener(listener)
            CrashLogger.log("BrightnessProvider: Stopped listening")
        }
    }
}
