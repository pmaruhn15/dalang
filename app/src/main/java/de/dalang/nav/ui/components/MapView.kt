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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

@Composable
fun MapViewComposable(
    currentLocation: LatLng?,
    destination: LatLng?,
    route: Route?,
    isNavigating: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDarkTheme = isSystemInDarkTheme()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    // MapLibre initialisieren
    DisposableEffect(Unit) {
        MapLibre.getInstance(context)
        onDispose { }
    }

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
            MapView(ctx).also { view ->
                mapView = view
                view.getMapAsync { map ->
                    mapLibreMap = map

                    map.setStyle(styleUrl) { _ ->
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
                    }
                }
            }
        },
        update = { _ ->
            mapLibreMap?.let { map ->
                // Kamera auf aktuelle Position zentrieren
                if (isNavigating && currentLocation != null) {
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
                }
            }
        },
        modifier = modifier
    )

    // Lifecycle-Management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView?.onStart()
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                Lifecycle.Event.ON_STOP -> mapView?.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Route zeichnen
    LaunchedEffect(route, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect

        map.getStyle { style ->
            // Vorhandene Route entfernen (einfacher Ansatz)
            try {
                style.removeLayer("route-layer")
                style.removeSource("route-source")
            } catch (e: Exception) {
                // Layer existiert nicht
            }

            if (route != null && route.geometry.isNotEmpty()) {
                // Route als GeoJSON hinzufügen
                val coordinates = route.geometry.map { pt ->
                    Point.fromLngLat(pt.lng, pt.lat)
                }

                val lineString = LineString.fromLngLats(coordinates)
                val feature = Feature.fromGeometry(lineString)

                val source = GeoJsonSource("route-source", feature)
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

                // Kamera auf Route zentrieren
                if (!isNavigating) {
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
                }
            }
        }
    }
}
