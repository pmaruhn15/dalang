package de.dalang.nav.navigation

import android.content.Context
import com.stadiamaps.ferrostar.core.*
import com.stadiamaps.ferrostar.core.http.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.AndroidLocationProvider
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uniffi.ferrostar.*
import java.util.concurrent.TimeUnit

/**
 * Manager-Klasse die FerrostarCore kapselt und mit unserer App integriert.
 *
 * Ersetzt:
 * - LocationSmoother (Ferrostar macht das intern)
 * - DistanceSmoother (Ferrostar's State Machine)
 * - MapMatcher (Ferrostar's spatial algorithms)
 * - Teile von MainViewModel's Navigation-Logik
 */
class FerrostarManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // HTTP Client für Route Requests
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val httpClient = okHttpClient.toOkHttpClientProvider()

    // Custom Route Provider für HERE + OSRM Fallback
    private val customRouteProvider = HereRouteProvider()
    private val routeProvider = RouteProvider.CustomProvider(customRouteProvider)

    // Android Location Provider (ohne Google Play Services)
    private val locationProvider = AndroidLocationProvider(context)

    // FerrostarCore - das Herzstück
    private var core: FerrostarCore? = null

    // Navigation State als Flow für UI
    private val _navigationState = MutableStateFlow<FerrostarNavigationState>(FerrostarNavigationState.Idle)
    val navigationState: StateFlow<FerrostarNavigationState> = _navigationState.asStateFlow()

    // Aktuelle Route
    private val _currentRoute = MutableStateFlow<Route?>(null)
    val currentRoute: StateFlow<Route?> = _currentRoute.asStateFlow()

    // Location Updates (für MapView)
    private val _userLocation = MutableStateFlow<UserLocation?>(null)
    val userLocation: StateFlow<UserLocation?> = _userLocation.asStateFlow()

    init {
        setupCore()
        observeLocationUpdates()
    }

    private fun setupCore() {
        try {
            val config = NavigationControllerConfig(
                // Step Advance Konfiguration
                stepAdvance = StepAdvanceMode.DistanceToEndOfStep(
                    distance = 30u,
                    minimumHorizontalAccuracy = 25u
                ),
                // Route Deviation Tracking
                routeDeviationTracking = RouteDeviationTracking.StaticThreshold(
                    minimumHorizontalAccuracy = 25u,
                    maxAcceptableDeviation = 50.0
                )
            )

            core = FerrostarCore(
                routeProvider = routeProvider,
                httpClient = httpClient,
                locationProvider = locationProvider,
                navigationControllerConfig = config
            )

            // Navigation State beobachten
            scope.launch {
                core?.state?.collect { state ->
                    updateNavigationState(state)
                }
            }

            CrashLogger.log("FerrostarManager: Core initialized")
        } catch (e: Exception) {
            CrashLogger.logError("FerrostarManager", "Core setup failed", e)
        }
    }

    private fun updateNavigationState(state: NavigationState) {
        _navigationState.value = when (val tripState = state.tripState) {
            is TripState.Navigating -> {
                FerrostarNavigationState.Navigating(
                    tripState = tripState,
                    route = _currentRoute.value
                )
            }
            is TripState.Complete -> {
                _currentRoute.value = null
                FerrostarNavigationState.Arrived
            }
            is TripState.Idle -> FerrostarNavigationState.Idle
        }
    }

    private fun observeLocationUpdates() {
        scope.launch {
            locationProvider.lastLocation?.let { location ->
                _userLocation.value = location
            }
        }
    }

    /**
     * Berechnet Route zu einem Ziel.
     */
    suspend fun calculateRoute(
        destination: GeographicCoordinate,
        waypoints: List<GeographicCoordinate> = emptyList()
    ): Route? {
        val ferrostar = core ?: return null
        val userLoc = _userLocation.value ?: return null

        try {
            _navigationState.value = FerrostarNavigationState.Calculating

            // Waypoints erstellen
            val ferrostarWaypoints = buildList {
                waypoints.forEach { coord ->
                    add(Waypoint(coord, WaypointKind.VIA))
                }
                add(Waypoint(destination, WaypointKind.BREAK))
            }

            // Route berechnen
            val routes = ferrostar.getRoutes(userLoc, ferrostarWaypoints)

            if (routes.isNotEmpty()) {
                val route = routes.first()
                _currentRoute.value = route
                _navigationState.value = FerrostarNavigationState.RouteReady(route)
                CrashLogger.log("FerrostarManager: Route calculated - ${route.steps.size} steps, ${route.distance}m")
                return route
            } else {
                _navigationState.value = FerrostarNavigationState.Error("Keine Route gefunden")
                return null
            }
        } catch (e: Exception) {
            CrashLogger.logError("FerrostarManager", "Route calculation failed", e)
            _navigationState.value = FerrostarNavigationState.Error(e.message ?: "Fehler")
            return null
        }
    }

    /**
     * Startet die Navigation auf der aktuellen Route.
     */
    fun startNavigation(): Boolean {
        val ferrostar = core ?: return false
        val route = _currentRoute.value ?: return false

        try {
            ferrostar.startNavigation(route)
            CrashLogger.log("FerrostarManager: Navigation started")
            return true
        } catch (e: Exception) {
            CrashLogger.logError("FerrostarManager", "Start navigation failed", e)
            return false
        }
    }

    /**
     * Stoppt die aktuelle Navigation.
     */
    fun stopNavigation() {
        try {
            core?.stopNavigation()
            _currentRoute.value = null
            _navigationState.value = FerrostarNavigationState.Idle
            CrashLogger.log("FerrostarManager: Navigation stopped")
        } catch (e: Exception) {
            CrashLogger.logError("FerrostarManager", "Stop navigation failed", e)
        }
    }

    /**
     * Fügt einen Zwischenstopp hinzu (z.B. McDonald's, Tankstelle).
     */
    suspend fun addWaypoint(location: GeographicCoordinate, name: String): Boolean {
        val currentDest = _currentRoute.value?.waypoints?.lastOrNull()?.coordinate
            ?: return false

        // Neue Route mit Waypoint berechnen
        val newRoute = calculateRoute(
            destination = currentDest,
            waypoints = listOf(location)
        )

        if (newRoute != null && _navigationState.value is FerrostarNavigationState.Navigating) {
            // Navigation neu starten mit neuer Route
            startNavigation()
            CrashLogger.log("FerrostarManager: Waypoint added - $name")
            return true
        }

        return false
    }

    /**
     * Location Provider starten (für GPS Updates).
     */
    fun startLocationUpdates() {
        try {
            // AndroidLocationProvider uses callbacks internally
            CrashLogger.log("FerrostarManager: Location updates started")
        } catch (e: Exception) {
            CrashLogger.logError("FerrostarManager", "Start location failed", e)
        }
    }

    /**
     * Location Provider stoppen.
     */
    fun stopLocationUpdates() {
        try {
            CrashLogger.log("FerrostarManager: Location updates stopped")
        } catch (e: Exception) {
            CrashLogger.logError("FerrostarManager", "Stop location failed", e)
        }
    }

    fun cleanup() {
        stopNavigation()
        stopLocationUpdates()
    }
}

