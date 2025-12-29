package de.dalang.nav

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dalang.nav.location.LocationProvider
import de.dalang.nav.navigation.*
import de.dalang.nav.search.SearchRepository
import de.dalang.nav.search.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val locationProvider = LocationProvider(application)
    private val searchRepository = SearchRepository()
    private val routeRepository = RouteRepository()

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

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

    private var navigationService: NavigationService? = null
    private var locationJob: Job? = null
    private var searchJob: Job? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NavigationService.LocalBinder
            navigationService = binder?.getService()
            navigationService?.voiceEnabled = _voiceEnabled.value
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            navigationService = null
            serviceBound = false
        }
    }

    private fun bindNavigationService() {
        if (serviceBound) return
        try {
            val intent = Intent(getApplication(), NavigationService::class.java)
            getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            // Service binding failed - ignore
        }
    }

    fun startLocationUpdates() {
        if (!LocationProvider.hasLocationPermission(getApplication())) return

        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            // Letzte bekannte Position
            locationProvider.getLastKnownLocation()?.let { location ->
                _currentLocation.value = LatLng(location.latitude, location.longitude)
            }

            // Live-Updates
            locationProvider.locationUpdates(1000L).collect { location ->
                val newLocation = LatLng(location.latitude, location.longitude)
                _currentLocation.value = newLocation

                if (_navigationState.value.isNavigating) {
                    updateNavigation(newLocation)
                }
            }
        }
    }

    fun stopLocationUpdates() {
        locationJob?.cancel()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()
        if (query.length >= 3) {
            searchJob = viewModelScope.launch {
                delay(300) // Debounce
                _isSearching.value = true
                _searchResults.value = searchRepository.search(query)
                _isSearching.value = false
            }
        } else {
            _searchResults.value = emptyList()
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun selectDestination(result: SearchResult) {
        viewModelScope.launch {
            val from = _currentLocation.value ?: return@launch
            val to = LatLng(result.lat, result.lon)

            _navigationState.update { it.copy(isRecalculating = true) }

            val route = routeRepository.getRoute(from, to)
            if (route != null) {
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
            } else {
                _navigationState.update { it.copy(isRecalculating = false) }
            }

            clearSearch()
        }
    }

    fun startNavigation() {
        val state = _navigationState.value
        if (state.route == null) return

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
            // Foreground service start failed
        }

        // Erste Ansage
        state.route.steps.firstOrNull()?.let { step ->
            speakInstruction(step)
        }
    }

    fun stopNavigation() {
        _navigationState.update {
            NavigationState()
        }

        val intent = Intent(getApplication(), NavigationService::class.java).apply {
            action = NavigationService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    private fun updateNavigation(location: LatLng) {
        val state = _navigationState.value
        val route = state.route ?: return
        val currentStep = state.currentStep ?: return

        // Distanz zum nächsten Manöver
        val distanceToManeuver = location.distanceTo(currentStep.maneuver.location)

        // Prüfen ob wir den nächsten Schritt erreicht haben
        if (distanceToManeuver < 30 && state.currentStepIndex < route.steps.size - 1) {
            val nextIndex = state.currentStepIndex + 1
            val nextStep = route.steps[nextIndex]

            _navigationState.update {
                it.copy(
                    currentStepIndex = nextIndex,
                    distanceToNextStep = location.distanceTo(nextStep.maneuver.location)
                )
            }

            speakInstruction(nextStep)
            updateServiceNotification(nextStep, distanceToManeuver)
        } else {
            _navigationState.update {
                it.copy(distanceToNextStep = distanceToManeuver)
            }

            // Voransage bei 200m, 100m, 50m
            when {
                distanceToManeuver in 190.0..210.0 -> speakDistance(200, currentStep)
                distanceToManeuver in 90.0..110.0 -> speakDistance(100, currentStep)
                distanceToManeuver in 45.0..55.0 -> speakDistance(50, currentStep)
            }
        }

        // Prüfen ob am Ziel
        val destination = state.destination
        if (destination != null && location.distanceTo(destination) < 20) {
            _navigationState.update { it.copy(hasArrived = true, isNavigating = false) }
            navigationService?.speakNow("Ziel erreicht")
            stopNavigation()
        }

        // Gesamtdistanz und Zeit aktualisieren
        updateRemainingDistance(location, route, state.currentStepIndex)
    }

    private fun updateRemainingDistance(location: LatLng, route: Route, fromStep: Int) {
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
    }

    private fun speakInstruction(step: RouteStep) {
        val instruction = step.toGermanInstruction()
        navigationService?.speak(instruction)
    }

    private fun speakDistance(meters: Int, step: RouteStep) {
        val direction = when (step.maneuver.type) {
            "turn" -> when (step.maneuver.modifier) {
                "left", "slight left", "sharp left" -> "links abbiegen"
                "right", "slight right", "sharp right" -> "rechts abbiegen"
                "uturn" -> "wenden"
                else -> ""
            }
            "roundabout", "rotary" -> "in den Kreisverkehr"
            else -> ""
        }
        if (direction.isNotBlank()) {
            navigationService?.speak("In $meters Metern $direction")
        }
    }

    private fun updateServiceNotification(step: RouteStep, distance: Double) {
        navigationService?.updateNotification(
            step.toGermanInstruction(),
            distance.formatDistance()
        )
    }

    fun setVoiceEnabled(enabled: Boolean) {
        _voiceEnabled.value = enabled
        navigationService?.voiceEnabled = enabled
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationUpdates()
        if (serviceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
                serviceBound = false
            } catch (e: Exception) {
                // Service not bound
            }
        }
    }
}
