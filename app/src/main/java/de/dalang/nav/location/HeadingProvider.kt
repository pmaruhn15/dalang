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

class HeadingProvider(private val context: Context) {

    private val sensorManager: SensorManager? = try {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    } catch (e: Exception) {
        CrashLogger.logError("HeadingProvider", "Failed to get SensorManager", e)
        null
    }

    fun headingUpdates(): Flow<Float> = callbackFlow {
        val manager = sensorManager
        if (manager == null) {
            CrashLogger.logError("HeadingProvider", "SensorManager is null")
            close()
            return@callbackFlow
        }

        val rotationVectorSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVectorSensor == null) {
            CrashLogger.log("HeadingProvider: No rotation vector sensor, trying accelerometer/magnetometer")
            // Fallback to accelerometer and magnetometer
            startAccelMagHeading(manager, this)
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                try {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)

                    // Convert azimuth from radians to degrees (0-360)
                    var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    if (heading < 0) heading += 360f

                    trySend(heading)
                } catch (e: Exception) {
                    CrashLogger.logError("HeadingProvider", "Rotation sensor processing failed", e)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        manager.registerListener(listener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)

        awaitClose {
            manager.unregisterListener(listener)
        }
    }

    private fun startAccelMagHeading(
        manager: SensorManager,
        scope: kotlinx.coroutines.channels.ProducerScope<Float>
    ) {
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (accelerometer == null || magnetometer == null) {
            CrashLogger.logError("HeadingProvider", "No compass sensors available")
            scope.close()
            return
        }

        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                try {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            System.arraycopy(event.values, 0, gravity, 0, 3)
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                        }
                    }

                    if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                        SensorManager.getOrientation(rotationMatrix, orientation)

                        var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        if (heading < 0) heading += 360f

                        scope.trySend(heading)
                    }
                } catch (e: Exception) {
                    CrashLogger.logError("HeadingProvider", "Accel/Mag sensor processing failed", e)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        manager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)

        scope.invokeOnClose {
            manager.unregisterListener(listener)
        }
    }
}