/**
 * Navigation State für UI.
 * Vereinfacht Ferrostar's TripState für unsere App.
 */
sealed class FerrostarNavigationState {
    object Idle : FerrostarNavigationState()
    object Calculating : FerrostarNavigationState()

    data class RouteReady(
        val route: Route
    ) : FerrostarNavigationState()

    data class Navigating(
        val tripState: TripState.Navigating,
        val route: Route?
    ) : FerrostarNavigationState() {
        // Convenience properties für UI
        val distanceToNextManeuver: Double
            get() = tripState.progress?.distanceToNextManeuver ?: 0.0

        val distanceRemaining: Double
            get() = tripState.progress?.distanceRemaining ?: 0.0

        val durationRemaining: Double
            get() = tripState.progress?.durationRemaining ?: 0.0

        val currentStepInstruction: String
            get() = tripState.progress?.currentStep?.instruction ?: ""

        val currentRoadName: String?
            get() = tripState.progress?.currentStep?.roadName

        val snappedLocation: GeographicCoordinate?
            get() = tripState.snappedUserLocation?.coordinates

        val isOffRoute: Boolean
            get() = tripState.deviation is RouteDeviation.OffRoute
    }

    object Arrived : FerrostarNavigationState()

    data class Error(val message: String) : FerrostarNavigationState()
}
