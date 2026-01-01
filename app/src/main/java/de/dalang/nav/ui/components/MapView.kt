package de.dalang.nav.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.dalang.nav.R
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.navigation.Poi
import de.dalang.nav.navigation.PoiType
import de.dalang.nav.navigation.Route
import de.dalang.nav.settings.FuelType
import de.dalang.nav.util.CrashLogger
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

@Composable
fun MapViewComposable(
    currentLocation: LatLng?,
    heading: Float = 0f,
    speed: Float = 0f,  // m/s
    bearing: Float = 0f,  // GPS bearing
    distanceToNextManeuver: Double = Double.MAX_VALUE,
    destination: LatLng?,
    route: Route?,
    isNavigating: Boolean,
    pois: List<Poi> = emptyList(),
    selectedPoiType: PoiType? = null,
    preferredFuelType: FuelType = FuelType.DIESEL,
    vehicleRangeKm: Int = 0,
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
    var styleVersion by remember { mutableIntStateOf(0) }

    // Auto Light/Dark basierend auf System-Theme
    val styleUrl = if (isDarkTheme) STYLE_DARK else STYLE_LIGHT

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
                                        isAttributionEnabled = false
                                        isLogoEnabled = false
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
                            // Dynamischer Zoom basierend auf Geschwindigkeit und Distanz zum nächsten Manöver
                            val dynamicZoom = calculateDynamicZoom(speed, distanceToNextManeuver)

                            map.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(pos)
                                        .zoom(dynamicZoom)
                                        .bearing(bearing.toDouble())  // Karte in Fahrtrichtung
                                        .tilt(60.0)  // Stärkerer Tilt für bessere 3D-Ansicht
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

    // Style-Wechsel bei Änderung (Auto-Switch oder Settings)
    LaunchedEffect(styleUrl, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect

        try {
            CrashLogger.log("MapView: Switching to style $styleUrl")
            map.setStyle(styleUrl) { _ ->
                CrashLogger.log("MapView: Style switched successfully")
                styleVersion++  // Trigger redraw of markers
            }
        } catch (e: Exception) {
            CrashLogger.logError("MapView", "Style switch failed", e)
        }
    }

    // Standort-Marker zeichnen (Pfeil mit Kompass-Rotation)
    LaunchedEffect(currentLocation, heading, isMapReady, styleVersion) {
        if (!isMapReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val location = currentLocation ?: return@LaunchedEffect

        try {
            map.getStyle { style ->
                try {
                    // Vorherigen Marker entfernen
                    try {
                        style.removeLayer("location-layer")
                        style.removeSource("location-source")
                    } catch (e: Exception) {
                        // Layer existiert nicht
                    }

                    // Icon zum Style hinzufügen (falls noch nicht vorhanden)
                    if (style.getImage("position-arrow") == null) {
                        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_position_arrow)
                        if (drawable != null) {
                            val bitmap = Bitmap.createBitmap(
                                drawable.intrinsicWidth,
                                drawable.intrinsicHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            val canvas = Canvas(bitmap)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                            style.addImage("position-arrow", bitmap)
                        }
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

                    // Pfeil als Symbol mit Kompass-Rotation
                    val locationLayer = SymbolLayer("location-layer", "location-source").apply {
                        setProperties(
                            PropertyFactory.iconImage("position-arrow"),
                            PropertyFactory.iconSize(0.8f),
                            PropertyFactory.iconRotate(heading),
                            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true)
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
    LaunchedEffect(route, isMapReady, styleVersion) {
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
                                PropertyFactory.lineColor(Color.WHITE),
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
                                var validPoints = 0
                                route.geometry.forEach { point ->
                                    // Nur gültige Koordinaten hinzufügen
                                    if (point.lat >= -90 && point.lat <= 90 &&
                                        point.lng >= -180 && point.lng <= 180) {
                                        bounds.include(
                                            org.maplibre.android.geometry.LatLng(point.lat, point.lng)
                                        )
                                        validPoints++
                                    }
                                }
                                if (validPoints >= 2) {
                                    map.animateCamera(
                                        CameraUpdateFactory.newLatLngBounds(
                                            bounds.build(),
                                            100
                                        ),
                                        1000
                                    )
                                }
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
    LaunchedEffect(destination, isMapReady, styleVersion) {
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
                        // Icon zum Style hinzufügen (falls noch nicht vorhanden)
                        if (style.getImage("destination-marker") == null) {
                            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_destination_marker)
                            if (drawable != null) {
                                val bitmap = Bitmap.createBitmap(
                                    drawable.intrinsicWidth,
                                    drawable.intrinsicHeight,
                                    Bitmap.Config.ARGB_8888
                                )
                                val canvas = Canvas(bitmap)
                                drawable.setBounds(0, 0, canvas.width, canvas.height)
                                drawable.draw(canvas)
                                style.addImage("destination-marker", bitmap)
                            }
                        }

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

                        val destLayer = SymbolLayer("destination-layer", "destination-source").apply {
                            setProperties(
                                PropertyFactory.iconImage("destination-marker"),
                                PropertyFactory.iconSize(1.0f),
                                PropertyFactory.iconAllowOverlap(true),
                                PropertyFactory.iconIgnorePlacement(true),
                                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM)
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

    // POI Marker zeichnen (mit Preisen für Tankstellen)
    LaunchedEffect(pois, selectedPoiType, preferredFuelType, vehicleRangeKm, isMapReady, styleVersion) {
        if (!isMapReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect

        try {
            map.getStyle { style ->
                try {
                    // Vorherige POI-Marker entfernen
                    try {
                        style.removeLayer("poi-layer")
                        style.removeLayer("poi-cheapest-layer")
                        style.removeSource("poi-source")
                        style.removeSource("poi-cheapest-source")
                    } catch (e: Exception) {
                        // Layer existiert nicht
                    }

                    if (pois.isNotEmpty() && selectedPoiType != null) {
                        // Filter POIs nach Reichweite (wenn gesetzt)
                        val filteredPois = if (vehicleRangeKm > 0) {
                            pois.filter { it.distanceKm <= vehicleRangeKm }
                        } else {
                            pois
                        }

                        if (filteredPois.isEmpty()) {
                            CrashLogger.log("MapView: No POIs within range")
                            return@getStyle
                        }

                        // Günstigste Tankstelle finden (nur für Tankstellen)
                        val cheapestPoi = if (selectedPoiType == PoiType.GAS_STATION) {
                            filteredPois.filter { poi ->
                                poi.fuelPrices?.getPriceForType(preferredFuelType) != null
                            }.minByOrNull { poi ->
                                poi.fuelPrices?.getPriceForType(preferredFuelType) ?: Double.MAX_VALUE
                            }
                        } else null

                        // Icon zum Style hinzufügen
                        val iconName = when (selectedPoiType) {
                            PoiType.GAS_STATION -> "poi-gas-station"
                            PoiType.MCDONALDS -> "poi-mcdonalds"
                        }
                        val drawableRes = when (selectedPoiType) {
                            PoiType.GAS_STATION -> R.drawable.ic_gas_station
                            PoiType.MCDONALDS -> R.drawable.ic_mcdonalds
                        }

                        if (style.getImage(iconName) == null) {
                            val drawable = ContextCompat.getDrawable(context, drawableRes)
                            if (drawable != null) {
                                val bitmap = Bitmap.createBitmap(
                                    drawable.intrinsicWidth,
                                    drawable.intrinsicHeight,
                                    Bitmap.Config.ARGB_8888
                                )
                                val canvas = Canvas(bitmap)
                                drawable.setBounds(0, 0, canvas.width, canvas.height)
                                drawable.draw(canvas)
                                style.addImage(iconName, bitmap)
                            }
                        }

                        // Preis-Label Icons für Tankstellen erstellen
                        if (selectedPoiType == PoiType.GAS_STATION) {
                            filteredPois.forEachIndexed { index, poi ->
                                val price = poi.fuelPrices?.getPriceForType(preferredFuelType)
                                if (price != null) {
                                    val isCheapest = poi == cheapestPoi
                                    val priceIconName = "price-label-$index"
                                    val priceBitmap = createPriceLabelBitmap(price, isCheapest)
                                    style.addImage(priceIconName, priceBitmap)
                                }
                            }
                        }

                        // Normale POIs (nicht die günstigste)
                        val normalPois = if (cheapestPoi != null) {
                            filteredPois.filter { it != cheapestPoi }
                        } else {
                            filteredPois
                        }

                        if (normalPois.isNotEmpty()) {
                            // POIs als FeatureCollection mit Preis-Infos
                            val features = normalPois.mapIndexed { index, poi ->
                                val priceIconName = if (selectedPoiType == PoiType.GAS_STATION &&
                                    poi.fuelPrices?.getPriceForType(preferredFuelType) != null) {
                                    "price-label-${filteredPois.indexOf(poi)}"
                                } else {
                                    iconName
                                }
                                """{"type":"Feature","id":$index,"geometry":{"type":"Point","coordinates":[${poi.lng},${poi.lat}]},"properties":{"name":"${poi.name.replace("\"", "\\\"")}", "icon":"$priceIconName"}}"""
                            }.joinToString(",")
                            val geoJson = """{"type":"FeatureCollection","features":[$features]}"""

                            val source = GeoJsonSource("poi-source", geoJson)
                            style.addSource(source)

                            val poiLayer = SymbolLayer("poi-layer", "poi-source").apply {
                                setProperties(
                                    PropertyFactory.iconImage("{icon}"),
                                    PropertyFactory.iconSize(1.0f),
                                    PropertyFactory.iconAllowOverlap(true),
                                    PropertyFactory.iconIgnorePlacement(true),
                                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)
                                )
                            }
                            style.addLayer(poiLayer)
                        }

                        // Günstigste Tankstelle separat (obendrauf)
                        if (cheapestPoi != null) {
                            val cheapestIndex = filteredPois.indexOf(cheapestPoi)
                            val priceIconName = "price-label-$cheapestIndex"
                            val geoJson = """{"type":"Feature","geometry":{"type":"Point","coordinates":[${cheapestPoi.lng},${cheapestPoi.lat}]},"properties":{"icon":"$priceIconName"}}"""

                            val source = GeoJsonSource("poi-cheapest-source", geoJson)
                            style.addSource(source)

                            val cheapestLayer = SymbolLayer("poi-cheapest-layer", "poi-cheapest-source").apply {
                                setProperties(
                                    PropertyFactory.iconImage("{icon}"),
                                    PropertyFactory.iconSize(1.2f),  // Etwas größer
                                    PropertyFactory.iconAllowOverlap(true),
                                    PropertyFactory.iconIgnorePlacement(true),
                                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)
                                )
                            }
                            style.addLayer(cheapestLayer)
                        }

                        CrashLogger.log("MapView: Drew ${filteredPois.size} POI markers, cheapest: ${cheapestPoi?.name}")

                        // Kamera auf Route + POIs zoomen (wenn nicht navigierend)
                        if (!isNavigating && route != null && route.geometry.isNotEmpty()) {
                            try {
                                val bounds = LatLngBounds.Builder()
                                var validPoints = 0

                                // Route-Punkte hinzufügen
                                route.geometry.forEach { point ->
                                    if (point.lat >= -90 && point.lat <= 90 &&
                                        point.lng >= -180 && point.lng <= 180) {
                                        bounds.include(
                                            org.maplibre.android.geometry.LatLng(point.lat, point.lng)
                                        )
                                        validPoints++
                                    }
                                }

                                // POI-Punkte hinzufügen
                                filteredPois.forEach { poi ->
                                    if (poi.lat >= -90 && poi.lat <= 90 &&
                                        poi.lng >= -180 && poi.lng <= 180) {
                                        bounds.include(
                                            org.maplibre.android.geometry.LatLng(poi.lat, poi.lng)
                                        )
                                        validPoints++
                                    }
                                }

                                if (validPoints >= 2) {
                                    map.animateCamera(
                                        CameraUpdateFactory.newLatLngBounds(
                                            bounds.build(),
                                            80  // Padding
                                        ),
                                        1000
                                    )
                                }
                            } catch (e: Exception) {
                                CrashLogger.logError("MapView", "POI camera bounds animation failed", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    CrashLogger.logError("MapView", "POI markers failed", e)
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("MapView", "getStyle failed for POIs", e)
        }
    }
}

/**
 * Erstellt ein Bitmap mit Preis-Label für die Karte
 */
private fun createPriceLabelBitmap(price: Double, isCheapest: Boolean): Bitmap {
    val priceText = String.format("%.2f€", price)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }

    val textBounds = Rect()
    paint.getTextBounds(priceText, 0, priceText.length, textBounds)

    val padding = 16
    val width = textBounds.width() + padding * 2
    val height = textBounds.height() + padding * 2

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Hintergrund (grün für günstigste, grau für andere)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isCheapest) Color.parseColor("#4CAF50") else Color.parseColor("#424242")
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)

    // Rand
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawRoundRect(1f, 1f, width.toFloat() - 1f, height.toFloat() - 1f, 8f, 8f, borderPaint)

    // Text
    canvas.drawText(
        priceText,
        padding.toFloat(),
        height.toFloat() - padding,
        paint
    )

    return bitmap
}

/**
 * Berechnet dynamischen Zoom basierend auf Geschwindigkeit und Distanz zum nächsten Manöver
 * - Bei niedriger Geschwindigkeit (Stadt): Zoom 17-18
 * - Bei hoher Geschwindigkeit (Autobahn): Zoom 14-15
 * - Näher am Manöver: mehr reinzoomen
 */
private fun calculateDynamicZoom(speedMs: Float, distanceToManeuver: Double): Double {
    // Geschwindigkeitsbasierter Zoom (m/s -> km/h: *3.6)
    val speedKmh = speedMs * 3.6f
    val speedZoom = when {
        speedKmh < 20 -> 18.0    // Langsam/Stehend: sehr nah
        speedKmh < 50 -> 17.0    // Stadt: nah
        speedKmh < 80 -> 16.0    // Landstraße: mittel
        speedKmh < 120 -> 15.0   // Autobahn: weiter weg
        else -> 14.0             // Schnell: weit weg
    }

    // Distanzbasierter Zoom-Bonus (näher am Manöver = mehr reinzoomen)
    val distanceBonus = when {
        distanceToManeuver < 100 -> 1.5   // Unter 100m: deutlich näher
        distanceToManeuver < 300 -> 1.0   // Unter 300m: näher
        distanceToManeuver < 500 -> 0.5   // Unter 500m: leicht näher
        else -> 0.0
    }

    // Kombination: Basis-Zoom + Distanz-Bonus, max 18.5
    return (speedZoom + distanceBonus).coerceIn(14.0, 18.5)
}
