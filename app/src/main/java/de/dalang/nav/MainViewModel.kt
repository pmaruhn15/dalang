package de.dalang.nav

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dalang.nav.location.HeadingProvider
import de.dalang.nav.location.LocationProvider
import de.dalang.nav.navigation.*
import de.dalang.nav.search.SearchRepository
import de.dalang.nav.search.SearchResult
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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

    private val searchRepository: SearchRepository = SearchRepository()
    private val routeRepository: RouteRepository = RouteRepository()
    private val voiceGuidance: VoiceGuidanceManager = VoiceGuidanceManager()

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

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

                // Live-Updates
                provider.locationUpdates(1000L)
                    .catch { e ->
                        CrashLogger.logError("MainViewModel", "Location updates error", e)
                    }
                    .collect { location ->
                        try {
                            val newLocation = LatLng(location.latitude, location.longitude)
                            _currentLocation.value = newLocation

                            // Speed und Bearing extrahieren
                            if (location.hasSpeed()) {
                                _speed.value = location.speed
                            }
                            if (location.hasBearing()) {
                                _bearing.value = location.bearing
                            }

                            if (_navigationState.value.isNavigating) {
                                updateNavigation(newLocation)
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
        viewModelScope.launch(exceptionHandler) {
            try {
                val from = _currentLocation.value
                if (from == null) {
                    CrashLogger.logError("MainViewModel", "No current location for route")
                    _errorMessage.value = "Kein GPS-Signal"
                    return@launch
                }
                val to = LatLng(result.lat, result.lon)

                _navigationState.update { it.copy(isRecalculating = true) }

                val route = routeRepository.getRoute(from, to)
                if (route != null) {
                    CrashLogger.log("MainViewModel: Route found with ${route.steps.size} steps")
                    _navigationState.update {
                        it.copy(
                            route = route,
                            destination = to,
                            destinationName = result.displayName.split(",").first(),
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
                    CrashLogger.logError("MainViewModel", "No route found")
                    _errorMessage.value = "Route konnte nicht berechnet werden. Siehe Einstellungen > Debug Log"
                    _navigationState.update { it.copy(isRecalculating = false) }
                }

                clearSearch()
            } catch (e: Exception) {
                CrashLogger.logError("MainViewModel", "selectDestination failed", e)
                _errorMessage.value = "Fehler: ${e.message}"
                _navigationState.update { it.copy(isRecalculating = false) }
            }
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

            // Reset voice guidance for new navigation
            voiceGuidance.reset()

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

            // Erste Ansage nur wenn erster Schritt nah genug
            state.route.steps.firstOrNull()?.let { step ->
                val currentLoc = _currentLocation.value
                if (currentLoc != null) {
                    val dist = currentLoc.distanceTo(step.maneuver.location)
                    if (dist < 200) {
                        speakInstruction(step)
                    }
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "startNavigation failed", e)
        }
    }

    fun stopNavigation() {
        CrashLogger.log("MainViewModel: stopNavigation")
        try {
            _navigationState.update {
                NavigationState()
            }

            val intent = Intent(getApplication(), NavigationService::class.java).apply {
                action = NavigationService.ACTION_STOP
            }
            getApplication<Application>().startService(intent)
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "stopNavigation failed", e)
        }
    }

    /**
     * Fügt einen Zwischenstopp zur aktuellen Route hinzu.
     * Berechnet neue Route: Aktueller Standort -> Waypoint -> Ursprüngliches Ziel
     */
    fun addWaypoint(waypoint: LatLng) {
        CrashLogger.log("MainViewModel: addWaypoint to ${waypoint.lat},${waypoint.lng}")
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
                val routeToWaypoint = routeRepository.getRoute(current, waypoint)

                // Route vom Waypoint zum ursprünglichen Ziel
                val routeToDestination = routeRepository.getRoute(waypoint, destination)

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

                    // Reset voice guidance for new route
                    voiceGuidance.reset()

                    _navigationState.update {
                        it.copy(
                            route = combinedRoute,
                            currentStepIndex = 0,
                            isRecalculating = false
                        )
                    }

                    // Short announcement for route recalculation
                    navigationService?.speak("Neue Route berechnet")
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

    private fun updateNavigation(location: LatLng) {
        try {
            val state = _navigationState.value
            val route = state.route ?: return
            val currentStep = state.currentStep ?: return
            val currentSpeedMs = _speed.value

            // Distanz zum nächsten Manöver
            val distanceToManeuver = location.distanceTo(currentStep.maneuver.location)

            // Prüfen ob wir den nächsten Schritt erreicht haben
            if (distanceToManeuver < 30 && state.currentStepIndex < route.steps.size - 1) {
                val nextIndex = state.currentStepIndex + 1
                val nextStep = route.steps[nextIndex]
                val distanceToNext = location.distanceTo(nextStep.maneuver.location)

                _navigationState.update {
                    it.copy(
                        currentStepIndex = nextIndex,
                        distanceToNextStep = distanceToNext
                    )
                }

                // Notify voice guidance about step change
                voiceGuidance.onStepChanged(nextIndex)

                // Only announce next step if it's close enough
                val announcement = voiceGuidance.getStepTransitionAnnouncement(
                    currentStep = nextStep,
                    nextStep = route.steps.getOrNull(nextIndex + 1),
                    distanceToNext = distanceToNext,
                    currentSpeedMs = currentSpeedMs
                )
                if (announcement != null) {
                    navigationService?.speak(announcement)
                }

                updateServiceNotification(nextStep, distanceToNext)
            } else {
                _navigationState.update {
                    it.copy(distanceToNextStep = distanceToManeuver)
                }

                // Use voice guidance manager for smart announcements
                val announcement = voiceGuidance.getAnnouncement(
                    step = currentStep,
                    nextStep = state.nextStep,
                    distanceToManeuver = distanceToManeuver,
                    currentSpeedMs = currentSpeedMs,
                    stepIndex = state.currentStepIndex
                )
                if (announcement != null) {
                    navigationService?.speak(announcement)
                }
            }

            // Prüfen ob am Ziel
            val destination = state.destination
            if (destination != null && location.distanceTo(destination) < 20) {
                CrashLogger.log("MainViewModel: Arrived at destination")
                _navigationState.update { it.copy(hasArrived = true, isNavigating = false) }
                navigationService?.speakNow("Ziel erreicht")
                stopNavigation()
            }

            // Gesamtdistanz und Zeit aktualisieren
            updateRemainingDistance(location, route, state.currentStepIndex)
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "updateNavigation failed", e)
        }
    }

    private fun updateRemainingDistance(location: LatLng, route: Route, fromStep: Int) {
        try {
            var remaining = 0.0
            var remainingTime = 0.0

            for (i in fromStep until route.steps.size) {
                remaining += route.steps[i].distance
                remainingTime += route.steps[i].duration
            }

            _navigationState.update {
                it.copy(
                    totalDistanceRemaining = remaining,
                    totalTimeRemaining = remainingTime
                )
            }
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "updateRemainingDistance failed", e)
        }
    }

    private fun speakInstruction(step: RouteStep) {
        try {
            val instruction = step.toGermanInstruction()
            navigationService?.speak(instruction)
        } catch (e: Exception) {
            CrashLogger.logError("MainViewModel", "speakInstruction failed", e)
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
