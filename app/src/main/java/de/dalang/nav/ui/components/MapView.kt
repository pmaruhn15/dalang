package de.dalang.nav.ui.components

import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.navigation.Route
import de.dalang.nav.util.CrashLogger
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

@Composable
fun MapViewComposable(
    currentLocation: LatLng?,
    destination: LatLng?,
    route: Route?,
    isNavigating: Boolean,
    onMapClick: ((LatLng) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDarkTheme = isSystemInDarkTheme()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var isMapReady by remember { mutableStateOf(false) }
    var hasCenteredOnLocation by remember { mutableStateOf(false) }

    // Style basierend auf Theme
    val styleUrl = remember(isDarkTheme) {
        if (isDarkTheme) {
            "https://tiles.openfreemap.org/styles/dark"
        } else {
            "https://tiles.openfreemap.org/styles/positron"
        }
    }

    AndroidView(
        factory = { ctx ->
            CrashLogger.log("MapView: Creating MapView")
            MapView(ctx).also { view ->
                mapView = view
                try {
                    view.getMapAsync { map ->
                        CrashLogger.log("MapView: Map async ready")
                        mapLibreMap = map

                        try {
                            map.setStyle(styleUrl) { style ->
                                CrashLogger.log("MapView: Style loaded")
                                try {
                                    map.uiSettings.apply {
                                        isCompassEnabled = true
                                        isRotateGesturesEnabled = true
                                        isZoomGesturesEnabled = true
                                        isTiltGesturesEnabled = false
                                    }

                                    // Initiale Kameraposition (Deutschland)
                                    val initialPosition = currentLocation?.let {
                                        org.maplibre.android.geometry.LatLng(it.lat, it.lng)
                                    } ?: org.maplibre.android.geometry.LatLng(51.1657, 10.4515)

                                    map.cameraPosition = CameraPosition.Builder()
                                        .target(initialPosition)
                                        .zoom(if (currentLocation != null) 15.0 else 5.0)
                                        .build()

                                    // Klick-Handler fuer Kartenklicks
                                    map.addOnMapClickListener { point ->
                                        CrashLogger.log("MapView: Map clicked at ${point.latitude}, ${point.longitude}")
                                        onMapClick?.invoke(LatLng(point.latitude, point.longitude))
                                        true
                                    }

                                    isMapReady = true
                                } catch (e: Exception) {
                                    CrashLogger.logError("MapView", "Style setup failed", e)
                                }
                            }
                        } catch (e: Exception) {
                            CrashLogger.logError("MapView", "setStyle failed", e)
                        }
                    }
                } catch (e: Exception) {
                    CrashLogger.logError("MapView", "getMapAsync failed", e)
                }
            }
        },
        update = { _ ->
            try {
                val map = mapLibreMap
                if (map != null && isMapReady) {
                    // Kamera auf aktuelle Position zentrieren (nur beim ersten Mal oder bei Navigation)
                    if (currentLocation != null) {
                        if (isNavigating) {
                            val pos = org.maplibre.android.geometry.LatLng(
                                currentLocation.lat,
                                currentLocation.lng
                            )
                            map.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(pos)
                                        .zoom(17.0)
                                        .tilt(45.0)
                                        .build()
                                ),
                                500
                            )
                        } else if (!hasCenteredOnLocation) {
                            // Einmalig auf Standort zentrieren beim App-Start
                            val pos = org.maplibre.android.geometry.LatLng(
                                currentLocation.lat,
                                currentLocation.lng
                            )
                            map.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(pos)
                                        .zoom(15.0)
                                        .build()
                                ),
                                1000
                            )
                            hasCenteredOnLocation = true
                        }
                    }
                }
            } catch (e: Exception) {
                CrashLogger.logError("MapView", "Update failed", e)
            }
        },
        modifier = modifier
    )

    // Lifecycle-Management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            try {
                val view = mapView ?: return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START -> view.onStart()
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    Lifecycle.Event.ON_STOP -> view.onStop()
                    Lifecycle.Event.ON_DESTROY -> view.onDestroy()
                    else -> {}
                }
            } catch (e: Exception) {
                CrashLogger.logError("MapView", "Lifecycle event failed: $event", e)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            try {
                lifecycleOwner.lifecycle.removeObserver(observer)
            } catch (e: Exception) {
                CrashLogger.logError("MapView", "Remove observer failed", e)
            }
        }
    }

    // Standort-Marker zeichnen
    LaunchedEffect(currentLocation, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val location = currentLocation ?: return@LaunchedEffect

        try {
            map.getStyle { style ->
                try {
                    // Vorherigen Marker entfernen
                    try {
                        style.removeLayer("location-layer")
                        style.removeLayer("location-pulse-layer")
                        style.removeSource("location-source")
                    } catch (e: Exception) {
                        // Layer existiert nicht
                    }

                    // Standort als GeoJSON Point
                    val geoJson = """
                        {
                            "type": "Feature",
                            "geometry": {
                                "type": "Point",
                                "coordinates": [${location.lng}, ${location.lat}]
                            }
                        }
                    """.trimIndent()

                    val source = GeoJsonSource("location-source", geoJson)
                    style.addSource(source)

                    // Aeusserer Kreis (Puls-Effekt)
                    val pulseLayer = CircleLayer("location-pulse-layer", "location-source").apply {
                        setProperties(
                            PropertyFactory.circleRadius(20f),
                            PropertyFactory.circleColor(Color.parseColor("#1976D2")),
                            PropertyFactory.circleOpacity(0.2f)
                        )
                    }
                    style.addLayer(pulseLayer)

                    // Innerer Kreis (Standort)
                    val locationLayer = CircleLayer("location-layer", "location-source").apply {
                        setProperties(
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleColor(Color.parseColor("#1976D2")),
                            PropertyFactory.circleStrokeWidth(3f),
                            PropertyFactory.circleStrokeColor(Color.WHITE)
                        )
                    }
                    style.addLayer(locationLayer)

                } catch (e: Exception) {
                    CrashLogger.logError("MapView", "Location marker failed", e)
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("MapView", "getStyle failed for location", e)
        }
    }

    // Route zeichnen
    LaunchedEffect(route, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect

        try {
            map.getStyle { style ->
                try {
                    // Vorhandene Route entfernen
                    try {
                        style.removeLayer("route-layer")
                        style.removeSource("route-source")
                    } catch (e: Exception) {
                        // Layer existiert nicht
                    }

                    if (route != null && route.geometry.isNotEmpty()) {
                        val coordinatesJson = route.geometry.joinToString(",") { pt ->
                            "[${pt.lng},${pt.lat}]"
                        }
                        val geoJson = """
                            {
                                "type": "Feature",
                                "geometry": {
                                    "type": "LineString",
                                    "coordinates": [$coordinatesJson]
                                }
                            }
                        """.trimIndent()

                        val source = GeoJsonSource("route-source", geoJson)
                        style.addSource(source)

                        val lineLayer = LineLayer("route-layer", "route-source").apply {
                            setProperties(
                                PropertyFactory.lineColor(Color.parseColor("#1976D2")),
                                PropertyFactory.lineWidth(6f),
                                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                            )
                        }
                        style.addLayer(lineLayer)

                        CrashLogger.log("MapView: Route drawn with ${route.geometry.size} points")

                        // Kamera auf Route zentrieren
                        if (!isNavigating && route.geometry.size >= 2) {
                            try {
                                val bounds = LatLngBounds.Builder()
                                route.geometry.forEach { point ->
                                    bounds.include(
                                        org.maplibre.android.geometry.LatLng(point.lat, point.lng)
                                    )
                                }
                                map.animateCamera(
                                    CameraUpdateFactory.newLatLngBounds(
                                        bounds.build(),
                                        100
                                    ),
                                    1000
                                )
                            } catch (e: Exception) {
                                CrashLogger.logError("MapView", "Camera bounds animation failed", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    CrashLogger.logError("MapView", "Route drawing failed", e)
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("MapView", "getStyle failed in LaunchedEffect", e)
        }
    }

    // Ziel-Marker zeichnen
    LaunchedEffect(destination, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect

        try {
            map.getStyle { style ->
                try {
                    // Vorherigen Ziel-Marker entfernen
                    try {
                        style.removeLayer("destination-layer")
                        style.removeSource("destination-source")
                    } catch (e: Exception) {
                        // Layer existiert nicht
                    }

                    if (destination != null) {
                        val geoJson = """
                            {
                                "type": "Feature",
                                "geometry": {
                                    "type": "Point",
                                    "coordinates": [${destination.lng}, ${destination.lat}]
                                }
                            }
                        """.trimIndent()

                        val source = GeoJsonSource("destination-source", geoJson)
                        style.addSource(source)

                        val destLayer = CircleLayer("destination-layer", "destination-source").apply {
                            setProperties(
                                PropertyFactory.circleRadius(10f),
                                PropertyFactory.circleColor(Color.parseColor("#E53935")),
                                PropertyFactory.circleStrokeWidth(3f),
                                PropertyFactory.circleStrokeColor(Color.WHITE)
                            )
                        }
                        style.addLayer(destLayer)
                    }
                } catch (e: Exception) {
                    CrashLogger.logError("MapView", "Destination marker failed", e)
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("MapView", "getStyle failed for destination", e)
        }
    }
}
