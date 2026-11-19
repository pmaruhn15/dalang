package de.dalang.nav

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dalang.nav.location.BrightnessProvider
import de.dalang.nav.location.HeadingProvider
import de.dalang.nav.location.LocationProvider
import de.dalang.nav.location.LocationSmoother
import de.dalang.nav.navigation.*
import de.dalang.nav.search.SearchRepository
import de.dalang.nav.search.SearchResult
import de.dalang.nav.settings.SettingsRepository
import de.dalang.nav.settings.ThemeMode
import de.dalang.nav.util.CrashLogger
import de.dalang.nav.util.GpsTrackLogger
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Coroutine Exception Handler für alle Coroutines
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        CrashLogger.logError("MainViewModel", "Coroutine exception", throwable)
    }

    private val locationProvider: LocationProvider? = try {
        LocationProvider(application)
    } catch (e: Exception) {
        CrashLogger.logError("MainViewModel", "LocationProvider init failed", e)
        null
    }

    private val headingProvider: HeadingProvider? = try {
        HeadingProvider(application)
    } catch (e: Exception) {
        CrashLogger.logError("MainViewModel", "HeadingProvider init failed", e)
        null
    }

    private val brightnessProvider: BrightnessProvider? = try {
        BrightnessProvider(application)
    } catch (e: Exception) {
        CrashLogger.logError("MainViewModel", "BrightnessProvider init failed", e)
        null
    }

    private val settingsRepo = SettingsRepository(application)
    private val searchRepository: SearchRepository = SearchRepository()
    private val routeRepository: RouteRepository = RouteRepository()
    private val locationSmoother = LocationSmoother()
    // Fortschritt entlang der aktuellen Route; neu angelegt, sobald sich die Route ändert.
    private var progress: RouteProgress? = null
    private var deadReckoningJob: Job? = null
    private var recalcJob: Job? = null
    private var progressLogTick = 0

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    // Angezeigte Position (geglättet + auf Route gematcht während Navigation)
    private val _displayLocation = MutableStateFlow<LatLng?>(null)
    val displayLocation: StateFlow<LatLng?> = _displayLocation.asStateFlow()

    // Distanz zur Route in Metern (für UI-Anzeige wenn off-route)
    private val _distanceToRoute = MutableStateFlow(0.0)
    val distanceToRoute: StateFlow<Double> = _distanceToRoute.asStateFlow()

    private val _heading = MutableStateFlow(0f)
    val heading: StateFlow<Float> = _heading.asStateFlow()

    private val _speed = MutableStateFlow(0f)  // m/s
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _bearing = MutableStateFlow(0f)  // GPS bearing (Fahrtrichtung)
    val bearing: StateFlow<Float> = _bearing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _navigationState = MutableStateFlow(NavigationState())
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _voiceEnabled = MutableStateFlow(true)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    // Fuer Fehlermeldungen an die UI
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Fuer kurze Info-Meldungen (auto-dismiss)
    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    // Für Recalculating Banner
    private val _isRecalculatingRoute = MutableStateFlow(false)
    val isRecalculatingRoute: StateFlow<Boolean> = _isRecalculatingRoute.asStateFlow()

    // Theme: null = System-Default verwenden (kein Override)
    private val _isDarkOverride = MutableStateFlow<Boolean?>(null)
    val isDarkOverride: StateFlow<Boolean?> = _isDarkOverride.asStateFlow()

    private val _themeMode = MutableStateFlow(settingsRepo.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // Aktuelle Helligkeit in Lux (für Debug)
    private val _currentLux = MutableStateFlow(0f)
    val currentLux: StateFlow<Float> = _currentLux.asStateFlow()

    private var brightnessJob: Job? = null

    // Ankunft am Hauspunkt (liegt oft 20-50 m neben der Straße) nur mit kurzer Restroute entlang der Straße
    private val ARRIVAL_RADIUS_DEST_M = 50.0
    private val ARRIVAL_MAX_REMAINING_M = 100.0
    // Cooldown um nicht zu oft neu zu berechnen
    private var lastRecalculationTime = 0L
    private val RECALCULATION_COOLDOWN_MS = 5000L

    // Fuer Map-Klick Navigation
    private val _clickedLocation = MutableStateFlow<LatLng?>(null)
    val clickedLocation: StateFlow<LatLng?> = _clickedLocation.asStateFlow()

    private val _clickedLocationAddress = MutableStateFlow<String?>(null)
    val clickedLocationAddress: StateFlow<String?> = _clickedLocationAddress.asStateFlow()

    private var navigationService: NavigationService? = null
    private var locationJob: Job? = null
    private var headingJob: Job? = null
    private var searchJob: Job? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            CrashLogger.log("MainViewModel: Service connected")
            try {
                val binder = service as? NavigationService.LocalBinder
                navigationService = binder?.getService()
                navigationService?.voiceEnabled = _voiceEnabled.value
                serviceBound = true
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "onServiceConnected failed", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            CrashLogger.log("MainViewModel: Service disconnected")
            navigationService = null
            serviceBound = false
        }
    }

    init {
        CrashLogger.log("MainViewModel initialized")
        // Theme-Mode beim Start setzen
        when (settingsRepo.themeMode) {
            ThemeMode.LIGHT -> _isDarkOverride.value = false
            ThemeMode.DARK -> _isDarkOverride.value = true
            ThemeMode.SYSTEM -> _isDarkOverride.value = null
            ThemeMode.AUTO -> {
                // Wird vom BrightnessSensor gesetzt, initial null (System)
                _isDarkOverride.value = null
            }
        }
        startBrightnessSensor()
    }

    /**
     * Startet den Helligkeitssensor für Auto-Dark-Mode.
     * Bei ThemeMode.AUTO wird dark mode nur bei <= 10 Lux aktiviert (sehr dunkel).
     *
     * Verwendet gleitenden Durchschnitt und Debounce um Flackern zu vermeiden.
     * - Erste Messung: Sofort anwenden
     * - Danach: 5-Sekunden gleitender Durchschnitt mit 3-Sekunden Debounce
     */
    private fun startBrightnessSensor() {
        val provider = brightnessProvider ?: return
        if (!provider.isAvailable) {
            CrashLogger.log("MainViewModel: No light sensor, using system theme")
            return
        }

        brightnessJob?.cancel()
        brightnessJob = viewModelScope.launch(exceptionHandler) {
            var currentlyDark: Boolean? = null  // null = noch nicht initialisiert
            var lastSwitchTime = 0L
            val minSwitchInterval = 10_000L  // Mindestens 10 Sekunden zwischen Wechseln
            val hysteresis = 15f             // 15 Lux Hysterese

            // Gleitender Durchschnitt der letzten Messungen
            val luxHistory = ArrayDeque<Float>(10)
            val maxHistorySize = 10  // ~5 Sekunden bei 2 Messungen/Sekunde

            provider.brightnessUpdates()
                .catch { e ->
                    CrashLogger.logError("MainViewModel", "Brightness updates error", e)
                }
                .collect { lux ->
                    _currentLux.value = lux
                    val threshold = settingsRepo.darkThresholdLux
                    val now = System.currentTimeMillis()

                    // Lux-Historie aktualisieren
                    luxHistory.addLast(lux)
                    if (luxHistory.size > maxHistorySize) {
                        luxHistory.removeFirst()
                    }

                    // Durchschnitt berechnen
                    val avgLux = luxHistory.average().toFloat()

                    // ERSTE Messung: Sofort anwenden (kein Debounce)
                    if (currentlyDark == null) {
                        currentlyDark = avgLux < threshold
                        if (_themeMode.value == ThemeMode.AUTO) {
                            _isDarkOverride.value = currentlyDark
                            CrashLogger.log("MainViewModel: Auto theme initial -> ${if (currentlyDark!!) "DARK" else "LIGHT"} (${avgLux.toInt()} lux avg)")
                        }
                        lastSwitchTime = now
                        return@collect
                    }

                    // Berechne ob Wechsel gewünscht ist (mit Hysterese)
                    val shouldBeDark = if (currentlyDark!!) {
                        // Aktuell dunkel -> erst bei threshold + hysteresis aufhellen
                        avgLux < threshold + hysteresis
                    } else {
                        // Aktuell hell -> erst bei threshold abdunkeln
                        avgLux < threshold
                    }

                    // Wechsel nur wenn:
                    // 1. Tatsächlich ein Wechsel nötig ist
                    // 2. Mindestens minSwitchInterval seit letztem Wechsel vergangen
                    if (shouldBeDark != currentlyDark && (now - lastSwitchTime) >= minSwitchInterval) {
                        currentlyDark = shouldBeDark
                        lastSwitchTime = now
                        if (_themeMode.value == ThemeMode.AUTO) {
                            _isDarkOverride.value = shouldBeDark
                            CrashLogger.log("MainViewModel: Auto theme -> ${if (shouldBeDark) "DARK" else "LIGHT"} (${avgLux.toInt()} lux avg)")
                        }
                    }
                }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        settingsRepo.themeMode = mode

        when (mode) {
            ThemeMode.AUTO -> {
                // Sofort basierend auf aktuellem Lux-Wert entscheiden
                val threshold = settingsRepo.darkThresholdLux
                _isDarkOverride.value = _currentLux.value < threshold
            }
            ThemeMode.LIGHT -> _isDarkOverride.value = false
            ThemeMode.DARK -> _isDarkOverride.value = true
            ThemeMode.SYSTEM -> _isDarkOverride.value = null  // System entscheidet
        }
        CrashLogger.log("MainViewModel: Theme mode set to $mode")
    }

    private fun bindNavigationService() {
        if (serviceBound) return
        try {
            CrashLogger.log("MainViewModel: Binding navigation service")
            val intent = Intent(getApplication(), NavigationService::class.java)
            getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "bindNavigationService failed", e)
        }
    }

    fun startLocationUpdates() {
        CrashLogger.log("MainViewModel: startLocationUpdates")

        val provider = locationProvider
        if (provider == null) {
            CrashLogger.logError("MainViewModel", "LocationProvider is null")
            return
        }

        if (!LocationProvider.hasLocationPermission(getApplication())) {
            CrashLogger.log("MainViewModel: No location permission")
            return
        }

        locationJob?.cancel()
        locationJob = viewModelScope.launch(exceptionHandler) {
            try {
                // Letzte bekannte Position
                provider.getLastKnownLocation()?.let { location ->
                    CrashLogger.log("MainViewModel: Got last known location")
                    _currentLocation.value = LatLng(location.latitude, location.longitude)
                }

                // Live-Updates (GPS only - Network Provider liefert nur Cell-Tower-Mist mit 9km Ungenauigkeit)
                provider.locationUpdates(1000L, useNetworkProvider = false)
                    .catch { e ->
                        CrashLogger.logError("MainViewModel", "Location updates error", e)
                    }
                    .collect { location ->
                        try {
                            val now = System.currentTimeMillis()
                            val accuracy = if (location.hasAccuracy()) location.accuracy else null
                            val good = accuracy == null || accuracy <= RouteProgress.GOOD_FIX_ACCURACY_M
                            val p = currentProgress()
                            // Während Tunnel-Schätzung: schlechte Fixes (wegdriftende Chip-Positionen) ignorieren
                            if (p?.estimating == true && !good) {
                                p.update(LatLng(location.latitude, location.longitude), null,
                                    if (location.hasSpeed()) location.speed else null, accuracy, now)
                                return@collect
                            }
                            if (p != null && good && p.lastGoodFixMs > 0 && now - p.lastGoodFixMs > RouteProgress.GPS_LOSS_MS) {
                                // GPS zurück nach Ausfall: Kalman-Zustand stammt von der Tunneleinfahrt —
                                // neu starten, sonst landet die erste Position zwischen Ein- und Ausfahrt.
                                locationSmoother.reset()
                            }

                            // Speed und Bearing extrahieren (vor dem Smoothing)
                            if (location.hasSpeed()) {
                                _speed.value = location.speed
                            }
                            if (location.hasBearing()) {
                                _bearing.value = location.bearing
                            }

                            // Position durch Kalman-Filter glätten
                            val smoothedLocation = locationSmoother.process(location)
                            if (smoothedLocation == null) {
                                // Position wurde als Ausreißer gefiltert
                                return@collect
                            }

                            _currentLocation.value = smoothedLocation

                            // GPS Track Logging für Debugging
                            GpsTrackLogger.logPosition(
                                location = smoothedLocation,
                                speed = location.speed,
                                bearing = location.bearing,
                                accuracy = location.accuracy
                            )

                            if (p != null) {
                                p.update(
                                    smoothedLocation,
                                    if (location.hasBearing()) location.bearing else null,
                                    if (location.hasSpeed()) location.speed else null,
                                    accuracy, now
                                )
                                // Anzeige auf die Route snappen, solange sie nah genug ist
                                _displayLocation.value = p.position?.takeIf { p.matched && p.lateralM <= 30 } ?: smoothedLocation
                                _distanceToRoute.value = p.lateralM
                                updateNavigation(smoothedLocation, p, estimated = false)
                            } else {
                                _displayLocation.value = smoothedLocation
                                _distanceToRoute.value = 0.0
                            }
                        } catch (e: Exception) {
                            CrashLogger.logError("MainViewModel", "Location collect failed", e)
                        }
                    }
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "startLocationUpdates failed", e)
            }
        }

        // Start heading updates
        startHeadingUpdates()
    }

    private fun startHeadingUpdates() {
        val provider = headingProvider ?: return

        headingJob?.cancel()
        headingJob = viewModelScope.launch(exceptionHandler) {
            try {
                provider.headingUpdates()
                    .catch { e ->
                        CrashLogger.logError("MainViewModel", "Heading updates error", e)
                    }
                    .collect { heading ->
                        _heading.value = heading
                    }
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "startHeadingUpdates failed", e)
            }
        }
    }

    fun stopLocationUpdates() {
        CrashLogger.log("MainViewModel: stopLocationUpdates")
        locationJob?.cancel()
        headingJob?.cancel()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()
        if (query.length >= 3) {
            searchJob = viewModelScope.launch(exceptionHandler) {
                try {
                    delay(300) // Debounce
                    _isSearching.value = true

                    // Aktuelle Position für standortbasierte Suche
                    val location = _currentLocation.value
                    _searchResults.value = searchRepository.search(
                        query = query,
                        currentLat = location?.lat,
                        currentLon = location?.lng
                    )

                    _isSearching.value = false
                } catch (e: Exception) {
                    CrashLogger.logError("MainViewModel", "search failed", e)
                    _isSearching.value = false
                    _searchResults.value = emptyList()
                }
            }
        } else {
            _searchResults.value = emptyList()
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearInfoMessage() {
        _infoMessage.value = null
    }

    fun selectDestination(result: SearchResult) {
        CrashLogger.log("MainViewModel: selectDestination: ${result.displayName}")
        val to = LatLng(result.lat, result.lon)
        val displayName = result.displayName.split(",").first()
        // Vorhandenen Retry abbrechen — neue Destination ersetzt die alte.
        pendingRetryJob?.cancel()
        pendingRetryJob = null

        viewModelScope.launch(exceptionHandler) {
            try {
                _navigationState.update { it.copy(isRecalculating = true) }

                val from = _currentLocation.value
                val route = if (from != null) routeRepository.getRoute(from, to) else null

                if (route != null) {
                    CrashLogger.log("MainViewModel: Route found with ${route.steps.size} steps")
                    _navigationState.update {
                        it.copy(
                            route = route,
                            destination = to,
                            destinationName = displayName,
                            isRecalculating = false,
                            totalDistanceRemaining = route.distance,
                            totalTimeRemaining = route.duration
                        )
                    }
                    if (!route.hasTrafficData) {
                        _infoMessage.value = "Route ohne Verkehrsdaten (OSRM)"
                    }
                } else {
                    // Destination trotzdem merken — entweder fehlt GPS (z.B. Tiefgarage)
                    // oder Netz war kurzzeitig weg. Retry-Coroutine erledigt es sobald beides da ist.
                    CrashLogger.logError("MainViewModel", "No route found — starting pending retry")
                    _errorMessage.value = if (from == null) {
                        "Warte auf GPS — Route wird automatisch geladen"
                    } else {
                        "Keine Verbindung — Route wird automatisch wiederholt"
                    }
                    _navigationState.update {
                        it.copy(
                            destination = to,
                            destinationName = displayName,
                            isRecalculating = false
                        )
                    }
                    startPendingRouteRetry(to, displayName)
                }

                clearSearch()
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "selectDestination failed", e)
                _errorMessage.value = "Fehler: ${e.message}"
                _navigationState.update {
                    it.copy(
                        destination = to,
                        destinationName = displayName,
                        isRecalculating = false
                    )
                }
                startPendingRouteRetry(to, displayName)
            }
        }
    }

    private var pendingRetryJob: Job? = null

    private fun startPendingRouteRetry(destination: LatLng, destinationName: String) {
        pendingRetryJob?.cancel()
        pendingRetryJob = viewModelScope.launch(exceptionHandler) {
            var attempt = 0
            while (coroutineContext.isActive && _navigationState.value.route == null) {
                // Erster Retry nach 5s, danach alle 30s (gibt GPS-Fix + Netz-Reconnect Zeit).
                delay(if (attempt == 0) 5_000L else 30_000L)
                attempt++

                if (_navigationState.value.destination != destination) {
                    // User hat ein anderes Ziel gewählt — Retry wegwerfen.
                    return@launch
                }
                val from = _currentLocation.value
                if (from == null) {
                    CrashLogger.log("MainViewModel: pending route retry #$attempt — still no GPS")
                    continue
                }
                CrashLogger.log("MainViewModel: pending route retry #$attempt")
                val route = try {
                    routeRepository.getRoute(from, destination)
                } catch (e: Exception) {
                    CrashLogger.logError("MainViewModel", "pending retry #$attempt failed", e)
                    null
                }
                if (route != null) {
                    _navigationState.update {
                        it.copy(
                            route = route,
                            destination = destination,
                            destinationName = destinationName,
                            isRecalculating = false,
                            totalDistanceRemaining = route.distance,
                            totalTimeRemaining = route.duration
                        )
                    }
                    _infoMessage.value = "Route geladen (nach $attempt. Versuch)"
                    CrashLogger.log("MainViewModel: pending route retry succeeded after $attempt attempts")
                    return@launch
                }
            }
        }
    }

    /** Dev-Einstieg per adb: Ziel setzen und Navigation starten, sobald die Route da ist. */
    fun startNavigationTo(lat: Double, lng: Double, name: String) {
        CrashLogger.log("MainViewModel: startNavigationTo $name ($lat,$lng)")
        selectDestination(SearchResult(displayName = name, lat = lat, lon = lng, type = "adb"))
        viewModelScope.launch(exceptionHandler) {
            _navigationState.first { it.route != null && it.destination == LatLng(lat, lng) }
            startNavigation()
        }
    }

    fun startNavigation() {
        CrashLogger.log("MainViewModel: startNavigation")
        try {
            val state = _navigationState.value
            if (state.route == null) {
                CrashLogger.logError("MainViewModel", "No route for navigation")
                return
            }

            // GPS Track Session starten
            GpsTrackLogger.startSession()

            // Lane-Info des ersten Steps loggen falls vorhanden
            state.route.steps.firstOrNull()?.laneInfo?.let { laneInfo ->
                if (laneInfo.lanes.isNotEmpty()) {
                    _currentLocation.value?.let { loc ->
                        GpsTrackLogger.logLaneInfo(loc, laneInfo, "Start")
                    }
                }
            }

            // Service binden falls noch nicht geschehen
            bindNavigationService()

            _navigationState.update {
                it.copy(
                    isNavigating = true,
                    currentStepIndex = 0,
                    hasArrived = false
                )
            }

            // Service starten
            try {
                val intent = Intent(getApplication(), NavigationService::class.java).apply {
                    action = NavigationService.ACTION_START
                }
                getApplication<Application>().startForegroundService(intent)
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "startForegroundService failed", e)
            }

            // Erste Ansage
            state.route.steps.firstOrNull()?.let { step ->
                speakInstruction(step)
            }

            // Proaktives Rerouting starten (prüft alle 3 Min ob schnellere Route verfügbar)
            startProactiveRerouting()
            startDeadReckoning()
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "startNavigation failed", e)
        }
    }

    fun stopNavigation() {
        CrashLogger.log("MainViewModel: stopNavigation")
        try {
            // Pending Route-Retry stoppen — User hat aufgegeben.
            pendingRetryJob?.cancel()
            pendingRetryJob = null

            // Proaktives Rerouting stoppen
            stopProactiveRerouting()

            // GPS Track Session stoppen
            GpsTrackLogger.stopSession()

            _navigationState.update {
                NavigationState()
            }

            deadReckoningJob?.cancel()
            deadReckoningJob = null
            progress = null
            announced.clear()

            locationSmoother.reset()
            _distanceToRoute.value = 0.0

            val intent = Intent(getApplication(), NavigationService::class.java).apply {
                action = NavigationService.ACTION_STOP
            }
            getApplication<Application>().startService(intent)
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "stopNavigation failed", e)
        }
    }

    /**
     * Exportiert den GPS-Track als GPX-Datei für Debugging.
     * Gibt den Dateipfad zurück oder null bei Fehler.
     */
    fun exportGpsTrack(): File? {
        return try {
            GpsTrackLogger.exportToGpx(getApplication())
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "exportGpsTrack failed", e)
            null
        }
    }

    /**
     * Gibt eine Debug-Zusammenfassung des GPS-Tracks zurück
     */
    fun getGpsTrackDebugSummary(): String {
        return GpsTrackLogger.getDebugSummary()
    }

    fun clearRoute() {
        CrashLogger.log("MainViewModel: clearRoute")
        _navigationState.update { NavigationState() }
    }

    /**
     * Fügt einen Zwischenstopp zur aktuellen Route hinzu.
     * Berechnet neue Route: Aktueller Standort -> Waypoint -> Ursprüngliches Ziel
     */
    fun addWaypoint(waypointLocation: LatLng, waypointName: String, waypointType: WaypointType) {
        CrashLogger.log("MainViewModel: addWaypoint '$waypointName' (${waypointType}) to ${waypointLocation.lat},${waypointLocation.lng}")
        viewModelScope.launch {
            try {
                val current = _currentLocation.value
                val state = _navigationState.value
                val destination = state.destination

                if (current == null || destination == null) {
                    CrashLogger.logError("MainViewModel", "Cannot add waypoint: missing current location or destination")
                    return@launch
                }

                _navigationState.update { it.copy(isRecalculating = true) }

                // Route vom aktuellen Standort zum Waypoint
                val routeToWaypoint = routeRepository.getRoute(current, waypointLocation)

                // Route vom Waypoint zum ursprünglichen Ziel — Abstand wegen OSRM-Rate-Limit (1 req/s)
                delay(1100)
                val routeToDestination = routeRepository.getRoute(waypointLocation, destination)

                if (routeToWaypoint != null && routeToDestination != null) {
                    // Kombinierte Route erstellen
                    val combinedGeometry = routeToWaypoint.geometry + routeToDestination.geometry
                    val combinedSteps = routeToWaypoint.steps + routeToDestination.steps
                    val combinedDistance = routeToWaypoint.distance + routeToDestination.distance
                    val combinedDuration = routeToWaypoint.duration + routeToDestination.duration

                    val combinedRoute = Route(
                        geometry = combinedGeometry,
                        distance = combinedDistance,
                        duration = combinedDuration,
                        steps = combinedSteps
                    )

                    CrashLogger.log("MainViewModel: Combined route with waypoint - ${combinedSteps.size} steps, ${combinedDistance}m")

                    _navigationState.update {
                        it.copy(
                            route = combinedRoute,
                            currentStepIndex = 0,
                            isRecalculating = false,
                            // Waypoint-Daten setzen
                            waypoint = waypointLocation,
                            waypointName = waypointName,
                            waypointType = waypointType,
                            distanceToWaypoint = routeToWaypoint.distance,
                            timeToWaypoint = routeToWaypoint.duration,
                            // Gesamtdistanz/-zeit zum Hauptziel aktualisieren
                            totalDistanceRemaining = combinedDistance,
                            totalTimeRemaining = combinedDuration
                        )
                    }

                    // Keine Sofort-Ansage nach Neuberechnung (vorher "Los geht's" bei jedem
                    // Verfahren, Fahrer-Report). Die Distanz-Voransagen greifen beim nächsten
                    // GPS-Update, falls das erste Manöver schon in Reichweite liegt.
                } else {
                    CrashLogger.logError("MainViewModel", "Failed to calculate route with waypoint")
                    _navigationState.update { it.copy(isRecalculating = false) }
                }
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "addWaypoint failed", e)
                _navigationState.update { it.copy(isRecalculating = false) }
            }
        }
    }

    /**
     * Entfernt das aktuelle Zwischenziel und berechnet Route direkt zum Hauptziel
     */
    fun clearWaypoint() {
        val state = _navigationState.value
        val destination = state.destination ?: return
        val current = _currentLocation.value ?: return

        CrashLogger.log("MainViewModel: clearWaypoint")

        viewModelScope.launch {
            try {
                _navigationState.update { it.copy(isRecalculating = true) }

                val route = routeRepository.getRoute(current, destination)
                if (route != null) {
                    _navigationState.update {
                        it.copy(
                            route = route,
                            currentStepIndex = 0,
                            isRecalculating = false,
                            waypoint = null,
                            waypointName = null,
                            waypointType = null,
                            distanceToWaypoint = 0.0,
                            timeToWaypoint = 0.0,
                            totalDistanceRemaining = route.distance,
                            totalTimeRemaining = route.duration
                        )
                    }
                } else {
                    _navigationState.update { it.copy(isRecalculating = false) }
                }
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "clearWaypoint failed", e)
                _navigationState.update { it.copy(isRecalculating = false) }
            }
        }
    }

    /** Progress zur aktuellen Route; bei Routenwechsel (Neuberechnung, schnellere Route, Alternative, Waypoint) neu. */
    private fun currentProgress(): RouteProgress? {
        val state = _navigationState.value
        val route = state.route ?: return null
        if (!state.isNavigating) return null
        progress?.takeIf { it.route === route }?.let { return it }
        // Voransagen der neuen Route, deren Manöver identisch sind, nicht wiederholen
        val maneuvers = route.steps.map { it.maneuver.location }.toSet()
        announced.retainAll { it.first in maneuvers }
        CrashLogger.log("MainViewModel: neue Route im RouteProgress (${route.steps.size} Steps, ${route.geometry.size} Punkte)")
        return RouteProgress(route) { CrashLogger.log("RouteProgress: $it") }.also { progress = it }
    }

    /**
     * 1-Hz-Takt während Navigation: bei GPS-Ausfall (Tunnel) schiebt RouteProgress die Position
     * entlang der Route weiter; Anzeige, Step-Advance und Voransagen laufen darüber weiter.
     */
    private fun startDeadReckoning() {
        deadReckoningJob?.cancel()
        deadReckoningJob = viewModelScope.launch(exceptionHandler) {
            while (isActive) {
                delay(1000)
                try {
                    if (locationJob?.isActive != true) continue
                    val p = currentProgress() ?: continue
                    if (!p.deadReckon(System.currentTimeMillis())) continue
                    val pos = p.position ?: continue
                    _displayLocation.value = pos
                    _distanceToRoute.value = 0.0
                    _bearing.value = p.bearingDeg
                    _speed.value = p.drSpeedMs.toFloat()
                    updateNavigation(pos, p, estimated = true)
                } catch (e: Exception) {
                    CrashLogger.logError("MainViewModel", "deadReckoning tick failed", e)
                }
            }
        }
    }

    /** Für den Debug-Report: was RouteProgress gerade denkt. */
    fun progressDebug(): String = progress?.debugLine() ?: "RouteProgress: -"

    /**
     * @param estimated Position stammt aus der Tunnel-Schätzung: kein Rerouting, keine Ankunft.
     */
    private fun updateNavigation(location: LatLng, p: RouteProgress, estimated: Boolean) {
        try {
            val state = _navigationState.value
            val route = state.route ?: return
            if (p.route !== route) return

            if (!estimated && p.offRoute) {
                val now = System.currentTimeMillis()
                if (now - lastRecalculationTime > RECALCULATION_COOLDOWN_MS) {
                    CrashLogger.log("MainViewModel: Off-route (${p.debugLine()}), recalculating...")
                    lastRecalculationTime = now
                    recalculateRoute(location)
                }
                return
            }

            // Ankunft nur entlang der Route — Luftlinie zum Routenende/Hauspunkt matchte auch
            // die Gegenfahrbahn bzw. lag vor Wende + Kreisverkehr (Fahrer-Reports 18.11.).
            if (!estimated && checkArrival(location, state, p)) return

            val stepIndex = p.stepIndex
            if (stepIndex != state.currentStepIndex) {
                route.steps.getOrNull(stepIndex)?.let { passed ->
                    passed.laneInfo?.takeIf { it.lanes.isNotEmpty() }?.let {
                        GpsTrackLogger.logLaneInfo(location, it, passed.instruction.ifBlank { "Step $stepIndex" })
                    }
                }
                route.steps.getOrNull(stepIndex + 1)?.let { updateServiceNotification(it, p.distanceToStep(stepIndex + 1)) }
            }

            // Zwischenziel: inneres "arrive" der kombinierten Route entlang der Straße; ohne ein solches Luftlinie
            var waypoint = state.waypoint
            val waypointStep = route.steps.indices.firstOrNull { isInnerStop(route.steps, it) && route.steps[it].maneuver.type == "arrive" }
            val waypointReached = waypointStep?.let { p.matched && p.distanceToStep(it) <= RouteProgress.ARRIVAL_REMAINING_M }
                ?: (waypoint != null && location.distanceTo(waypoint) < 50)
            if (!estimated && waypoint != null && waypointReached) {
                CrashLogger.log("MainViewModel: Arrived at waypoint ${state.waypointName}")
                navigationService?.speakNow("Zwischenziel erreicht: ${state.waypointName}")
                waypoint = null
            }
            val waypointDistance = waypoint?.let { location.distanceTo(it) } ?: 0.0
            val waypointSpeed = _speed.value.coerceAtLeast(10f)  // grobe Schätzung, mind. 36 km/h

            val newState = state.copy(
                currentStepIndex = stepIndex,
                distanceToNextStep = p.distanceToStep(stepIndex + 1),
                totalDistanceRemaining = p.remainingM,
                totalTimeRemaining = p.remainingTimeS(),
                waypoint = waypoint,
                waypointName = if (waypoint == null) null else state.waypointName,
                waypointType = if (waypoint == null) null else state.waypointType,
                distanceToWaypoint = waypointDistance,
                timeToWaypoint = waypointDistance / waypointSpeed
            )
            _navigationState.update { newState }

            announceNextManeuver(newState, p)

            if (++progressLogTick % 5 == 0) CrashLogger.log(p.debugLine())
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "updateNavigation failed", e)
        }
    }

    /**
     * Distanz-Voransagen für das nächste relevante Manöver, gemessen entlang der Route.
     * Kein Distanz-Fenster (bei 120 km/h springt GPS 33 m/s weit drüber), sondern
     * "unterschritten und noch nicht angesagt". Straßentyp = Step, auf dem man zufährt.
     */
    private fun announceNextManeuver(state: NavigationState, p: RouteProgress) {
        val steps = state.route?.steps ?: return
        val r = (state.currentStepIndex + 1 until steps.size)
            .firstOrNull { steps[it].isRelevantManeuver() && !isInnerStop(steps, it) } ?: return
        val step = steps[r]
        val d = p.distanceToStep(r)
        val thresholds = announceDistancesM(steps.getOrNull(r - 1))
        val key = step.maneuver.location
        val consume = consumeAnnouncements(d, thresholds, thresholds.filter { (key to it) in announced }.toSet())
        if (consume.isEmpty()) return
        consume.forEach { announced.add(key to it) }
        CrashLogger.log("MainViewModel: Voransage Step $r (${step.maneuver.type}) bei ${d.toInt()}m entlang Route")
        speakDistance(d, step)
    }

    /** arrive/depart mitten in der Route = Zwischenziel-Naht der kombinierten Waypoint-Route. */
    private fun isInnerStop(steps: List<RouteStep>, i: Int): Boolean {
        val type = steps[i].maneuver.type
        return (type == "arrive" && i < steps.size - 1) || (type == "depart" && i > 0)
    }

    /**
     * Ziel erreicht: Routenende entlang der Straße (< 30 m Restroute), oder Hauspunkt (liegt oft
     * 20-50 m neben der Straße) nah UND Restroute entlang der Straße kurz.
     */
    private fun checkArrival(location: LatLng, state: NavigationState, p: RouteProgress): Boolean {
        val destination = state.destination ?: return false
        if (state.waypoint != null) return false  // erst Zwischenziel
        val toDest = location.distanceTo(destination)
        val nearDestByRoad = p.matched && toDest < ARRIVAL_RADIUS_DEST_M && p.remainingM < ARRIVAL_MAX_REMAINING_M
        if (!nearDestByRoad && !p.atRouteEnd) return false
        CrashLogger.log("MainViewModel: Arrived (toDest=${toDest.toInt()}m) — ${p.debugLine()}")
        _navigationState.update { it.copy(hasArrived = true, isNavigating = false) }
        navigationService?.speakNow("Ziel erreicht")
        stopNavigation()
        return true
    }

    /**
     * Neuberechnung der Route vom aktuellen Standort zum Ziel
     */
    private fun recalculateRoute(currentLocation: LatLng) {
        val destination = _navigationState.value.destination ?: return
        // getRoute dauert mit HERE + OSM-Matching oft > Cooldown: parallele Recalcs = OSRM 429,
        // HERE-Kontingent, und die zuletzt fertige (evtl. ältere Position) gewinnt.
        if (recalcJob?.isActive == true) {
            CrashLogger.log("MainViewModel: Recalc läuft schon — übersprungen")
            return
        }

        recalcJob = viewModelScope.launch(exceptionHandler) {
            try {
                _isRecalculatingRoute.value = true
                CrashLogger.log("MainViewModel: Recalculating route to destination...")

                val route = routeRepository.getRoute(currentLocation, destination)
                if (route != null && _navigationState.value.isNavigating) {
                    CrashLogger.log("MainViewModel: New route calculated with ${route.steps.size} steps")
                    _navigationState.update {
                        it.copy(
                            route = route,
                            currentStepIndex = 0,
                            totalDistanceRemaining = route.distance,
                            totalTimeRemaining = route.duration
                        )
                    }

                    // Keine Sofort-Ansage nach Neuberechnung (vorher "Los geht's" bei jedem
                    // Verfahren, Fahrer-Report). Die Distanz-Voransagen greifen beim nächsten
                    // GPS-Update, falls das erste Manöver schon in Reichweite liegt.
                } else {
                    CrashLogger.logError("MainViewModel", "Route recalculation failed")
                }
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "recalculateRoute failed", e)
            } finally {
                _isRecalculatingRoute.value = false
            }
        }
    }

    /**
     * Wählt eine alternative Route aus und macht sie zur Hauptroute.
     * Die bisherige Hauptroute wird zur Alternative — synchron, ohne API-Call.
     */
    fun selectAlternativeRoute(alternativeIndex: Int) {
        val currentRoute = _navigationState.value.route ?: return
        val alternatives = currentRoute.alternatives

        if (alternativeIndex !in alternatives.indices) {
            CrashLogger.log("MainViewModel: Invalid alternative index $alternativeIndex")
            return
        }

        val diffMin = (alternatives[alternativeIndex].durationDifference / 60).toInt()
        CrashLogger.log("MainViewModel: Selecting alternative route $alternativeIndex ($diffMin min diff)")

        val newRoute = routeRepository.promoteAlternativeToMain(currentRoute, alternativeIndex)
        if (newRoute == null) {
            CrashLogger.log("MainViewModel: promote returned null")
            return
        }

        _navigationState.update {
            it.copy(
                route = newRoute,
                currentStepIndex = 0,
                totalDistanceRemaining = newRoute.distance,
                totalTimeRemaining = newRoute.duration
            )
        }
        CrashLogger.log("MainViewModel: Route promoted; ${newRoute.alternatives.size} alternatives remain")
    }

    // Proaktives Rerouting - prüft periodisch ob schnellere Route verfügbar
    private var proactiveReroutingJob: Job? = null
    private var lastFasterRouteCheck = 0L
    private val FASTER_ROUTE_CHECK_INTERVAL_MS = 3 * 60 * 1000L  // Alle 3 Minuten
    private val MIN_TIME_SAVINGS_SECONDS = 120  // Mindestens 2 Min schneller

    private fun startProactiveRerouting() {
        proactiveReroutingJob?.cancel()
        proactiveReroutingJob = viewModelScope.launch(exceptionHandler) {
            while (coroutineContext.isActive) {
                delay(FASTER_ROUTE_CHECK_INTERVAL_MS)
                checkForFasterRoute()
            }
        }
    }

    private fun stopProactiveRerouting() {
        proactiveReroutingJob?.cancel()
        proactiveReroutingJob = null
    }

    private suspend fun checkForFasterRoute() {
        val state = _navigationState.value
        if (!state.isNavigating || state.route == null) return
        // Im Tunnel ist _currentLocation die Einfahrt — eine Route von dort begänne hinter uns.
        if (progress?.estimating == true) return
        if (recalcJob?.isActive == true) return

        val currentLocation = _currentLocation.value ?: return
        val destination = state.destination ?: return

        try {
            CrashLogger.log("MainViewModel: Checking for faster route...")
            val newRoute = routeRepository.getRoute(currentLocation, destination)

            if (newRoute != null) {
                val currentRemaining = state.totalTimeRemaining
                val newDuration = newRoute.duration
                val timeSaved = currentRemaining - newDuration

                if (timeSaved >= MIN_TIME_SAVINGS_SECONDS) {
                    val savedMinutes = (timeSaved / 60).toInt()
                    CrashLogger.log("MainViewModel: Faster route found! Saves $savedMinutes min")

                    // Route automatisch übernehmen und User informieren
                    _navigationState.update {
                        it.copy(
                            route = newRoute,
                            currentStepIndex = 0,
                            totalDistanceRemaining = newRoute.distance,
                            totalTimeRemaining = newRoute.duration
                        )
                    }

                    // User über schnellere Route informieren
                    navigationService?.speakNow("Schnellere Route gefunden. $savedMinutes Minuten gespart.")
                } else {
                    CrashLogger.log("MainViewModel: No significantly faster route (would save ${(timeSaved/60).toInt()} min)")
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "checkForFasterRoute failed", e)
        }
    }

    private fun speakInstruction(step: RouteStep) {
        try {
            // Nur relevante Manöver ansagen (keine "Geradeaus fahren" etc.)
            if (!step.isRelevantManeuver()) {
                CrashLogger.log("MainViewModel: Skipping non-relevant instruction: ${step.maneuver.type}")
                return
            }
            val instruction = step.toGermanInstruction()
            navigationService?.speak(instruction)
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "speakInstruction failed", e)
        }
    }

    /** Bereits gesprochene Voransagen: (Manöver-Ort, Schwelle). */
    private val announced = mutableSetOf<Pair<LatLng, Int>>()

    private fun speakDistance(distanceM: Double, step: RouteStep) {
        try {
            if (!step.isRelevantManeuver()) return
            val dist = if (distanceM >= 950) {
                val km = Math.round(distanceM / 500.0) * 0.5  // auf 0,5 km
                if (km % 1.0 == 0.0) "${km.toInt()} Kilometern" else "${km.toString().replace('.', ',')} Kilometern"
            } else {
                "${(Math.round(distanceM / 50.0) * 50).coerceAtLeast(50)} Metern"
            }
            navigationService?.speak("In $dist: ${step.toGermanInstruction()}")
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "speakDistance failed", e)
        }
    }

    private fun updateServiceNotification(step: RouteStep, distance: Double) {
        try {
            navigationService?.updateNotification(
                step.toGermanInstruction(),
                distance.formatDistance()
            )
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "updateServiceNotification failed", e)
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        _voiceEnabled.value = enabled
        navigationService?.voiceEnabled = enabled
    }

    // Map-Klick Handling
    fun onMapClicked(location: LatLng) {
        CrashLogger.log("MainViewModel: Map clicked at ${location.lat}, ${location.lng}")
        _clickedLocation.value = location
        _clickedLocationAddress.value = "Lade Adresse..."

        // Reverse Geocoding im Hintergrund
        viewModelScope.launch(exceptionHandler) {
            try {
                val address = searchRepository.reverseGeocode(location.lat, location.lng)
                _clickedLocationAddress.value = address ?: "Unbekannte Adresse"
            } catch (e: Exception) {
                _clickedLocationAddress.value = "Adresse nicht gefunden"
            }
        }
    }

    fun dismissMapClick() {
        _clickedLocation.value = null
        _clickedLocationAddress.value = null
    }

    fun navigateToClickedLocation() {
        val location = _clickedLocation.value ?: return
        CrashLogger.log("MainViewModel: Navigating to clicked location")

        viewModelScope.launch(exceptionHandler) {
            try {
                val from = _currentLocation.value
                if (from == null) {
                    CrashLogger.logError("MainViewModel", "No current location for route")
                    return@launch
                }

                _navigationState.update { it.copy(isRecalculating = true) }
                _clickedLocation.value = null

                // Reverse Geocode um Namen zu bekommen
                val name = searchRepository.reverseGeocode(location.lat, location.lng)
                    ?.split(",")?.firstOrNull()?.trim()
                    ?: "Ziel"

                val route = routeRepository.getRoute(from, location)
                if (route != null) {
                    CrashLogger.log("MainViewModel: Route to clicked location found")
                    _navigationState.update {
                        it.copy(
                            route = route,
                            destination = location,
                            destinationName = name,
                            isRecalculating = false,
                            totalDistanceRemaining = route.distance,
                            totalTimeRemaining = route.duration
                        )
                    }
                    // Info wenn ohne Verkehrsdaten (OSRM Fallback)
                    if (!route.hasTrafficData) {
                        _infoMessage.value = "Route ohne Verkehrsdaten (OSRM)"
                    }
                } else {
                    CrashLogger.logError("MainViewModel", "No route to clicked location")
                    _navigationState.update { it.copy(isRecalculating = false) }
                }
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "navigateToClickedLocation failed", e)
                _navigationState.update { it.copy(isRecalculating = false) }
            }
        }
    }

    override fun onCleared() {
        CrashLogger.log("MainViewModel: onCleared")
        super.onCleared()
        try {
            stopLocationUpdates()
            if (serviceBound) {
                try {
                    getApplication<Application>().unbindService(serviceConnection)
                    serviceBound = false
                } catch (e: Exception) {
                    CrashLogger.logError("MainViewModel", "unbindService failed", e)
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "onCleared failed", e)
        }
    }
}
